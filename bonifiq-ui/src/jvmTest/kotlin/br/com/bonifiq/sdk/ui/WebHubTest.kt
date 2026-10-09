package br.com.bonifiq.sdk.ui

import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.BonifiQConfig
import br.com.bonifiq.sdk.auth.VtexSessionTokens
import br.com.bonifiq.sdk.ui.web.HubChoice
import br.com.bonifiq.sdk.ui.web.WebHubBridge
import br.com.bonifiq.sdk.ui.web.buildEmbedUrl
import br.com.bonifiq.sdk.ui.web.jsStringLiteral
import br.com.bonifiq.sdk.ui.web.resolveHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebHubTest {

    /** A ponte num escopo próprio: [settle] espera as chamadas que ela dispara (rede do MockEngine inclusa). */
    private class Harness(parent: CoroutineScope, sdk: BonifiQ) {
        private val job = SupervisorJob() // sem pai: o runTest não espera por ele; quem espera é o settle()
        val sent = mutableListOf<String>()
        val events = mutableListOf<LoyaltyEvent>()
        val opened = mutableListOf<String>()
        var ready = 0
        var failures = 0
        val bridge = WebHubBridge(
            bonifiq = sdk,
            scope = CoroutineScope(parent.coroutineContext + job),
            onEvent = { events += it },
            openUrl = { opened += it },
            onReady = { ready++ },
            onFailure = { failures++ },
        ).also { it.sendToWeb = { json -> sent += json } }

        suspend fun settle() = job.children.toList().forEach { it.join() }

        fun sentTypes() = sent.map { Json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content }
    }

    @Test
    fun readyDeliversCustomerAndTokenWhenLoggedIn() = runTest {
        val server = FakeBonifiQServer()
        server.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }
        val harness = Harness(this, server.sdk)

        harness.bridge.onMessageFromWeb("""{"type":"ready","version":1}""")
        harness.settle()

        assertEquals(1, harness.ready)
        val auth = Json.parseToJsonElement(harness.sent.single()).jsonObject
        assertEquals("auth", auth["type"]!!.jsonPrimitive.content)
        assertEquals("tok-1", auth["secureToken"]!!.jsonPrimitive.content)
        assertEquals("cliente@teste.com", auth["customer"]!!.jsonObject["email"]!!.jsonPrimitive.content)
        assertEquals("user-1", auth["customer"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun anonymousReadySendsNothingAndLogoutIsPushedAfterLogin() = runTest {
        val server = FakeBonifiQServer()
        val harness = Harness(this, server.sdk)

        harness.bridge.onMessageFromWeb("""{"type":"ready","version":1}""")
        harness.settle()
        assertTrue(harness.sent.isEmpty(), "sem cliente: o widget fica no modo anônimo")

        server.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }
        harness.bridge.onAuthStateChanged()
        harness.settle()
        server.sdk.auth.logout()
        harness.bridge.onAuthStateChanged()

        assertEquals(listOf("auth", "logout"), harness.sentTypes())
    }

    @Test
    fun widgetEventsBecomeLoyaltyEvents() = runTest {
        val harness = Harness(this, FakeBonifiQServer().sdk)

        harness.bridge.onMessageFromWeb("""{"type":"applyCoupon","couponCode":"BQ-123"}""")
        harness.bridge.onMessageFromWeb("""{"type":"loginRequired"}""")
        harness.bridge.onMessageFromWeb("""{"type":"close"}""")
        harness.bridge.onMessageFromWeb("""{"type":"openUrl","url":"https://wa.me/?text=oi"}""")
        harness.bridge.onMessageFromWeb("""{"type":"openUrl","url":"javascript:alert(1)"}""")
        harness.bridge.onMessageFromWeb("""{"type":"error","message":"config indisponível"}""")
        harness.bridge.onMessageFromWeb("""não é json""")
        harness.bridge.onMessageFromWeb("""{"type":"desconhecido"}""")

        assertEquals(
            listOf(LoyaltyEvent.ApplyCoupon("BQ-123", null), LoyaltyEvent.LoginRequired, LoyaltyEvent.Close),
            harness.events,
        )
        assertEquals(listOf("https://wa.me/?text=oi"), harness.opened)
        assertEquals(1, harness.failures)
    }

    @Test
    fun tokenExpiredRenewsThroughVtexSession() = runTest {
        val server = FakeBonifiQServer()
        server.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }
        val harness = Harness(this, server.sdk)

        harness.bridge.onMessageFromWeb("""{"type":"tokenExpired"}""")
        harness.settle()

        val refreshed = Json.parseToJsonElement(harness.sent.single()).jsonObject
        assertEquals("tokenRefreshed", refreshed["type"]!!.jsonPrimitive.content)
        assertEquals("tok-2", refreshed["secureToken"]!!.jsonPrimitive.content)
    }

    @Test
    fun hubOpensTheStoreWidget() = runTest {
        val server = FakeBonifiQServer()
        server.appConfig = """{"ClientURL":"https://widget.bonifiq.com.br/"}"""

        assertEquals(
            HubChoice.Web("https://widget.bonifiq.com.br/?bqapp=1&tenant=tenant-teste"),
            resolveHub(server.freshSdk(), platformSupportsWeb = true),
        )
    }

    @Test
    fun configuredWidgetUrlSkipsTheStoreLookup() = runTest {
        val server = FakeBonifiQServer() // app/config fora do ar (404)
        val outroWidget = server.freshSdk(BonifiQConfig(tenant = "tenant-teste", widgetUrl = "https://widget.exemplo.com"))

        assertEquals(HubChoice.Web("https://widget.exemplo.com/?bqapp=1&tenant=tenant-teste"), resolveHub(outroWidget, true))
    }

    @Test
    fun hubIsUnavailableWithoutWidgetUrlOrWebView() = runTest {
        val server = FakeBonifiQServer()

        // Config da loja fora do ar ou URL inválida: vale tentar de novo.
        assertEquals(HubChoice.Unavailable(retryable = true), resolveHub(server.sdk, platformSupportsWeb = true))
        server.appConfig = """{"ClientURL":"javascript:alert(1)"}"""
        assertEquals(HubChoice.Unavailable(retryable = true), resolveHub(server.freshSdk(), platformSupportsWeb = true))

        // Sem WebView com ponte segura (desktop, Android antigo): tentar de novo não adianta.
        server.appConfig = """{"ClientURL":"https://widget.bonifiq.com.br"}"""
        assertEquals(HubChoice.Unavailable(retryable = false), resolveHub(server.freshSdk(), platformSupportsWeb = false))
    }

    @Test
    fun embedUrlCarriesApiOnlyWhenNotDefaultAndNeverTheToken() {
        val outraApi = BonifiQConfig(tenant = "tenant teste", baseUrl = "https://api.exemplo.com/")
        assertEquals(
            "https://widget.bonifiq.com.br/?bqapp=1&tenant=tenant%20teste&api=https%3A%2F%2Fapi.exemplo.com",
            buildEmbedUrl("https://widget.bonifiq.com.br", outraApi),
        )
    }

    @Test
    fun jsStringLiteralEscapesForEvaluateJavascript() {
        assertEquals("\"{\\\"type\\\":\\\"logout\\\"}\"", jsStringLiteral("""{"type":"logout"}"""))
        assertEquals("\"linha\\nnova \\\\ </script>\"", jsStringLiteral("linha\nnova \\ </script>"))
    }

    /** Nova instância com o mesmo servidor (o appConfig fica em cache por instância). */
    private fun FakeBonifiQServer.freshSdk(config: BonifiQConfig = BonifiQConfig(tenant = "tenant-teste")) =
        BonifiQ(config, engine)
}
