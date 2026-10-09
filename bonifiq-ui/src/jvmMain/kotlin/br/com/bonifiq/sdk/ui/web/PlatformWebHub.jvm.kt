package br.com.bonifiq.sdk.ui.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Compose Desktop não tem WebView embutido: o hub avisa que não está disponível. */
internal actual val platformSupportsWebHub: Boolean = false

@Composable
internal actual fun PlatformWebHub(url: String, bridge: WebHubBridge, modifier: Modifier) {
    // Nunca chamado na JVM (platformSupportsWebHub = false).
}
