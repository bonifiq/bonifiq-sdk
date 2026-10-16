package br.com.bonifiq.sdk.push

import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.BonifiQException
import br.com.bonifiq.sdk.auth.AuthState
import br.com.bonifiq.sdk.auth.VtexSessionTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BonifiQPushTest {

    private val created = mutableListOf<BonifiQPush>()

    @BeforeTest
    fun reset() {
        PushRuntime.reset()
    }

    @AfterTest
    fun closeAll() {
        created.forEach { it.close() }
        PushRuntime.reset()
    }

    private fun push(server: FakePushServer, platform: PushPlatform = FakePushPlatform()): BonifiQPush =
        BonifiQPush(server.sdk, platform, CoroutineScope(SupervisorJob() + Dispatchers.Default)).also { created += it }

    private suspend fun BonifiQ.loginVtex() {
        auth.loginWithVtex { VtexSessionTokens("s", "g") }
    }

    @Test
    fun registersOnLoginWithSecureToken() = runBlocking {
        val server = FakePushServer()
        BonifiQPush.setToken("fcm-1")
        val platform = FakePushPlatform()
        val push = push(server, platform)

        push.awaitIdle()
        assertTrue(server.calls("push/devices").isEmpty(), "sem cliente logado não registra")

        server.sdk.loginVtex()
        val register = server.await("push/devices").single()

        assertEquals("tok-1", register.secureToken)
        assertEquals("tenant-teste", register.tenant)
        assertEquals("fcm-1", register.field("Token"))
        assertEquals("android", register.field("Platform"))
        assertEquals("com.loja.app", register.field("AppId"))
        assertEquals("3.2.1", register.field("AppVersion"))
        assertEquals(BonifiQ.VERSION, register.field("SdkVersion"))
        assertEquals("pt-BR", register.field("Locale"))
        assertEquals(1, platform.started)
        assertEquals("bonifiq_loyalty", platform.pushConfigs.single().pushAndroidChannelId, "canal criado uma vez")
        assertEquals(1, server.calls("app/config").size, "a config da loja fica em memória")
    }

    @Test
    fun tokenArrivingAfterLoginRegisters() = runBlocking {
        val server = FakePushServer()
        val push = push(server)
        server.sdk.loginVtex()
        push.awaitIdle()
        assertTrue(server.calls("push/devices").isEmpty(), "ainda sem token")

        BonifiQPush.setToken("  ")
        BonifiQPush.setToken(null)
        BonifiQPush.setToken("fcm-1")

        assertEquals("fcm-1", server.await("push/devices").single().field("Token"))
    }

    @Test
    fun unregistersOnLogoutWithoutSecureToken() = runBlocking {
        val server = FakePushServer()
        BonifiQPush.setToken("fcm-1")
        val push = push(server)
        server.sdk.loginVtex()
        server.await("push/devices")

        server.sdk.auth.logout()
        val unregister = server.await("push/devices/unregister").single()

        assertNull(unregister.secureToken, "no logout o SecureToken pode já ter expirado")
        assertEquals("tenant-teste", unregister.tenant)
        assertEquals("fcm-1", unregister.field("Token"))

        // Depois do logout, um novo login registra o mesmo token de novo.
        push.awaitIdle()
        server.sdk.loginVtex()
        assertEquals("tok-2", server.await("push/devices", count = 2).last().secureToken)
        server.sdk.auth.logout()
        server.await("push/devices/unregister", count = 2)
        push.awaitIdle()
        assertEquals(2, server.calls("push/devices/unregister").size)
    }

    @Test
    fun doesNotRegisterWhenPushIsDisabled() = runBlocking {
        val server = FakePushServer(appConfig = FakePushServer.PUSH_DISABLED)
        BonifiQPush.setToken("fcm-1")
        val platform = FakePushPlatform()
        val push = push(server, platform)

        server.sdk.loginVtex()
        push.awaitIdle()
        server.sdk.auth.logout()
        push.awaitIdle()

        assertEquals(1, server.calls("app/config").size)
        assertTrue(server.calls("push/devices").isEmpty())
        assertTrue(server.calls("push/devices/unregister").isEmpty(), "nada registrado, nada a remover")
        assertTrue(platform.pushConfigs.isEmpty(), "sem canal de notificação com o push desligado")
    }

    @Test
    fun olderServerWithoutPushFieldsDoesNotRegister() = runBlocking {
        val server = FakePushServer(appConfig = """{"ClientURL":"https://widget.bonifiq.com.br"}""")
        BonifiQPush.setToken("fcm-1")
        val push = push(server)

        server.sdk.loginVtex()
        push.awaitIdle()

        assertTrue(server.calls("push/devices").isEmpty())
    }

    @Test
    fun tokenChangeRegistersAgain() = runBlocking {
        val server = FakePushServer()
        BonifiQPush.setToken("fcm-1")
        val push = push(server)
        server.sdk.loginVtex()
        server.await("push/devices")

        BonifiQPush.setToken("fcm-2")
        val registers = server.await("push/devices", count = 2)
        BonifiQPush.setToken("fcm-2")
        push.awaitIdle()

        assertEquals(listOf("fcm-1", "fcm-2"), registers.map { it.field("Token") })
        assertEquals(2, server.calls("push/devices").size, "o mesmo token não registra de novo")
        assertTrue(registers.all { it.secureToken == "tok-1" })
    }

    @Test
    fun expiredSessionKeepsTheDeviceAndSameCustomerIsNotRegisteredAgain() = runBlocking {
        val server = FakePushServer()
        BonifiQPush.setToken("fcm-1")
        val push = push(server)
        server.sdk.auth.loginWithEmailCode("cliente@teste.com", "123456")
        server.await("push/devices")

        // O login por código não renova: um 401 expira a sessão.
        server.rejectToken = true
        assertFailsWith<BonifiQException.Unauthorized> { server.sdk.customer.info() }
        assertEquals(AuthState.Expired, server.sdk.auth.state.value)
        push.awaitIdle()
        assertTrue(server.calls("push/devices/unregister").isEmpty(), "sessão expirada não é logout")

        server.sdk.auth.loginWithEmailCode("cliente@teste.com", "654321")
        push.awaitIdle()
        assertEquals(1, server.calls("push/devices").size, "mesmo token e mesmo cliente: não registra de novo")
    }

    @Test
    fun openedIsReportedWithCommunicationIdAndEmitted() = runBlocking {
        val server = FakePushServer()
        val push = push(server)
        BonifiQPush.setToken("fcm-1")
        server.sdk.loginVtex()

        val handled = BonifiQPush.handleNotification(
            mapOf<Any?, Any?>(
                "bq" to "1",
                "bq_cid" to "cid-42",
                "bq_type" to "PointsEarned",
                "bq_target" to "hub",
                "bq_test" to "1",
                "aps" to mapOf("alert" to "Você ganhou pontos"),
            ),
        )

        assertTrue(handled)
        assertEquals(PushOpen(type = "PointsEarned", target = "hub", isTest = true), withTimeout(5_000) { push.opens.first() })
        val opened = server.await("push/opened").single()
        assertEquals("cid-42", opened.field("CommunicationId"))
        assertNull(opened.secureToken, "a abertura vai só com o tenant")
        assertEquals("tenant-teste", opened.tenant)
    }

    @Test
    fun messagesWithoutBqAreIgnored() = runBlocking {
        val server = FakePushServer()
        val push = push(server)

        assertFalse(BonifiQPush.handleNotification(mapOf<Any?, Any?>("title" to "Promoção da loja", "deeplink" to "app://ofertas")))
        assertFalse(BonifiQPush.handleNotification(mapOf<Any?, Any?>("bq" to "0", "bq_cid" to "cid-x")))
        assertFalse(BonifiQPush.handleNotification(emptyMap<Any?, Any?>()))
        assertFalse(isBonifiQMessage(mapOf("google.message_id" to "0:123", "promo" to "1")))
        assertTrue(isBonifiQMessage(mapOf("bq" to "1")))

        // Uma mensagem da BonifiQ depois (como os dados do RemoteMessage no Android, Map<String, String>): só ela
        // chega, e sem bq_cid não há abertura a registrar.
        assertTrue(BonifiQPush.handleNotification(mapOf("bq" to "1", "bq_type" to "RewardAvailable")))
        assertEquals(PushOpen("RewardAvailable", target = null, isTest = false), withTimeout(5_000) { push.opens.first() })
        push.awaitIdle()
        assertTrue(server.calls("push/opened").isEmpty())
    }

    @Test
    fun openBeforeTheInstanceExistsIsDeliveredWhenItIsCreated() = runBlocking {
        // O app foi aberto pela notificação e repassou o toque antes de criar o BonifiQPush.
        assertTrue(BonifiQPush.handleNotification(mapOf<Any?, Any?>("bq" to "1", "bq_cid" to "cid-7", "bq_type" to "TierUp")))

        val server = FakePushServer()
        val push = push(server)

        assertEquals("TierUp", withTimeout(5_000) { push.opens.first() }.type)
        assertEquals("cid-7", server.await("push/opened").single().field("CommunicationId"))
    }

    @Test
    fun desktopNeverRegisters() = runBlocking {
        val server = FakePushServer()
        BonifiQPush.setToken("fcm-1")
        val push = push(server, UnsupportedPushPlatform)

        server.sdk.loginVtex()
        push.awaitIdle()

        assertTrue(server.calls("push/devices").isEmpty())
        assertEquals(PushPermissionStatus.Denied, push.permissionStatus())
        assertEquals(PushPermissionStatus.Denied, push.requestPermission())
        assertFalse(push.openNotificationSettings())
    }

    @Test
    fun permissionGoesThroughThePlatform() = runBlocking {
        val platform = FakePushPlatform()
        val push = push(FakePushServer(), platform)

        assertEquals(PushPermissionStatus.NotDetermined, push.permissionStatus())
        assertEquals(PushPermissionStatus.Granted, push.requestPermission())
        assertEquals(1, platform.requests)
    }

    @Test
    fun platformFailureBecomesDenied() = runBlocking {
        val failing = object : PushPlatform by FakePushPlatform() {
            override suspend fun permissionStatus(): PushPermissionStatus = error("sem contexto")
        }
        val push = push(FakePushServer(), failing)

        assertEquals(PushPermissionStatus.Denied, push.permissionStatus())
    }

    @Test
    fun firebaseProjectMismatchIsReported() {
        assertNull(firebaseProjectWarning("loja-app", "loja-app"))
        assertNull(firebaseProjectWarning(null, "loja-app"), "sem Firebase no app: o aviso é outro")
        assertNull(firebaseProjectWarning("loja-app", null), "push desligado")
        val warning = firebaseProjectWarning("outro-projeto", "loja-app")!!
        assertTrue("outro-projeto" in warning && "loja-app" in warning)
    }
}
