package br.com.bonifiq.sdk.ui

import br.com.bonifiq.sdk.ui.web.WebHubBridge
import br.com.bonifiq.sdk.ui.web.auth.AuthSessionLauncher
import br.com.bonifiq.sdk.ui.web.auth.AuthSessionRegistry
import br.com.bonifiq.sdk.ui.web.auth.AuthSessionResult
import br.com.bonifiq.sdk.ui.web.auth.BonifiQAuthRedirect
import br.com.bonifiq.sdk.ui.web.auth.callbackSchemeFor
import br.com.bonifiq.sdk.ui.web.auth.isSafeAppUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthSessionTest {

    private class FakeLauncher : AuthSessionLauncher {
        override val callbackScheme = "bq.com.loja.app"
        val launched = mutableListOf<Pair<String, String?>>()
        override fun launch(url: String, appUrl: String?) {
            launched += url to appUrl
        }
    }

    private class Harness(launcher: AuthSessionLauncher?) {
        val sent = mutableListOf<JsonObject>()
        val bridge = WebHubBridge(
            bonifiq = FakeBonifiQServer().sdk,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onEvent = {},
            openUrl = {},
            onReady = {},
            onFailure = {},
            authLauncher = launcher,
        ).also { it.sendToWeb = { json -> sent += Json.parseToJsonElement(json).jsonObject } }

        fun last(type: String) = sent.last { it["type"]!!.jsonPrimitive.content == type }
    }

    private val stravaWeb = "https://www.strava.com/oauth/mobile/authorize?client_id=1&state=abc"
    private val stravaApp = "strava://oauth/mobile/authorize?client_id=1&state=abc"

    @AfterTest
    fun clearSession() = AuthSessionRegistry.finish(AuthSessionResult.Cancelled)

    @Test
    fun readyAnnouncesCallbackSchemeOnlyWhenPlatformSupportsIt() {
        val supported = Harness(FakeLauncher())
        supported.bridge.onMessageFromWeb("""{"type":"ready","version":1}""")
        val capabilities = supported.last("capabilities")
        assertEquals("bq.com.loja.app", capabilities["authSession"]!!.jsonObject["callbackScheme"]!!.jsonPrimitive.content)

        val unsupported = Harness(null)
        unsupported.bridge.onMessageFromWeb("""{"type":"ready","version":1}""")
        assertTrue(unsupported.sent.isEmpty(), "sem launcher o widget segue no fluxo antigo")
    }

    @Test
    fun openAuthLaunchesAndReturnsTheCallbackWithTheSameRequestId() {
        val launcher = FakeLauncher()
        val harness = Harness(launcher)

        harness.bridge.onMessageFromWeb("""{"type":"openAuth","requestId":"r1","url":"$stravaWeb","appUrl":"$stravaApp"}""")
        assertEquals(listOf<Pair<String, String?>>(stravaWeb to stravaApp), launcher.launched)

        assertFalse(BonifiQAuthRedirect.handle("bq.outro.app://api/strava?code=x"), "esquema de outro app é ignorado")
        assertTrue(BonifiQAuthRedirect.handle("bq.com.loja.app://api.bonifiq.com.br/strava?code=c1&state=abc"))

        val result = harness.last("authResult")
        assertEquals("r1", result["requestId"]!!.jsonPrimitive.content)
        assertEquals("success", result["status"]!!.jsonPrimitive.content)
        assertEquals("bq.com.loja.app://api.bonifiq.com.br/strava?code=c1&state=abc", result["url"]!!.jsonPrimitive.content)
        assertFalse(BonifiQAuthRedirect.handle("bq.com.loja.app://api/strava?code=de-novo"), "sessão já encerrada")
    }

    @Test
    fun newOpenAuthCancelsThePreviousOne() {
        val harness = Harness(FakeLauncher())

        harness.bridge.onMessageFromWeb("""{"type":"openAuth","requestId":"r1","url":"$stravaWeb"}""")
        harness.bridge.onMessageFromWeb("""{"type":"openAuth","requestId":"r2","url":"$stravaWeb"}""")

        val first = harness.sent.single { it["type"]!!.jsonPrimitive.content == "authResult" }
        assertEquals("r1", first["requestId"]!!.jsonPrimitive.content)
        assertEquals("cancelled", first["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun unsafeUrlsAreRefused() {
        val launcher = FakeLauncher()
        val harness = Harness(launcher)

        harness.bridge.onMessageFromWeb("""{"type":"openAuth","requestId":"r1","url":"http://www.strava.com/oauth"}""")
        assertEquals("error", harness.last("authResult")["status"]!!.jsonPrimitive.content)
        assertTrue(launcher.launched.isEmpty(), "login só por https")

        harness.bridge.onMessageFromWeb("""{"type":"openAuth","requestId":"r2","url":"$stravaWeb","appUrl":"javascript:alert(1)"}""")
        assertNull(launcher.launched.single().second, "appUrl perigoso é descartado; segue pelo login web")
    }

    @Test
    fun withoutLauncherOpenAuthAnswersUnsupported() {
        val harness = Harness(null)
        harness.bridge.onMessageFromWeb("""{"type":"openAuth","requestId":"r1","url":"$stravaWeb"}""")
        val result = harness.last("authResult")
        assertEquals("error", result["status"]!!.jsonPrimitive.content)
        assertEquals("unsupported", result["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun callbackSchemeComesFromTheAppIdOnlyWhenSafe() {
        assertEquals("bq.com.loja.app", callbackSchemeFor("com.loja.app"))
        assertEquals("bq.com.loja-app2", callbackSchemeFor("com.loja-app2"))
        assertNull(callbackSchemeFor("com.loja_app"), "_ não é válido em esquema de URL")
        assertNull(callbackSchemeFor("com.Loja.App"), "no Android a caixa importa: o iOS passa o id em minúsculas")
        assertNull(callbackSchemeFor("a".repeat(120)))
    }

    @Test
    fun appUrlMustBeAnotherAppsOwnScheme() {
        val mine = "bq.com.loja.app"
        assertTrue(isSafeAppUrl(stravaApp, mine))
        listOf(
            "https://www.strava.com/oauth",
            "http://x",
            "javascript:alert(1)",
            "data:text/html,oi",
            "file:///etc/passwd",
            "intent://x#Intent;end",
            "content://x",
            "$mine://api/strava",
            "strava:oauth",
        ).forEach { assertFalse(isSafeAppUrl(it, mine), it) }
    }
}
