package br.com.bonifiq.sdk.api

import br.com.bonifiq.sdk.BonifiQException
import br.com.bonifiq.sdk.internal.AuthMode
import br.com.bonifiq.sdk.internal.WidgetHttp
import br.com.bonifiq.sdk.internal.jsonBody
import br.com.bonifiq.sdk.model.AppConfig
import br.com.bonifiq.sdk.model.CashbackApplication
import br.com.bonifiq.sdk.model.CashbackOptions
import br.com.bonifiq.sdk.model.CashbackRefresh
import br.com.bonifiq.sdk.model.CashbackRemoval
import br.com.bonifiq.sdk.model.CashbackSettings
import br.com.bonifiq.sdk.model.CustomerInfo
import br.com.bonifiq.sdk.model.ExpiringPoints
import br.com.bonifiq.sdk.model.PointsBalanceResponse
import br.com.bonifiq.sdk.model.PointsHistoryPage
import br.com.bonifiq.sdk.model.ProgramConfiguration
import br.com.bonifiq.sdk.model.RedeemResult
import br.com.bonifiq.sdk.model.RedeemedReward
import br.com.bonifiq.sdk.model.Reward
import br.com.bonifiq.sdk.model.TermsResponse
import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Dados do cliente logado. Todos os métodos exigem sessão ([br.com.bonifiq.sdk.auth.BonifiQAuth]). */
public class CustomerApi internal constructor(private val http: WidgetHttp) {

    private val _summary = MutableStateFlow<CustomerInfo?>(null)

    /**
     * Último [CustomerInfo] conhecido. Atualizado por [info] e automaticamente depois de resgates e de
     * uso/remoção de cashback; volta a `null` no logout. Ideal para chips de saldo.
     */
    public val summary: StateFlow<CustomerInfo?> = _summary.asStateFlow()

    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun info(): CustomerInfo =
        http.send<CustomerInfo>(HttpMethod.Get, "customer/info", AuthMode.Required).also { _summary.value = it }

    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun pointsBalance(): Long =
        http.send<PointsBalanceResponse>(HttpMethod.Get, "customer/points", AuthMode.Required).pointsBalance

    /** Extrato paginado (primeira página = 1). */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun history(page: Int = 1): PointsHistoryPage =
        http.send<PointsHistoryPage?>(HttpMethod.Get, "customer/history", AuthMode.Required) {
            parameter("page", page)
        } ?: PointsHistoryPage()

    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun expiringPoints(): List<ExpiringPoints> =
        http.send<List<ExpiringPoints>?>(HttpMethod.Get, "customer/expiringpoints", AuthMode.Required).orEmpty()

    internal suspend fun refreshSummary() {
        try {
            info()
        } catch (e: CancellationException) {
            throw e
        } catch (_: BonifiQException) {
            // Melhor esforço: o resumo é só um cache.
        }
    }

    internal fun clear() {
        _summary.value = null
    }
}

/** Recompensas: listar, resgatar com pontos e consultar cupons já resgatados. */
public class RewardsApi internal constructor(
    private val http: WidgetHttp,
    private val customer: CustomerApi,
) {
    /** Recompensas do programa. Com cliente logado, inclui a elegibilidade dele ([Reward.canUse]). */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun list(): List<Reward> =
        http.send<List<Reward>?>(HttpMethod.Get, "rewards", AuthMode.Optional).orEmpty()

    /**
     * Resgata a recompensa debitando os pontos. Informe [cartId] (ex.: `orderFormId` da VTEX) para associar o
     * resgate ao carrinho. O cupom gerado vem em [RedeemResult.couponCode] — o app aplica no carrinho.
     */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun redeem(rewardId: Long, cartId: String? = null): RedeemResult {
        val result = http.send<RedeemResult>(HttpMethod.Post, "rewards/redeem/$rewardId", AuthMode.Required) {
            jsonBody(buildJsonObject { if (cartId != null) put("OriginalId", cartId) })
        }
        if (!result.success) {
            throw BonifiQException.Api(result.resultMessage ?: "Não foi possível resgatar a recompensa.", 200)
        }
        customer.refreshSummary()
        return result
    }

    /** Cupons já resgatados e ainda não usados. */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun redeemed(): List<RedeemedReward> =
        http.send<List<RedeemedReward>?>(HttpMethod.Get, "rewards/redeemed", AuthMode.Required).orEmpty()
}

