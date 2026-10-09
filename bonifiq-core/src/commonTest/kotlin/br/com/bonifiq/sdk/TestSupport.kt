package br.com.bonifiq.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

const val TEST_TENANT = "tenant-teste"

class Recorded(val data: HttpRequestData, val body: String) {
    val method get() = data.method.value
    val path get() = data.url.encodedPath
    fun param(name: String) = data.url.parameters[name]
    val tenant get() = data.headers["X-Bq-Tenant"]
    val token get() = data.headers["X-Bq-SecureToken"]
}

/** BonifiQ com engine fake e relógio controlável. */
class FakeBonifiQ(
    private val handler: suspend MockRequestHandleScope.(Recorded) -> HttpResponseData,
) {
    var now = 1_000_000L
    val requests = mutableListOf<Recorded>()

    val engine = MockEngine { data ->
        val recorded = Recorded(data, data.body.toByteArray().decodeToString())
        requests += recorded
        handler(recorded)
    }

    val sdk = BonifiQ(BonifiQConfig(tenant = TEST_TENANT), engine) { now }

    fun advanceMinutes(minutes: Int) {
        now += minutes * 60_000L
    }
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"))

fun envelope(item: String, hasError: Boolean = false, message: String? = null): String =
    """{"HasError":$hasError,"Message":${message?.let { "\"$it\"" } ?: "null"},"Item":$item}"""

fun secureLogin(token: String) =
    envelope("""{"Email":"cliente@teste.com","Name":"Cliente Teste","UserId":"cliente@teste.com","SecureToken":"$token"}""")
