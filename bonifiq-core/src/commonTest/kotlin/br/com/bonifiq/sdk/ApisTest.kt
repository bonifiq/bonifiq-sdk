package br.com.bonifiq.sdk

import br.com.bonifiq.sdk.api.CheckoutApi
import br.com.bonifiq.sdk.auth.VtexSessionTokens
import br.com.bonifiq.sdk.model.RewardType
import br.com.bonifiq.sdk.model.UseReason
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApisTest {

    private suspend fun loggedIn(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(Recorded) -> io.ktor.client.request.HttpResponseData): FakeBonifiQ {
        val fake = FakeBonifiQ { request ->
            if (request.path.endsWith("/securelogin")) json(secureLogin("tok")) else handler(request)
        }
        fake.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }
        fake.requests.clear()
        return fake
    }

    @Test
    fun rewardsListIsPublicButSendsTokenWhenLoggedIn() = runTest {
        val rewardsJson = envelope(
            """[{"Id":421,"Type":3,"Title":"Cashback","Points":1,"Enabled":true,"CanUse":false,"UseReason":0,"IsCashbackInformativeOnly":true},
               {"Id":6618,"Type":1,"Title":"R$ 12","Points":"240.0","Enabled":true,"CanUse":false,"UseReason":1,"MinValueToUse":50}]""",
        )
        val anonymous = FakeBonifiQ { json(rewardsJson) }
        val rewards = anonymous.sdk.rewards.list()
        assertNull(anonymous.requests.single().token)

        val logged = loggedIn { json(rewardsJson) }
        logged.sdk.rewards.list()
        assertEquals("tok", logged.requests.single().token)

        val (cashback, coupon) = rewards
        assertEquals(RewardType.PointToCashback, cashback.type)
        assertFalse(cashback.isRedeemable)
        assertEquals(RewardType.ValueDiscountCoupon, coupon.type)
        assertEquals(240, coupon.points, "pontos decimais/string são aceitos")
        assertEquals(UseReason.NotEnoughPoints, coupon.useReason)
        assertTrue(coupon.isRedeemable)
    }

    @Test
    fun redeemSendsCartAndRefreshesSummary() = runTest {
        val fake = loggedIn { request ->
            when {
                request.path.endsWith("/rewards/redeem/6618") -> json(
                    envelope("""{"Success":true,"ResultMessage":"Utilize seu cupom","CouponCode":"BQ-123","HasCouponCode":true,"Reward":{"RedeemedId":1416,"Title":"R$ 12","Points":240}}"""),
                )
                else -> json(envelope("""{"PointsBalance":1260,"PointsText":"1.260 Coins"}"""))
            }
        }

        val result = fake.sdk.rewards.redeem(6618, cartId = "of-123")

        assertEquals("BQ-123", result.couponCode)
        assertEquals(1416, result.reward?.redeemedId)
        val body = Json.parseToJsonElement(fake.requests[0].body).jsonObject
        assertEquals("of-123", body["OriginalId"]!!.jsonPrimitive.content)
        assertEquals("/pub/widget/customer/info", fake.requests[1].path, "saldo atualizado após o resgate")
        assertEquals(1260, fake.sdk.customer.summary.value?.pointsBalance)
        assertEquals("Coins", fake.sdk.customer.summary.value?.pointsName)
    }

    @Test
    fun unsuccessfulRedeemThrowsWithMessage() = runTest {
        val fake = loggedIn { json(envelope("""{"Success":false,"ResultMessage":"Pontos insuficientes"}""")) }

        val error = assertFailsWith<BonifiQException.Api> { fake.sdk.rewards.redeem(1) }
        assertEquals("Pontos insuficientes", error.message)
    }

    @Test
    fun applyCashbackUsesMobileOriginAndPlainResponse() = runTest {
        val fake = loggedIn { request ->
            when {
                request.path.endsWith("/cashback/redeem") ->
                    json("""{"AppliedCashback":25.0,"Success":true,"CustomerMessage":"Cashback resgatado com sucesso.","CouponCode":null}""")
                else -> json(envelope("""{"PointsBalance":10}"""))
            }
        }

        val applied = fake.sdk.checkout.applyCashback("of-123", value = 25.0, cartTotal = 150.0)

        assertTrue(applied.success)
        assertEquals(25.0, applied.applied)
        val body = Json.parseToJsonElement(fake.requests[0].body).jsonObject
        assertEquals("of-123", body["CheckoutId"]!!.jsonPrimitive.content)
        assertEquals(25.0, body["CashbackValue"]!!.jsonPrimitive.double)
        assertEquals(CheckoutApi.REDEEM_ORIGIN_MOBILE, body["RedeemOrigin"]!!.jsonPrimitive.int)
        assertEquals(150.0, body["Total"]!!.jsonPrimitive.double)
    }

    @Test
    fun cashbackOptionsAcceptCamelCaseFields() = runTest {
        val fake = loggedIn {
            json("""{"CashbackBalance":50.0,"UsableCashback":25.0,"CanUse":true,"CashbackRules":"Até 50%","hasProductsIneligibleForCashback":true,"MinValueToUse":20.0}""")
        }

        val options = fake.sdk.checkout.cashbackOptions("of-123", 150.0)

        assertEquals(50.0, options.balance)
        assertEquals(25.0, options.usable)
        assertTrue(options.canUse)
        assertTrue(options.hasIneligibleProducts)
        assertEquals("/pub/widget/rewards/checkout/cashback", fake.requests.single().path)
    }

    @Test
    fun appliedCashbackIsNullWhenNothingApplied() = runTest {
        val fake = loggedIn { json("""{"AppliedCashback":0,"Success":false}""") }
        assertNull(fake.sdk.checkout.appliedCashback("of-123"))
    }

    @Test
    fun historyAndExpiringPoints() = runTest {
        val fake = loggedIn { request ->
            when {
                request.path.endsWith("/customer/history") -> json(
                    envelope("""{"Items":[{"Id":1,"Title":"Compra #1","Points":150.0,"Date":"2026-10-01T10:00:00Z"},{"Id":2,"Title":"Resgate","Points":-100,"CouponCode":"X"}],"TotalPages":3,"TotalItems":21}"""),
                )
                else -> json(envelope("""[{"Id":1001,"Value":200,"Date":"2026-12-15"}]"""))
            }
        }

        val page = fake.sdk.customer.history(page = 2)
        val expiring = fake.sdk.customer.expiringPoints()

        assertEquals("2", fake.requests[0].param("page"))
        assertEquals(listOf(150L, -100L), page.items.map { it.points })
        assertEquals(3, page.totalPages)
        assertEquals(200, expiring.single().points)
    }

    @Test
    fun publicProgramInfo() = runTest {
        val fake = FakeBonifiQ { request ->
            when {
                request.path.endsWith("/terms") -> json(envelope("""{"Terms":"<h3>Regras</h3>"}"""))
                request.path.endsWith("/configuration") ->
                    json("""{"Text":"Resgate","ProgramName":"BonifiQ Club","Color":"#0088ff","Active":true,"UseCashback":true,"UsePoints":false}""")
                else -> json(envelope("""{"Enabled":true,"CashbackPointValue":0.05,"MinimalValue":2.0}"""))
            }
        }

        assertEquals("<h3>Regras</h3>", fake.sdk.program.terms())
        val config = fake.sdk.program.configuration()
        assertEquals("BonifiQ Club", config.programName)
        assertTrue(config.useCashback)
        assertEquals(0.05, fake.sdk.program.cashbackSettings().pointValue)
        assertTrue(fake.requests.all { it.tenant == TEST_TENANT && it.token == null })
    }

    @Test
    fun appConfigReadsWidgetUrlOnce() = runTest {
        val fake = FakeBonifiQ {
            json(envelope("""{"ClientURL":"https://widget.bonifiq.com.br","AppEmbeddedHubEnabled":true,"ProgramName":"BonifiQ Club","TenantUseCashback":"true","WidgetCustomization":{"general":{}}}"""))
        }

        val config = fake.sdk.program.appConfig()
        fake.sdk.program.appConfig()

        assertEquals("https://widget.bonifiq.com.br", config.widgetUrl)
        assertFalse(config.pushEnabled, "servidor sem os campos de push")
        assertNull(config.pushFirebaseProjectId)
        assertEquals("/pub/widget/app/config", fake.requests.single().path, "fica em memória")
        assertNull(fake.requests.single().token)
    }

    @Test
    fun httpErrorsAndNetworkFailuresAreMapped() = runTest {
        val serverError = FakeBonifiQ { json("""{"Message":"Falha interna"}""", HttpStatusCode.InternalServerError) }
        val api = assertFailsWith<BonifiQException.Api> { serverError.sdk.program.terms() }
        assertEquals("Falha interna", api.message)
        assertEquals(500, api.httpStatus)

        val offline = FakeBonifiQ { throw IOException("sem rede") }
        assertFailsWith<BonifiQException.Network> { offline.sdk.program.terms() }
    }

    @Test
    fun nonJsonSuccessBodyBecomesApiError() = runTest {
        // Ex.: um Wi-Fi com página de login responde 200 com HTML no lugar da BonifiQ.
        val captivePortal = FakeBonifiQ { json("<html><body>Entre na rede</body></html>") }

        assertFailsWith<BonifiQException.Api> { captivePortal.sdk.program.terms() }
        assertFailsWith<BonifiQException.Api> { captivePortal.sdk.auth.requestEmailCode("a@b.com") }
    }
}
