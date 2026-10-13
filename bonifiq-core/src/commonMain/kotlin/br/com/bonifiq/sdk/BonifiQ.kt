package br.com.bonifiq.sdk

import br.com.bonifiq.sdk.api.CheckoutApi
import br.com.bonifiq.sdk.api.CustomerApi
import br.com.bonifiq.sdk.api.ProgramApi
import br.com.bonifiq.sdk.api.RewardsApi
import br.com.bonifiq.sdk.auth.BonifiQAuth
import br.com.bonifiq.sdk.auth.SecureTokenManager
import br.com.bonifiq.sdk.internal.WidgetHttp
import io.ktor.client.engine.HttpClientEngine
import io.ktor.util.date.getTimeMillis

/**
 * Ponto de entrada do SDK BonifiQ. Crie **uma instância por app** e compartilhe.
 *
 * ```
 * val bonifiq = BonifiQ(BonifiQConfig(tenant = "SEU_TENANT"))
 * bonifiq.auth.loginWithVtex { VtexSessionTokens(sessionToken, segmentToken) }
 * val cliente = bonifiq.customer.info()
 * ```
 *
 * @param httpEngine engine HTTP opcional (testes ou proxy); por padrão usa OkHttp no Android/JVM e Darwin no iOS.
 */
public class BonifiQ internal constructor(
    public val config: BonifiQConfig,
    httpEngine: HttpClientEngine?,
    clock: () -> Long,
) {
    public constructor(config: BonifiQConfig, httpEngine: HttpClientEngine? = null) :
        this(config, httpEngine, { getTimeMillis() })

    private val tokens = SecureTokenManager(clock)
    private val http = WidgetHttp(config, httpEngine, tokens)

    public val customer: CustomerApi = CustomerApi(http)
    public val auth: BonifiQAuth = BonifiQAuth(http, tokens, onSessionCleared = customer::clear)
    public val rewards: RewardsApi = RewardsApi(http, customer)
    public val checkout: CheckoutApi = CheckoutApi(http, customer)
    public val program: ProgramApi = ProgramApi(http)

    /** Libera o cliente HTTP. Depois disso a instância não pode mais ser usada. */
    public fun close() {
        http.close()
    }

    public companion object {
        public const val VERSION: String = "0.4.0"
    }
}
