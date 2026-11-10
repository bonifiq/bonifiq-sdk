package br.com.bonifiq.sdk.push

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Um pedido de permissão de notificação por vez, conduzido pela [PushPermissionActivity]. */
internal object NotificationPermissionRequest {
    private val mutex = Mutex()

    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    val isPending: Boolean get() = pending?.isActive == true

    suspend fun request(): Boolean = mutex.withLock {
        val result = CompletableDeferred<Boolean>()
        pending = result
        try {
            PushAndroid.startActivity(Intent(PushAndroid.context, PushPermissionActivity::class.java))
            result.await()
        } finally {
            pending = null
        }
    }

    fun complete(granted: Boolean) {
        pending?.complete(granted)
    }
}

/**
 * Tela invisível que abre o pedido de `POST_NOTIFICATIONS` (Android 13 ou mais novo) e devolve a resposta ao
 * [NotificationPermissionRequest]. Registrar o contrato no `onCreate` faz a resposta chegar mesmo se a tela for
 * recriada (ex.: rotação).
 */
internal class PushPermissionActivity : ComponentActivity() {

    private val permissionRequest: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        NotificationPermissionRequest.complete(granted)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // pedido já aberto; a resposta chega ao contrato registrado acima

        if (Build.VERSION.SDK_INT < 33 || !NotificationPermissionRequest.isPending) {
            NotificationPermissionRequest.complete(false)
            finish()
            return
        }
        permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
