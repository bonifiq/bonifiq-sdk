package br.com.bonifiq.sdk.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.ui.LoyaltyEvent
import br.com.bonifiq.sdk.ui.internal.CenteredProgress
import br.com.bonifiq.sdk.ui.web.auth.rememberAuthSessionLauncher
import kotlinx.coroutines.delay

/** Tempo máximo para o widget responder `ready`; depois disso o hub mostra a opção de tentar de novo. */
internal const val WEB_HUB_READY_TIMEOUT_MS: Long = 12_000

/** A plataforma tem WebView com ponte segura (restrita à origem do widget). */
internal expect val platformSupportsWebHub: Boolean

/** WebView da plataforma carregando [url] e ligado à [bridge]. */
@Composable
internal expect fun PlatformWebHub(url: String, bridge: WebHubBridge, modifier: Modifier)

/** Hub de fidelidade: o widget web da BonifiQ num WebView. Chama [onFailure] se não carregar. */
@Composable
internal fun WebLoyaltyHub(
    bonifiq: BonifiQ,
    url: String,
    onEvent: (LoyaltyEvent) -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentOnFailure by rememberUpdatedState(onFailure)
    var ready by remember(url) { mutableStateOf(false) }
    val authLauncher = rememberAuthSessionLauncher()

    val bridge = remember(bonifiq, url, authLauncher) {
        WebHubBridge(
            bonifiq = bonifiq,
            scope = scope,
            onEvent = { currentOnEvent(it) },
            openUrl = { runCatching { uriHandler.openUri(it) } },
            onReady = { ready = true },
            onFailure = { currentOnFailure() },
            authLauncher = authLauncher,
        )
    }

    val auth by bonifiq.auth.state.collectAsState()
    LaunchedEffect(bridge, auth) { bridge.onAuthStateChanged() }

    LaunchedEffect(bridge) {
        delay(WEB_HUB_READY_TIMEOUT_MS)
        if (!ready) currentOnFailure()
    }

    Box(modifier.fillMaxSize()) {
        PlatformWebHub(url = url, bridge = bridge, modifier = Modifier.fillMaxSize())
        if (!ready) CenteredProgress()
    }
}
