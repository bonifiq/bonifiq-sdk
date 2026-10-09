@file:OptIn(ExperimentalForeignApi::class)

package br.com.bonifiq.sdk.ui.web.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIWindow
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/** Tempo para o retorno chegar depois que o app volta a ficar ativo, antes de considerar cancelado. */
private const val RETURN_GRACE_MS = 1_500L

@Composable
internal actual fun rememberAuthSessionLauncher(): AuthSessionLauncher? = remember {
    // Esquemas de URL no iOS não diferenciam caixa: o bundle id vai em minúsculas.
    val bundleId = NSBundle.mainBundle.bundleIdentifier ?: return@remember null
    callbackSchemeFor(bundleId.lowercase())?.let(::IosAuthSessionLauncher)
}

private class IosAuthSessionLauncher(override val callbackScheme: String) : AuthSessionLauncher {
    // O ASWebAuthenticationSession precisa de referência forte enquanto estiver aberto.
    private var session: ASWebAuthenticationSession? = null
    private val anchorProvider = PresentationAnchorProvider()

    override fun launch(url: String, appUrl: String?) {
        val sessionId = AuthSessionRegistry.currentId()
        val providerApp = appUrl?.let { NSURL.URLWithString(it) }
        // O app do provedor só consegue voltar para cá se o app host registrou o esquema (CFBundleURLTypes) e
        // repassa a URL ao SDK (BonifiQAuthRedirect). Sem isso, a tela de login do sistema, que não exige nada.
        if (providerApp != null && hostRegisteredScheme(callbackScheme)) {
            UIApplication.sharedApplication.openURL(providerApp, options = emptyMap<Any?, Any?>()) { opened ->
                if (opened) cancelIfReturnsWithoutResult(sessionId) else startWebSession(url)
            }
            return
        }
        startWebSession(url)
    }

    private fun startWebSession(url: String) {
        val target = NSURL.URLWithString(url)
        if (target == null) {
            AuthSessionRegistry.finish(AuthSessionResult.Failed("URL de login inválida."))
            return
        }
        val webSession = ASWebAuthenticationSession(uRL = target, callbackURLScheme = callbackScheme) { returned, error ->
            dispatch_async(dispatch_get_main_queue()) {
                session = null
                val returnedUrl = returned?.absoluteString
                when {
                    returnedUrl != null -> if (!AuthSessionRegistry.complete(returnedUrl)) {
                        AuthSessionRegistry.finish(AuthSessionResult.Cancelled)
                    }
                    error?.code == ASWebAuthenticationSessionErrorCodeCanceledLogin ->
                        AuthSessionRegistry.finish(AuthSessionResult.Cancelled)
                    else -> AuthSessionRegistry.finish(
                        AuthSessionResult.Failed(error?.localizedDescription ?: "O login não foi concluído."),
                    )
                }
            }
        }
        webSession.presentationContextProvider = anchorProvider
        // Compartilha os cookies do Safari: quem já está logado no provedor não digita a senha de novo.
        webSession.prefersEphemeralWebBrowserSession = false
        session = webSession
        if (!webSession.start()) {
            session = null
            AuthSessionRegistry.finish(AuthSessionResult.Failed("Não foi possível abrir o login."))
        }
    }

    /**
     * Abriu o app do provedor: se o cliente voltar sem passar pelo retorno (ex.: fechou o Strava), cancela.
     * Espera o app ir para o fundo e voltar, e dá um tempo para a URL de retorno chegar.
     */
    private fun cancelIfReturnsWithoutResult(sessionId: Long) {
        val center = NSNotificationCenter.defaultCenter
        var backgroundObserver: NSObjectProtocol? = null
        backgroundObserver = center.addObserverForName(
            UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            backgroundObserver?.let(center::removeObserver)
            var activeObserver: NSObjectProtocol? = null
            activeObserver = center.addObserverForName(
                UIApplicationDidBecomeActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                activeObserver?.let(center::removeObserver)
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (RETURN_GRACE_MS * NSEC_PER_MSEC.toLong())), dispatch_get_main_queue()) {
                    AuthSessionRegistry.finish(sessionId, AuthSessionResult.Cancelled)
                }
            }
        }
    }
}

private class PresentationAnchorProvider : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor {
        val app = UIApplication.sharedApplication
        @Suppress("DEPRECATION")
        return app.keyWindow ?: app.windows.firstOrNull() as? UIWindow ?: UIWindow()
    }
}

/** O app host declarou [scheme] em `CFBundleURLTypes`? */
private fun hostRegisteredScheme(scheme: String): Boolean {
    val types = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleURLTypes") as? List<*> ?: return false
    return types.any { type ->
        val schemes = (type as? Map<*, *>)?.get("CFBundleURLSchemes") as? List<*>
        schemes?.any { (it as? String)?.lowercase() == scheme } == true
    }
}