/**
 * Cashback no checkout. [cartId] é o identificador do carrinho na plataforma (na VTEX, o `orderFormId`):
 * a BonifiQ lê o carrinho direto da plataforma. Depois de aplicar/remover, recarregue o carrinho no app.
 */
public class CheckoutApi internal constructor(
    private val http: WidgetHttp,
    private val customer: CustomerApi,
) {
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun cashbackOptions(cartId: String, cartTotal: Double? = null): CashbackOptions =
        http.send<CashbackOptions>(HttpMethod.Post, "rewards/checkout/cashback", AuthMode.Required) {
            jsonBody(buildJsonObject {
                put("CheckoutId", cartId)
                if (cartTotal != null) put("Total", cartTotal)
            })
        }

    /** Aplica [value] reais de cashback ao carrinho (origem "checkout mobile"). */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun applyCashback(cartId: String, value: Double, cartTotal: Double? = null): CashbackApplication =
        http.send<CashbackApplication>(HttpMethod.Post, "rewards/checkout/cashback/redeem", AuthMode.Required) {
            jsonBody(buildJsonObject {
                put("CheckoutId", cartId)
                put("CashbackValue", value)
                put("RedeemOrigin", REDEEM_ORIGIN_MOBILE)
                if (cartTotal != null) put("Total", cartTotal)
            })
        }.also { if (it.success) customer.refreshSummary() }

    /** Cashback já aplicado ao carrinho, ou `null` se não houver. */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun appliedCashback(cartId: String): CashbackApplication? =
        http.send<CashbackApplication?>(HttpMethod.Post, "rewards/checkout/cashback/redeemed", AuthMode.Required) {
            jsonBody(buildJsonObject { put("CheckoutId", cartId) })
        }?.takeIf { it.applied > 0 }

    /** Revalida o cashback aplicado. Chame sempre que o carrinho mudar (itens, valores). */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun refreshCashback(cartId: String): CashbackRefresh =
        http.send<CashbackRefresh>(HttpMethod.Post, "rewards/checkout/cashback/refresh", AuthMode.Required) {
            jsonBody(buildJsonObject { put("CheckoutId", cartId) })
        }

    /** Remove o cashback do carrinho e devolve o valor à carteira do cliente. */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun removeCashback(cartId: String, cartTotal: Double? = null): CashbackRemoval =
        http.send<CashbackRemoval>(HttpMethod.Post, "rewards/checkout/cashback/redeem/remove", AuthMode.Required) {
            jsonBody(buildJsonObject {
                put("CheckoutId", cartId)
                if (cartTotal != null) put("Total", cartTotal)
            })
        }.also { if (it.success) customer.refreshSummary() }

    public companion object {
        /** `RedeemOrigin` que identifica resgates feitos pelo checkout de apps. */
        public const val REDEEM_ORIGIN_MOBILE: Int = 3
    }
}

/** Informações públicas do programa de fidelidade. */
public class ProgramApi internal constructor(private val http: WidgetHttp) {

    private var cachedConfiguration: ProgramConfiguration? = null

    /** Nome, cor e mecânicas ativas (pontos/cashback). Fica em memória; use [forceRefresh] para buscar de novo. */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun configuration(forceRefresh: Boolean = false): ProgramConfiguration {
        cachedConfiguration?.takeUnless { forceRefresh }?.let { return it }
        return http.send<ProgramConfiguration>(HttpMethod.Get, "rewards/checkout/configuration", AuthMode.Optional)
            .also { cachedConfiguration = it }
    }

    /** Regulamento do programa em HTML. */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun terms(): String =
        http.send<TermsResponse?>(HttpMethod.Get, "terms", AuthMode.None)?.terms.orEmpty()

    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun cashbackSettings(): CashbackSettings =
        http.send<CashbackSettings?>(HttpMethod.Get, "rewards/cashback", AuthMode.Optional) ?: CashbackSettings()

    private var cachedAppConfig: AppConfig? = null

    /** Config da loja para apps (URL do widget e o push do programa). Fica em memória. */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun appConfig(forceRefresh: Boolean = false): AppConfig {
        cachedAppConfig?.takeUnless { forceRefresh }?.let { return it }
        return http.send<AppConfig>(HttpMethod.Get, "app/config", AuthMode.None).also { cachedAppConfig = it }
    }
}
