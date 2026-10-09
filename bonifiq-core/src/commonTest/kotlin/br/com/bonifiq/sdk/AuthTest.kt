package br.com.bonifiq.sdk

import br.com.bonifiq.sdk.auth.AuthState
import br.com.bonifiq.sdk.auth.VtexSessionTokens
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthTest {

    private val customerInfo = envelope("""{"PointsBalance":1500,"PointsText":"1.500 Coins","Name":"Cliente Teste"}""")

    @Test
    fun vtexLoginSendsSessionTokensAndAuthenticatesCalls() = runTest {
        val fake = FakeBonifiQ { request ->
            when (request.path) {
                "/pub/widget/vendors/vtex/securelogin" -> json(secureLogin("token-1"))
                else -> json(customerInfo)
            }
        }

        val identity = fake.sdk.auth.loginWithVtex { VtexSessionTokens("session-abc", "segment-xyz") }
        fake.sdk.customer.info()

        assertEquals("cliente@teste.com", identity.email)
        assertIs<AuthState.Authenticated>(fake.sdk.auth.state.value)
        val (login, info) = fake.requests
        assertEquals("POST", login.method)
        val body = Json.parseToJsonElement(login.body).jsonObject
        assertEquals("session-abc", body["sessionToken"]!!.jsonPrimitive.content)
        assertEquals("segment-xyz", body["segmentToken"]!!.jsonPrimitive.content)
        assertNull(login.token, "o login não envia SecureToken")
        assertEquals(TEST_TENANT, info.tenant)
        assertEquals("token-1", info.token)
    }

    @Test
    fun renewsOnUnauthorizedAndRetriesOnce() = runTest {
        var logins = 0
        val fake = FakeBonifiQ { request ->
            when {
                request.path.endsWith("/securelogin") -> json(secureLogin("token-${++logins}"))
                request.token == "token-1" -> json("""{"Message":"Token expirado"}""", HttpStatusCode.Unauthorized)
                else -> json(customerInfo)
            }
        }
        fake.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }

        val info = fake.sdk.customer.info()

        assertEquals(1500, info.pointsBalance)
        assertEquals(2, logins, "renovou uma vez")
        assertEquals(listOf(null, "token-1", null, "token-2"), fake.requests.map { it.token })
    }

    @Test
    fun renewsProactivelyAfter55Minutes() = runTest {
        var logins = 0
        val fake = FakeBonifiQ { request ->
            if (request.path.endsWith("/securelogin")) json(secureLogin("token-${++logins}")) else json(customerInfo)
        }
        fake.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }

        fake.advanceMinutes(30)
        fake.sdk.customer.info()
        fake.advanceMinutes(26)
        fake.sdk.customer.info()

        assertEquals(2, logins)
        assertEquals(listOf("token-1", "token-2"), fake.requests.filterNot { it.path.endsWith("/securelogin") }.map { it.token })
    }

    @Test
    fun emailLoginExpiresWithoutRenewal() = runTest {
        val fake = FakeBonifiQ { request ->
            when {
                request.path.endsWith("/account/challenge") -> json(envelope("null"))
                request.path.endsWith("/account/validatechallenge") -> json(secureLogin("email-token"))
                else -> json(customerInfo)
            }
        }

        fake.sdk.auth.requestEmailCode(" cliente@teste.com ")
        fake.sdk.auth.loginWithEmailCode("cliente@teste.com", "482910")
        fake.advanceMinutes(58)
        fake.sdk.customer.info() // ainda dentro dos 60 min
        fake.advanceMinutes(3)
        assertFailsWith<BonifiQException.Unauthorized> { fake.sdk.customer.info() }

        assertEquals(AuthState.Expired, fake.sdk.auth.state.value)
        val challenge = Json.parseToJsonElement(fake.requests[0].body).jsonObject
        assertEquals("cliente@teste.com", challenge["email"]!!.jsonPrimitive.content)
        val validate = Json.parseToJsonElement(fake.requests[1].body).jsonObject
        assertEquals("482910", validate["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun emailCodeLoginKeepsTypedEmailWhenApiOmitsIt() = runTest {
        // Resposta real do validatechallenge: sem e-mail, nome nem UserId.
        val fake = FakeBonifiQ { json(envelope("""{"HasBirthday":false,"PublicId":"pid-1","SecureToken":"email-token"}""")) }

        val identity = fake.sdk.auth.loginWithEmailCode(" cliente@teste.com ", "482910")

        assertEquals("cliente@teste.com", identity.email)
        assertEquals("cliente@teste.com", (fake.sdk.auth.state.value as AuthState.Authenticated).customer.email)
    }

    @Test
    fun invalidCodeSurfacesBonifiQMessage() = runTest {
        val fake = FakeBonifiQ { json(envelope("null", hasError = true, message = "Código de verificação inválido")) }

        val error = assertFailsWith<BonifiQException.Api> { fake.sdk.auth.loginWithEmailCode("a@b.com", "000000") }

        assertEquals("Código de verificação inválido", error.message)
        assertEquals(AuthState.Anonymous, fake.sdk.auth.state.value)
    }

    @Test
    fun authenticatedCallWithoutSessionFailsWithoutNetwork() = runTest {
        val fake = FakeBonifiQ { error("não deveria chamar a API") }

        assertFailsWith<BonifiQException.Unauthorized> { fake.sdk.customer.info() }
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun logoutClearsSessionAndSummary() = runTest {
        val fake = FakeBonifiQ { request ->
            if (request.path.endsWith("/securelogin")) json(secureLogin("t")) else json(customerInfo)
        }
        fake.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }
        fake.sdk.customer.info()
        assertEquals(1500, fake.sdk.customer.summary.value?.pointsBalance)

        fake.sdk.auth.logout()

        assertEquals(AuthState.Anonymous, fake.sdk.auth.state.value)
        assertNull(fake.sdk.customer.summary.value)
    }

    @OptIn(BonifiQInternalApi::class)
    @Test
    fun embedTokenRenewsOnDemandWithVtexButExpiresWithEmailLogin() = runTest {
        var logins = 0
        val fake = FakeBonifiQ { request ->
            when {
                request.path.endsWith("/securelogin") -> json(secureLogin("vtex-${++logins}"))
                request.path.endsWith("/validatechallenge") -> json(secureLogin("email-token"))
                else -> json(customerInfo)
            }
        }

        fake.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }
        assertEquals("vtex-1", fake.sdk.auth.secureTokenForEmbed())
        assertEquals("vtex-2", fake.sdk.auth.renewSecureTokenForEmbed(), "o widget recusou o token: renova pela sessão VTEX")

        fake.sdk.auth.loginWithEmailCode("a@b.com", "123456")
        assertNull(fake.sdk.auth.renewSecureTokenForEmbed(), "login por código não renova sozinho")
        assertEquals(AuthState.Expired, fake.sdk.auth.state.value)
    }

    @Test
    fun failedRenewalExpiresSession() = runTest {
        var logins = 0
        val fake = FakeBonifiQ { request ->
            when {
                request.path.endsWith("/securelogin") && ++logins > 1 ->
                    json(envelope("null", hasError = true, message = "Sessão inválida ou expirada"))
                request.path.endsWith("/securelogin") -> json(secureLogin("t1"))
                else -> json(customerInfo)
            }
        }
        fake.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }
        fake.advanceMinutes(56)

        assertFailsWith<BonifiQException.Api> { fake.sdk.customer.info() }
        assertEquals(AuthState.Expired, fake.sdk.auth.state.value)
    }

    @Test
    fun vtexProviderFailureBecomesNetworkError() = runTest {
        val fake = FakeBonifiQ { json(secureLogin("t1")) }
        val failure = IllegalStateException("VTEX fora do ar")

        val error = assertFailsWith<BonifiQException.Network> { fake.sdk.auth.loginWithVtex { throw failure } }

        assertEquals(failure, error.cause)
        assertEquals(AuthState.Anonymous, fake.sdk.auth.state.value)
        assertTrue(fake.requests.isEmpty(), "não chama a BonifiQ sem os tokens da VTEX")
    }
}
