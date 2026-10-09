package br.com.bonifiq.sdk.ui.web

import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.BonifiQInternalApi
import br.com.bonifiq.sdk.auth.AuthState
import br.com.bonifiq.sdk.ui.LoyaltyEvent
import br.com.bonifiq.sdk.ui.internal.attempt
import br.com.bonifiq.sdk.ui.web.auth.AuthSessionLauncher
import br.com.bonifiq.sdk.ui.web.auth.AuthSessionRegistry
import br.com.bonifiq.sdk.ui.web.auth.AuthSessionResult
import br.com.bonifiq.sdk.ui.web.auth.isSafeAppUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Protocolo entre o app e o widget web no WebView (contrato: `docs/webview-bridge.md`). Independe da plataforma:
 * o WebView de cada plataforma só entrega as mensagens da página em [onMessageFromWeb] e fornece [sendToWeb].
 *
 * - página → app: `ready`, `applyCoupon`, `loginRequired`, `tokenExpired`, `openUrl`, `openAuth`, `close`, `error`;
 * - app → página: `capabilities`, `auth` (SecureToken + cliente), `tokenRefreshed`, `logout`, `authResult`.
 *
 * [authLauncher] abre o login de terceiros (ex.: Strava) pedido em `openAuth`; `null` se a plataforma não suporta.
 */
@OptIn(BonifiQInternalApi::class)
internal class WebHubBridge(
    private val bonifiq: BonifiQ,
    private val scope: CoroutineScope,
    private val onEvent: (LoyaltyEvent) -> Unit,
    private val openUrl: (String) -> Unit,
    private val onReady: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val authLauncher: AuthSessionLauncher? = null,
) {
    /** Canal para a página, definido pelo WebView da plataforma. */
    var sendToWeb: ((String) -> Unit)? = null

    private var ready = false
    private var sentAuthenticated = false

    fun onMessageFromWeb(raw: String) {
        val message = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        when (message.string("type")) {
            "ready" -> {
                ready = true
                onReady()
                sendCapabilities()
                if (bonifiq.auth.state.value is AuthState.Authenticated) sendAuth()
            }
            "applyCoupon" -> message.string("couponCode")?.let { onEvent(LoyaltyEvent.ApplyCoupon(it, null)) }
            "loginRequired" -> onEvent(LoyaltyEvent.LoginRequired)
            "close" -> onEvent(LoyaltyEvent.Close)
            "openUrl" -> message.string("url")?.takeIf(::isHttpUrl)?.let(openUrl)
            "openAuth" -> openAuth(message)
            "tokenExpired" -> renewToken()
            "error" -> onFailure(message.string("message") ?: "O widget não carregou.")
        }
    }

    /** O login do app mudou: entrega o cliente à página ou faz logout nela. */
    fun onAuthStateChanged() {
        if (!ready) return
        when (bonifiq.auth.state.value) {
            is AuthState.Authenticated -> sendAuth()
            else -> if (sentAuthenticated) {
                sentAuthenticated = false
                send(buildJsonObject { put("type", "logout") })
            }
        }
    }

    private fun sendAuth() {
        val customer = (bonifiq.auth.state.value as? AuthState.Authenticated)?.customer ?: return
        scope.launch {
            val token = attempt { bonifiq.auth.secureTokenForEmbed() }.getOrNull() ?: return@launch
            sentAuthenticated = true
            send(buildJsonObject {
                put("type", "auth")
                put("secureToken", token)
                putJsonObject("customer") {
                    put("email", customer.email)
                    put("name", customer.name)
                    put("id", customer.userId ?: customer.email)
                }
            })
        }
    }

    /** Avisa a página do que o app sabe fazer além do contrato básico. Hoje: login de terceiros. */
    private fun sendCapabilities() {
        val launcher = authLauncher ?: return
        send(buildJsonObject {
            put("type", "capabilities")
            putJsonObject("authSession") { put("callbackScheme", launcher.callbackScheme) }
        })
    }

    /** `openAuth`: abre o login de terceiros e responde `authResult` com o mesmo `requestId`. */
    private fun openAuth(message: JsonObject) {
        val requestId = message.string("requestId")?.takeIf { it.length <= 100 } ?: return
        val url = message.string("url")?.takeIf { it.startsWith("https://", ignoreCase = true) }
        val launcher = authLauncher
        if (launcher == null || url == null) {
            sendAuthResult(requestId, AuthSessionResult.Failed(if (launcher == null) "unsupported" else "invalid_url"))
            return
        }
        val appUrl = message.string("appUrl")?.takeIf { isSafeAppUrl(it, launcher.callbackScheme) }
        AuthSessionRegistry.start(launcher.callbackScheme) { result -> scope.launch { sendAuthResult(requestId, result) } }
        launcher.launch(url, appUrl)
    }

    private fun sendAuthResult(requestId: String, result: AuthSessionResult) {
        send(buildJsonObject {
            put("type", "authResult")
            put("requestId", requestId)
            when (result) {
                is AuthSessionResult.Success -> {
                    put("status", "success")
                    put("url", result.url)
                }
                AuthSessionResult.Cancelled -> put("status", "cancelled")
                is AuthSessionResult.Failed -> {
                    put("status", "error")
                    put("message", result.message)
                }
            }
        })
    }

    private fun renewToken() {
        scope.launch {
            val token = attempt { bonifiq.auth.renewSecureTokenForEmbed() }.getOrNull()
            if (token != null) {
                send(buildJsonObject {
                    put("type", "tokenRefreshed")
                    put("secureToken", token)
                })
            } else {
                sentAuthenticated = false
                send(buildJsonObject { put("type", "logout") })
                onEvent(LoyaltyEvent.LoginRequired)
            }
        }
    }

    private fun send(message: JsonObject) {
        sendToWeb?.invoke(message.toString())
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun isHttpUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

/**
 * Texto JSON como literal de string JavaScript, para `evaluateJavaScript("...receive(<literal>)")`.
 * `JsonPrimitive(text).toString()` já escapa aspas, barras e quebras de linha.
 */
internal fun jsStringLiteral(text: String): String =
    JsonPrimitive(text).toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
