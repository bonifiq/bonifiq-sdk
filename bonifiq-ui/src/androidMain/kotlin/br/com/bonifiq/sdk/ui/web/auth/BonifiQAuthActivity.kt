package br.com.bonifiq.sdk.ui.web.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Tela invisível que conduz o login de terceiros (mesmo desenho do AppAuth):
 * 1. abre o app do provedor, se estiver instalado (Android 11+), ou o login web numa Custom Tab;
 * 2. o retorno `bq.<applicationId>://...` chega pela [BonifiQAuthRedirectActivity], que traz esta tela de volta com
 *    `CLEAR_TOP` (fechando a Custom Tab) e entrega a URL em [onNewIntent];
 * 3. se o cliente voltar ao app sem concluir, [onResume] cancela.
 */
internal class BonifiQAuthActivity : Activity() {

    private var authorizationStarted = false
    private var resumedAfterStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authorizationStarted = savedInstanceState?.getBoolean(STATE_STARTED) ?: false
        if (authorizationStarted) return

        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null || !AuthSessionRegistry.isPending) {
            // Ex.: o processo morreu durante o login e a tela voltou sem sessão para entregar.
            finish()
            return
        }
        authorizationStarted = true
        openAuthorization(Uri.parse(url))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val returned = intent.data?.toString()
        if (returned == null || !AuthSessionRegistry.complete(returned)) {
            AuthSessionRegistry.finish(AuthSessionResult.Cancelled)
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // O primeiro onResume vem logo após abrir o login; os seguintes são o cliente voltando sem retorno.
        if (resumedAfterStart) {
            AuthSessionRegistry.finish(AuthSessionResult.Cancelled)
            finish()
        }
        resumedAfterStart = authorizationStarted
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_STARTED, authorizationStarted)
    }

    private fun openAuthorization(uri: Uri) {
        // App Link do provedor (ex.: o app do Strava trata https://www.strava.com/oauth/mobile/authorize).
        // REQUIRE_NON_BROWSER: só abre se um app que não é navegador atender; senão cai na Custom Tab.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val app = Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
            try {
                startActivity(app)
                return
            } catch (_: ActivityNotFoundException) {
                // Sem o app do provedor: login web.
            }
        }
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
        } catch (_: ActivityNotFoundException) {
            AuthSessionRegistry.finish(AuthSessionResult.Failed("Nenhum navegador disponível para o login."))
            finish()
        }
    }

    internal companion object {
        const val EXTRA_URL = "br.com.bonifiq.sdk.auth.URL"
        private const val STATE_STARTED = "br.com.bonifiq.sdk.auth.STARTED"
    }
}

/** Recebe `bq.<applicationId>://...` e devolve à [BonifiQAuthActivity], fechando o que estiver por cima dela. */
internal class BonifiQAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, BonifiQAuthActivity::class.java)
            .setData(intent?.data)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(forward)
        finish()
    }
}
