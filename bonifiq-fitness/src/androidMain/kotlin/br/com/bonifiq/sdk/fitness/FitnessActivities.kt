package br.com.bonifiq.sdk.fitness

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Um pedido de permissão por vez, conduzido pela [FitnessPermissionActivity]. */
internal object HealthPermissionRequest {
    private val mutex = Mutex()

    @Volatile
    private var pending: CompletableDeferred<Set<String>>? = null

    val isPending: Boolean get() = pending?.isActive == true

    suspend fun request(permissions: Set<String>): Set<String> = mutex.withLock {
        val result = CompletableDeferred<Set<String>>()
        pending = result
        try {
            val intent = Intent(FitnessAndroid.context, FitnessPermissionActivity::class.java)
                .putExtra(FitnessPermissionActivity.EXTRA_PERMISSIONS, permissions.toTypedArray())
            FitnessAndroid.startActivity(intent)
            result.await()
        } finally {
            pending = null
        }
    }

    fun complete(granted: Set<String>) {
        pending?.complete(granted)
    }
}

/**
 * Tela invisível que abre o pedido de permissão do Health Connect e devolve a resposta ao [HealthPermissionRequest].
 * Registrar o contrato no `onCreate` faz a resposta chegar mesmo se a tela for recriada (ex.: rotação).
 */
internal class FitnessPermissionActivity : ComponentActivity() {

    private val permissionRequest: ActivityResultLauncher<Set<String>> = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(HealthConnectSource.PROVIDER_PACKAGE),
    ) { granted ->
        HealthPermissionRequest.complete(granted)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // pedido já aberto; a resposta chega ao contrato registrado acima

        val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS)?.toSet()
        if (permissions.isNullOrEmpty() || !HealthPermissionRequest.isPending) {
            HealthPermissionRequest.complete(emptySet())
            finish()
            return
        }
        try {
            permissionRequest.launch(permissions)
        } catch (_: ActivityNotFoundException) {
            HealthPermissionRequest.complete(emptySet())
            finish()
        }
    }

    internal companion object {
        const val EXTRA_PERMISSIONS = "br.com.bonifiq.sdk.fitness.PERMISSIONS"
    }
}

/**
 * Política de privacidade do app, exigida pelo Health Connect: sem esta tela ele não mostra o pedido de permissão.
 * Abre a URL definida pelo app em
 * `<meta-data android:name="br.com.bonifiq.sdk.fitness.PRIVACY_POLICY_URL" android:value="https://..." />`.
 */
internal class FitnessPrivacyPolicyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = privacyPolicyUrl()
        if (url == null) {
            Log.w(TAG, "Defina a meta-data $PRIVACY_POLICY_URL (https://...) no <application> do AndroidManifest.")
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Log.w(TAG, "Nenhum navegador para abrir a política de privacidade.")
            }
        }
        finish()
    }

    @Suppress("DEPRECATION") // ApplicationInfoFlags só existe a partir do Android 13
    private fun privacyPolicyUrl(): String? = try {
        packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData?.getString(PRIVACY_POLICY_URL)
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        const val TAG = "BonifiQFitness"
        const val PRIVACY_POLICY_URL = "br.com.bonifiq.sdk.fitness.PRIVACY_POLICY_URL"
    }
}
