package br.com.bonifiq.sdk.ui.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/** Nome do handler que a página usa (`window.webkit.messageHandlers.bonifiq`). */
private const val HANDLER_NAME = "bonifiq"

internal actual val platformSupportsWebHub: Boolean = true

/** Recebe as mensagens da página; só aceita o frame principal vindo do host do widget. */
private class WidgetMessageHandler(
    private val bridge: WebHubBridge,
    private val expectedHost: String?,
) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val frame = didReceiveScriptMessage.frameInfo
        if (!frame.mainFrame || frame.securityOrigin.host != expectedHost) return
        val body = didReceiveScriptMessage.body as? String ?: return
        bridge.onMessageFromWeb(body)
    }
}

/**
 * O frame principal fica no widget: navegação para outro host (redes sociais, regulamento...) abre no navegador,
 * como no Android. Iframes do conteúdo seguem livres. `targetFrame` nulo é um link para nova janela.
 */
private class WidgetNavigationDelegate(
    private val bridge: WebHubBridge,
    private val expectedHost: String?,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val target = decidePolicyForNavigationAction.request.URL
        val isMainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: true
        if (!isMainFrame || target?.host == expectedHost) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }

        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        val external = target?.absoluteString ?: return
        if (isHttpUrl(external)) bridge.onMessageFromWeb("""{"type":"openUrl","url":${jsStringLiteral(external)}}""")
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun PlatformWebHub(url: String, bridge: WebHubBridge, modifier: Modifier) {
    val expectedHost = remember(url) { NSURL.URLWithString(url)?.host }
    // O WKWebView guarda o navigationDelegate como referência fraca: o remember mantém os dois vivos.
    val handler = remember(bridge, url) { WidgetMessageHandler(bridge, expectedHost) }
    val navigationDelegate = remember(bridge, url) { WidgetNavigationDelegate(bridge, expectedHost) }

    UIKitView(
        factory = {
            val configuration = WKWebViewConfiguration()
            configuration.userContentController.addScriptMessageHandler(handler, name = HANDLER_NAME)
            val webView = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
            webView.navigationDelegate = navigationDelegate
            bridge.sendToWeb = { json ->
                // As mensagens levam o SecureToken: só saem se o WebView ainda estiver no widget.
                if (webView.URL?.host == expectedHost) {
                    webView.evaluateJavaScript(
                        "window.BonifiQEmbed && window.BonifiQEmbed.receive(${jsStringLiteral(json)})",
                        completionHandler = null,
                    )
                }
            }
            NSURL.URLWithString(url)?.let { webView.loadRequest(NSURLRequest.requestWithURL(it)) }
            webView
        },
        modifier = modifier,
        onRelease = { webView ->
            bridge.sendToWeb = null
            webView.navigationDelegate = null
            webView.configuration.userContentController.removeScriptMessageHandlerForName(HANDLER_NAME)
        },
    )
}
