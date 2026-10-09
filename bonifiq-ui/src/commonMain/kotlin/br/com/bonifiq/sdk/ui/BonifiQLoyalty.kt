package br.com.bonifiq.sdk.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.ui.internal.CenteredMessage
import br.com.bonifiq.sdk.ui.internal.CenteredProgress
import br.com.bonifiq.sdk.ui.web.HubChoice
import br.com.bonifiq.sdk.ui.web.WebLoyaltyHub
import br.com.bonifiq.sdk.ui.web.platformSupportsWebHub
import br.com.bonifiq.sdk.ui.web.resolveHub

/**
 * Hub de fidelidade completo ("Meus pontos"): o widget web da BonifiQ num WebView, com saldo, recompensas, cupons,
 * extrato, regulamento, missões, quiz, níveis e o que mais o widget da loja tiver. As novidades do widget chegam sem
 * publicar o app. O app só trata os [LoyaltyEvent]:
 * ```
 * BonifiQLoyalty(bonifiq, cartId = orderFormId) { event ->
 *     when (event) {
 *         is LoyaltyEvent.ApplyCoupon -> carrinho.aplicarCupom(event.couponCode)
 *         LoyaltyEvent.LoginRequired -> abrirLogin()
 *         LoyaltyEvent.Close -> voltar()
 *     }
 * }
 * ```
 * Se o widget não carregar, o hub mostra uma mensagem com "Tentar novamente". Em plataformas sem WebView com ponte
 * segura (desktop, Android com WebView desatualizado), avisa que o programa não está disponível no aparelho.
 *
 * @param cartId carrinho atual (na VTEX, o `orderFormId`). Reservado: o widget ainda não associa os resgates a ele.
 */
@Composable
public fun BonifiQLoyalty(
    bonifiq: BonifiQ,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") cartId: String? = null,
    onEvent: (LoyaltyEvent) -> Unit,
) {
    BonifiQTheme(bonifiq) {
        val strings = LocalBonifiQStrings.current
        val currentOnEvent by rememberUpdatedState(onEvent)
        // Cada tentativa resolve o hub de novo e recria o WebView.
        var attempt by remember(bonifiq) { mutableIntStateOf(0) }
        var choice by remember(bonifiq) { mutableStateOf<HubChoice>(HubChoice.Resolving) }
        LaunchedEffect(bonifiq, attempt) {
            choice = HubChoice.Resolving
            choice = resolveHub(bonifiq, platformSupportsWebHub)
        }

        when (val current = choice) {
            HubChoice.Resolving -> CenteredProgress(modifier)
            is HubChoice.Web -> key(attempt) {
                WebLoyaltyHub(
                    bonifiq = bonifiq,
                    url = current.url,
                    onEvent = { currentOnEvent(it) },
                    onFailure = { choice = HubChoice.Unavailable(retryable = true) },
                    modifier = modifier,
                )
            }
            is HubChoice.Unavailable -> if (current.retryable) {
                CenteredMessage(strings.hubLoadFailed, modifier, actionLabel = strings.retry, onAction = { attempt++ })
            } else {
                CenteredMessage(strings.hubUnsupported, modifier, actionLabel = strings.close, onAction = { currentOnEvent(LoyaltyEvent.Close) })
            }
        }
    }
}
