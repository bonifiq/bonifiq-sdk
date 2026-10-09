package br.com.bonifiq.sdk.ui.web.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberAuthSessionLauncher(): AuthSessionLauncher? {
    val context = LocalContext.current
    return remember(context) {
        val scheme = callbackSchemeFor(context.packageName) ?: return@remember null
        // O manifest do SDK declara o retorno; se o app host o removeu no merge, o widget segue pelo navegador.
        if (!receivesRedirect(context, scheme)) return@remember null
        AndroidAuthSessionLauncher(context, scheme)
    }
}

private fun receivesRedirect(context: Context, scheme: String): Boolean = runCatching {
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://check")).setPackage(context.packageName)
    context.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
}.getOrDefault(false)

private class AndroidAuthSessionLauncher(
    private val context: Context,
    override val callbackScheme: String,
) : AuthSessionLauncher {
    /** No Android o app do provedor abre pelo próprio [url] (App Link); [appUrl] é para o iOS. */
    override fun launch(url: String, appUrl: String?) {
        val intent = Intent(context, BonifiQAuthActivity::class.java).putExtra(BonifiQAuthActivity.EXTRA_URL, url)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            AuthSessionRegistry.finish(AuthSessionResult.Failed(e.message ?: "Não foi possível abrir o login."))
        }
    }
}
