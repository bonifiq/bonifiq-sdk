package br.com.bonifiq.sdk.push

import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.BonifiQConfig
import br.com.bonifiq.sdk.model.AppConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Simula a API Widget nas rotas que o push usa: login, config da loja e os endpoints de aparelhos e aberturas. */
class FakePushServer(
    /** Item de `app/config`. */
    var appConfig: String = PUSH_ENABLED,
) {
    class Request(val path: String, val secureToken: String?, val tenant: String?, val body: JsonObject?) {
        fun field(name: String): String? = body?.get(name)?.jsonPrimitive?.content
    }

    val requests = MutableStateFlow<List<Request>>(emptyList())
    var logins = 0

    /** `customer/info` responde 401: com login por código, a sessão vira `Expired`. */
    var rejectToken = false

    val engine = MockEngine { request ->
        val path = request.url.encodedPath.removePrefix("/pub/widget/")
        val text = request.body.toByteArray().decodeToString()
        val body = text.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
        requests.update { it + Request(path, request.headers["X-Bq-SecureToken"], request.headers["X-Bq-Tenant"], body) }
        val json = when (path) {
            "vendors/vtex/securelogin" -> env(login("cliente@teste.com", "user-1"))
            "account/validatechallenge" -> env(login(null, "user-2"))
            "app/config" -> env(appConfig)
            "customer/info" -> if (rejectToken) {
                return@MockEngine respond("""{"Message":"Token inválido"}""", HttpStatusCode.Unauthorized)
            } else {
                env("""{"PointsBalance":10}""")
            }
            "push/devices" -> env("""{"Registered":true}""")
            "push/devices/unregister", "push/opened" -> env("null")
            else -> return@MockEngine respond("""{"Message":"rota desconhecida: $path"}""", HttpStatusCode.NotFound)
        }
        respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    val sdk = BonifiQ(BonifiQConfig(tenant = "tenant-teste"), engine)

    fun calls(path: String): List<Request> = requests.value.filter { it.path == path }

    /** Espera até haver [count] chamadas a [path] (o push trabalha em segundo plano). */
    suspend fun await(path: String, count: Int = 1): List<Request> = withTimeout(5_000) {
        requests.first { list -> list.count { it.path == path } >= count }
        calls(path)
    }

    private fun login(email: String?, userId: String): String {
        val token = "tok-${++logins}"
        val emailJson = email?.let { "\"$it\"" } ?: "null"
        return """{"Email":$emailJson,"Name":"Cliente Teste","UserId":"$userId","SecureToken":"$token"}"""
    }

    private fun env(item: String) = """{"HasError":false,"Message":null,"Item":$item}"""

    companion object {
        const val PUSH_ENABLED = """{"ClientURL":"https://widget.bonifiq.com.br","PushEnabled":true,
            "PushFirebaseProjectId":"loja-app","PushAndroidChannelId":"bonifiq_loyalty",
            "PushAndroidChannelName":"Programa de fidelidade"}"""
        const val PUSH_DISABLED = """{"ClientURL":"https://widget.bonifiq.com.br","PushEnabled":false,
            "PushFirebaseProjectId":null}"""
    }
}

/** Plataforma fake: registra como Android e guarda o que o SDK pediu. */
class FakePushPlatform(override val name: String? = "android") : PushPlatform {
    override val appId: String = "com.loja.app"
    override val appVersion: String = "3.2.1"
    override val locale: String = "pt-BR"

    var started = 0
    val pushConfigs = mutableListOf<AppConfig>()
    var status = PushPermissionStatus.NotDetermined
    var requests = 0

    override fun start() {
        started++
    }

    override fun onPushConfig(config: AppConfig) {
        pushConfigs += config
    }

    override suspend fun permissionStatus(): PushPermissionStatus = status

    override suspend fun requestPermission(): PushPermissionStatus {
        requests++
        status = PushPermissionStatus.Granted
        return status
    }

    override fun openNotificationSettings(): Boolean = true
}
