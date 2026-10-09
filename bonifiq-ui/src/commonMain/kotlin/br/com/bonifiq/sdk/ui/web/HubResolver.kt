package br.com.bonifiq.sdk.ui.web

import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.BonifiQConfig
import br.com.bonifiq.sdk.ui.internal.attempt
import io.ktor.http.encodeURLParameter

/** O que o `BonifiQLoyalty` mostra. */
internal sealed interface HubChoice {
    data object Resolving : HubChoice
    data class Web(val url: String) : HubChoice

    /**
     * O hub não pode abrir. [retryable]: a config da loja ou o widget falharam (rede, API fora do ar) e vale tentar
     * de novo; `false` quando a plataforma não tem WebView com ponte segura (desktop, Android com WebView antigo).
     */
    data class Unavailable(val retryable: Boolean) : HubChoice
}

/**
 * O hub é o widget web da BonifiQ num WebView. A URL vem de [BonifiQConfig.widgetUrl] ou, por padrão, da config da
 * loja (`ClientURL` em `/pub/widget/app/config`).
 */
internal suspend fun resolveHub(bonifiq: BonifiQ, platformSupportsWeb: Boolean): HubChoice {
    if (!platformSupportsWeb) return HubChoice.Unavailable(retryable = false)

    val config = bonifiq.config
    val widgetUrl = config.widgetUrl ?: attempt { bonifiq.program.appConfig() }.getOrNull()?.widgetUrl
    if (widgetUrl.isNullOrBlank() || !isHttpUrl(widgetUrl)) return HubChoice.Unavailable(retryable = true)

    return HubChoice.Web(buildEmbedUrl(widgetUrl, config))
}

/** `{widget}/?bqapp=1&tenant=…[&api=…]` — o modo app do widget (sem token na URL). */
internal fun buildEmbedUrl(widgetUrl: String, config: BonifiQConfig): String = buildString {
    append(widgetUrl.trimEnd('/'))
    append("/?bqapp=1&tenant=")
    append(config.tenant.encodeURLParameter())
    if (config.baseUrl.trimEnd('/') != BonifiQConfig.DEFAULT_BASE_URL) {
        append("&api=")
        append(config.baseUrl.trimEnd('/').encodeURLParameter())
    }
}
