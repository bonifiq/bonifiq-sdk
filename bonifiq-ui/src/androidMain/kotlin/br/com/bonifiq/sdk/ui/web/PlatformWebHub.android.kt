package br.com.bonifiq.sdk.ui.web

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Nome do objeto que a página enxerga (`window.BonifiQNative`). */
private const val BRIDGE_OBJECT = "BonifiQNative"

/**
 * Só usa o WebView quando há `WebMessageListener`: a ponte fica restrita à origem do widget (allowlist),
 * ao contrário de `addJavascriptInterface`, que exporia o objeto para qualquer página carregada.
 */
internal actual val platformSupportsWebHub: Boolean
    get() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false)

@Composable
internal actual fun PlatformWebHub(url: String, bridge: WebHubBridge, modifier: Modifier) {
    val origin = remember(url) { Uri.parse(url).let { "${it.scheme}://${it.authority}" } }

    BoxWithConstraints(modifier) {
        // Sem layoutParams o WebView assume WRAP_CONTENT e mede a altura pelo conteúdo: o `100vh` da página deixa de
        // ser a altura da tela e o widget fica cortado. Com tamanho definido pelo Compose, MATCH_PARENT.
        val layoutParams = ViewGroup.LayoutParams(
            if (constraints.hasFixedWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (constraints.hasFixedHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        WidgetWebView(url, origin, bridge, layoutParams)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WidgetWebView(url: String, origin: String, bridge: WebHubBridge, layoutParams: ViewGroup.LayoutParams) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                this.layoutParams = layoutParams
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                var replyProxy: JavaScriptReplyProxy? = null
                bridge.sendToWeb = { json -> post { replyProxy?.postMessage(json) } }

                WebViewCompat.addWebMessageListener(this, BRIDGE_OBJECT, setOf(origin)) { _, message, _, isMainFrame, proxy ->
                    if (!isMainFrame) return@addWebMessageListener
                    replyProxy = proxy
                    message.data?.let(bridge::onMessageFromWeb)
                }

                webViewClient = object : WebViewClient() {
                    // Navegação para fora do widget (links de redes sociais, regulamento...) abre no navegador.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url
                        if ("${target.scheme}://${target.authority}" == origin) return false
                        val external = target.toString()
                        if (isHttpUrl(external)) bridge.onMessageFromWeb("""{"type":"openUrl","url":${jsStringLiteral(external)}}""")
                        return true
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) bridge.onMessageFromWeb("""{"type":"error","message":"Falha ao carregar o widget"}""")
                    }
                }

                loadUrl(url)
            }
        },
        onRelease = { webView ->
            bridge.sendToWeb = null
            webView.destroy()
        },
    )
}
