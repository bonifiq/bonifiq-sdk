package br.com.bonifiq.sdk.internal

import br.com.bonifiq.sdk.BonifiQConfig
import br.com.bonifiq.sdk.BonifiQException
import br.com.bonifiq.sdk.auth.SecureTokenManager
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

internal val BonifiQJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

/** Como uma chamada se relaciona com o SecureToken do cliente. */
internal enum class AuthMode {
    /** Endpoint público: nunca envia token. */
    None,

    /** Endpoint público que personaliza a resposta se houver cliente logado (ex.: lista de recompensas). */
    Optional,

    /** Endpoint autenticado: exige token válido, renova e repete uma vez em caso de 401. */
    Required,
}

/**
 * Cliente HTTP da API Widget (`{baseUrl}/pub/widget/`). Envia `X-Bq-Tenant` sempre e `X-Bq-SecureToken`
 * conforme [AuthMode], desembrulha o envelope `{HasError, Message, Item}` e mapeia erros para [BonifiQException].
 */
internal class WidgetHttp(
    config: BonifiQConfig,
    engine: HttpClientEngine?,
    private val tokens: SecureTokenManager,
) {
    private val client: HttpClient = run {
        val widgetBaseUrl = config.baseUrl.trimEnd('/') + "/pub/widget/"
        val configure: HttpClientConfig<*>.() -> Unit = {
            expectSuccess = false
            install(ContentNegotiation) { json(BonifiQJson) }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
            }
            if (config.enableLogging) {
                install(Logging) {
                    logger = Logger.SIMPLE
                    level = LogLevel.INFO
                }
            }
            defaultRequest {
                url(widgetBaseUrl)
                header(HEADER_TENANT, config.tenant)
            }
        }
        if (engine != null) HttpClient(engine, configure) else HttpClient(configure)
    }

    suspend inline fun <reified T> send(
        method: HttpMethod,
        path: String,
        auth: AuthMode,
        noinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T = decode(execute(method, path, auth, configure))

    /** Faz a chamada e só verifica erros (corpo ignorado). */
    suspend fun sendIgnoringBody(
        method: HttpMethod,
        path: String,
        auth: AuthMode,
        configure: HttpRequestBuilder.() -> Unit = {},
    ) {
        unwrap(parse(execute(method, path, auth, configure)))
    }

    suspend fun execute(
        method: HttpMethod,
        path: String,
        auth: AuthMode,
        configure: HttpRequestBuilder.() -> Unit,
    ): String = when (auth) {
        AuthMode.None -> call(method, path, token = null, configure)
        AuthMode.Optional -> call(method, path, tokens.currentTokenOrNull(), configure)
        AuthMode.Required -> tokens.withToken { token -> call(method, path, token, configure) }
    }

    fun close() = client.close()

    private suspend fun call(
        method: HttpMethod,
        path: String,
        token: String?,
        configure: HttpRequestBuilder.() -> Unit,
    ): String {
        val response = try {
            client.request(path) {
                this.method = method
                if (token != null) header(HEADER_SECURE_TOKEN, token)
                configure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BonifiQException) {
            throw e
        } catch (e: Exception) {
            throw BonifiQException.Network(e)
        }
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BonifiQException.Network(e)
        }
        val status = response.status.value
        if (status == 401 || status == 403) {
            throw BonifiQException.Unauthorized(errorMessage(text) ?: "Sessão BonifiQ inválida ou expirada.")
        }
        if (status !in 200..299) {
            throw BonifiQException.Api(errorMessage(text) ?: "Erro HTTP $status na BonifiQ.", status)
        }
        return text
    }

    private fun errorMessage(body: String): String? {
        val obj = runCatching { normalizeKeys(BonifiQJson.parseToJsonElement(body)) as? JsonObject }.getOrNull()
        return listOf("Message", "Title", "Error")
            .firstNotNullOfOrNull { key -> (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
            ?: body.take(200).takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }
    }

    companion object {
        const val HEADER_TENANT = "X-Bq-Tenant"
        const val HEADER_SECURE_TOKEN = "X-Bq-SecureToken"
    }
}

internal inline fun <reified T> decode(text: String): T {
    val payload = unwrap(parse(text))
    return try {
        BonifiQJson.decodeFromJsonElement<T>(payload)
    } catch (e: IllegalArgumentException) {
        throw BonifiQException.Api("Resposta inesperada da BonifiQ: ${e.message?.take(200)}", 200)
    }
}

internal fun parse(text: String): JsonElement {
    if (text.isBlank()) return JsonNull
    return try {
        normalizeKeys(BonifiQJson.parseToJsonElement(text))
    } catch (e: IllegalArgumentException) {
        // Ex.: página HTML de um proxy ou de um Wi-Fi com login no lugar do JSON da BonifiQ.
        throw BonifiQException.Api("Resposta inesperada da BonifiQ.", 200)
    }
}

/**
 * Desembrulha `{HasError, Message, Item}`. Respostas sem envelope (ex.: endpoints de cashback)
 * passam direto. `HasError: true` vira [BonifiQException.Api] com a mensagem da BonifiQ.
 */
internal fun unwrap(element: JsonElement): JsonElement {
    val obj = element as? JsonObject ?: return element
    val hasError = obj["HasError"] as? JsonPrimitive ?: return element
    if (hasError.booleanOrNull == true) {
        val message = (obj["Message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        throw BonifiQException.Api(message ?: "A BonifiQ não conseguiu processar a solicitação.", 200)
    }
    return obj["Item"] ?: JsonNull
}

/**
 * A API mistura `PascalCase` e `camelCase` (ex.: `hasProductsIneligibleForCashback`). Normalizamos todas as
 * chaves para PascalCase, que é o padrão usado nos `@SerialName` dos modelos.
 */
internal fun normalizeKeys(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.associate { (key, value) -> key.replaceFirstChar { it.uppercaseChar() } to normalizeKeys(value) })
    is JsonArray -> JsonArray(element.map(::normalizeKeys))
    else -> element
}

internal inline fun <reified B> HttpRequestBuilder.jsonBody(body: B) {
    contentType(ContentType.Application.Json)
    setBody(body)
}
