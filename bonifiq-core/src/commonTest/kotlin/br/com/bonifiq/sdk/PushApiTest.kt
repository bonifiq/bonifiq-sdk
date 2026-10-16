package br.com.bonifiq.sdk

import br.com.bonifiq.sdk.auth.VtexSessionTokens
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(BonifiQInternalApi::class)
class PushApiTest {

    private fun body(request: Recorded) = Json.parseToJsonElement(request.body).jsonObject

    @Test
    fun appConfigReadsPushFields() = runTest {
        val fake = FakeBonifiQ {
            json(
                envelope(
                    """{"ClientURL":"https://widget.bonifiq.com.br","PushEnabled":true,"PushFirebaseProjectId":"loja-app",
                       "PushAndroidChannelId":"bonifiq_loyalty","PushAndroidChannelName":"Programa de fidelidade"}""",
                ),
            )
        }

        val config = fake.sdk.program.appConfig()

        assertTrue(config.pushEnabled)
        assertEquals("loja-app", config.pushFirebaseProjectId)
        assertEquals("bonifiq_loyalty", config.pushAndroidChannelId)
        assertEquals("Programa de fidelidade", config.pushAndroidChannelName)
    }

    @Test
    fun registerSendsSecureTokenAndDevice() = runTest {
        val fake = FakeBonifiQ { request ->
            if (request.path.endsWith("/securelogin")) json(secureLogin("tok")) else json(envelope("""{"Registered":true}"""))
        }
        fake.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }

        val registered = fake.sdk.push.registerDevice("fcm-1", "android", "com.loja.app", "2.1.0", locale = null)

        assertTrue(registered)
        val request = fake.requests.last()
        assertEquals("/pub/widget/push/devices", request.path)
        assertEquals("tok", request.token)
        assertEquals(TEST_TENANT, request.tenant)
        val sent = body(request)
        assertEquals("fcm-1", sent["Token"]!!.jsonPrimitive.content)
        assertEquals("android", sent["Platform"]!!.jsonPrimitive.content)
        assertEquals("com.loja.app", sent["AppId"]!!.jsonPrimitive.content)
        assertEquals("2.1.0", sent["AppVersion"]!!.jsonPrimitive.content)
        assertEquals(BonifiQ.VERSION, sent["SdkVersion"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, sent["Locale"])
    }

    @Test
    fun registerRequiresLogin() = runTest {
        val fake = FakeBonifiQ { json(envelope("""{"Registered":true}""")) }

        assertFailsWith<BonifiQException.Unauthorized> { fake.sdk.push.registerDevice("fcm-1", "ios", null, null, null) }
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun registerWithoutConfirmationIsFalse() = runTest {
        val fake = FakeBonifiQ { request ->
            if (request.path.endsWith("/securelogin")) json(secureLogin("tok")) else json(envelope("null"))
        }
        fake.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }

        assertFalse(fake.sdk.push.registerDevice("fcm-1", "ios", null, null, null))
    }

    @Test
    fun unregisterAndOpenedGoWithoutSecureToken() = runTest {
        val fake = FakeBonifiQ { request ->
            if (request.path.endsWith("/securelogin")) json(secureLogin("tok")) else json("")
        }
        fake.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }
        fake.requests.clear()

        fake.sdk.push.unregisterDevice("fcm-1")
        fake.sdk.push.reportOpened("cid-42")

        val (unregister, opened) = fake.requests
        assertEquals("/pub/widget/push/devices/unregister", unregister.path)
        assertEquals("fcm-1", body(unregister)["Token"]!!.jsonPrimitive.content)
        assertEquals("/pub/widget/push/opened", opened.path)
        assertEquals("cid-42", body(opened)["CommunicationId"]!!.jsonPrimitive.content)
        assertTrue(fake.requests.all { it.token == null && it.tenant == TEST_TENANT }, "só o tenant, mesmo logado")
    }
}
