package br.com.bonifiq.sdk.ui

import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.BonifiQConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.min

/** Simula a API Widget com estado (saldo cai no resgate, cashback aplica/remove) para testes de UI. */
class FakeBonifiQServer(var balance: Long = 1500, existingCoupons: List<String> = emptyList()) {

    private data class FakeReward(val id: Long, val type: Int, val title: String, val points: Long, val description: String)

    private val rewards = listOf(
        FakeReward(421, 3, "Cashback", 1, "Cada Coin vale R$ 0,05 de cashback direto no carrinho."),
        FakeReward(6618, 1, "R$ 12,00", 240, "Cupom de R$ 12,00 para compras acima de R$ 50,00."),
        FakeReward(5078, 0, "10%", 500, "Cupom de 10% de desconto."),
        FakeReward(5079, 1, "R$ 50,00", 2000, "Cupom de R$ 50,00."),
    )
    private val redeemed = existingCoupons.toMutableList()
    var appliedCashback = 0.0
    /** Resposta de app/config; null = endpoint ainda não publicado (404). */
    var appConfig: String? = null
    var logins = 0
    var redeemCount = 0

    val engine = MockEngine { request ->
        val path = request.url.encodedPath.removePrefix("/pub/widget/")
        val body = request.body.toByteArray().decodeToString()
        val json = when (path) {
            "vendors/vtex/securelogin" -> env("""{"Email":"cliente@teste.com","Name":"Cliente Teste","UserId":"user-1","SecureToken":"tok-${++logins}"}""")
            "app/config" -> appConfig?.let { env(it) }
                ?: return@MockEngine respond("""{"Message":"Not Found"}""", HttpStatusCode.NotFound)
            "customer/info" -> env("""{"PointsBalance":$balance,"PointsText":"${fmt(balance)} Coins","Name":"Cliente Teste"}""")
            "customer/expiringpoints" -> env("""[{"Id":1,"Value":200,"Date":"2026-12-15"}]""")
            "customer/history" -> env(
                """{"Items":[
                    {"Id":3,"Title":"Resgate de recompensa","Points":-240,"Date":"2026-10-05T12:00:00Z","CouponCode":"BQ-OLD"},
                    {"Id":2,"Title":"Compra #1667130502271","Points":120,"Date":"2026-10-04T12:00:00Z"},
                    {"Id":1,"Title":"Cadastro no programa","Points":100,"Date":"2026-09-01T12:00:00Z"}],
                   "TotalPages":1,"TotalItems":3}""",
            )
            "rewards" -> env(rewards.joinToString(",", "[", "]") { r ->
                val canUse = r.type != 3 && balance >= r.points
                """{"Id":${r.id},"Type":${r.type},"Title":"${r.title}","Points":${r.points},"Description":"${r.description}",
                    "Enabled":true,"CanUse":$canUse,"UseReason":${if (canUse || r.type == 3) 0 else 1},
                    "IsCashbackInformativeOnly":${r.type == 3},"CashbackInformativeText":${if (r.type == 3) "\"Use seu cashback ao finalizar a compra\"" else "null"}}"""
            })
            "rewards/redeemed" -> env(redeemed.joinToString(",", "[", "]"))
            "terms" -> env("""{"Terms":"<h3><strong>Como ganhar pontos?</strong></h3><p>A cada R&#36; 1,00 em compras voc&ecirc; ganha 1 Coin.</p><ul><li>Compras aprovadas</li><li>Cadastro</li></ul>"}""")
            "rewards/checkout/configuration" -> """{"ProgramName":"BonifiQ Club","Color":"#0088ff","Active":true,"UseCashback":true,"UsePoints":false}"""
            "rewards/cashback" -> env("""{"Enabled":true,"CashbackPointValue":0.05,"MinimalValue":2.0}""")
            "rewards/checkout/cashback" -> {
                val total = Json.parseToJsonElement(body).jsonObject["Total"]?.jsonPrimitive?.double ?: 0.0
                // Como na API: o cashback aplicado sai da carteira e volta quando é removido.
                val cashback = balance * 0.05 - appliedCashback
                """{"CashbackBalance":$cashback,"UsableCashback":${min(cashback, total / 2).coerceAtLeast(0.0)},"CanUse":true,
                    "CashbackRules":"Pague até 50% da compra com cashback.","CashbackWillReceiveText":"Você receberá R$ 6,00 de cashback nesta compra"}"""
            }
            "rewards/checkout/cashback/redeemed" -> """{"AppliedCashback":$appliedCashback,"Success":${appliedCashback > 0}}"""
            "rewards/checkout/cashback/redeem" -> {
                appliedCashback = Json.parseToJsonElement(body).jsonObject["CashbackValue"]!!.jsonPrimitive.double
                """{"AppliedCashback":$appliedCashback,"Success":true,"CustomerMessage":"Cashback resgatado com sucesso."}"""
            }
            "rewards/checkout/cashback/redeem/remove" -> {
                appliedCashback = 0.0
                """{"Success":true,"CustomerMessage":"O cashback foi removido com sucesso"}"""
            }
            else -> if (path.startsWith("rewards/redeem/")) {
                val reward = rewards.first { it.id == path.substringAfterLast('/').toLong() }
                balance -= reward.points
                val code = "BQ-${reward.id}-${++redeemCount}"
                redeemed += """{"RedeemedId":${1400 + redeemCount},"Id":${reward.id},"Type":${reward.type},"Title":"${reward.title}","Points":${reward.points},"CouponCode":"$code"}"""
                env("""{"Success":true,"ResultMessage":"Utilize seu cupom nesta compra:","CouponCode":"$code","Reward":{"RedeemedId":${1400 + redeemCount},"Title":"${reward.title}","Points":${reward.points}}}""")
            } else {
                return@MockEngine respond("""{"Message":"rota desconhecida: $path"}""", HttpStatusCode.NotFound)
            }
        }
        respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    val sdk = BonifiQ(BonifiQConfig(tenant = "tenant-teste"), engine)

    private fun env(item: String) = """{"HasError":false,"Message":null,"Item":$item}"""

    private fun fmt(value: Long) = value.toString().reversed().chunked(3).joinToString(".").reversed()
}
