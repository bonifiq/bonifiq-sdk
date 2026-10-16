package br.com.bonifiq.sdk.push

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import br.com.bonifiq.sdk.model.AppConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.Locale

internal actual fun platformPush(): PushPlatform = AndroidPushPlatform

/** Android: FCM pelo Firebase que o app configurou (`google-services.json`). */
internal object AndroidPushPlatform : PushPlatform {
    override val name: String = "android"

    override val appId: String? get() = PushAndroid.contextOrNull?.packageName

    @Suppress("DEPRECATION") // PackageInfoFlags só existe a partir do Android 13
    override val appVersion: String?
        get() = PushAndroid.contextOrNull?.let { context ->
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }

    override val locale: String get() = Locale.getDefault().toLanguageTag()

    // O token de registro está obsoleto desde o firebase-messaging 25.1 (veja BonifiQMessagingService), mas é o que a
    // BonifiQ usa para enviar e continua no 26.
    @Suppress("DEPRECATION")
    override fun start() {
        try {
            val messaging = FirebaseMessaging.getInstance()
            if (!messaging.isAutoInitEnabled) {
                // O app desligou a inicialização automática do FCM (ex.: espera o consentimento do cliente). Pedir o
                // token agora geraria um; ele chega pelo onNewToken quando o app ligar o FCM.
                pushLog("O FCM está com a inicialização automática desligada: o token chega quando o app ligá-la.")
                return
            }
            messaging.token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    PushRuntime.setToken(task.result)
                } else {
                    pushLog("Não foi possível obter o token FCM: ${task.exception?.message}")
                }
            }
        } catch (e: Exception) {
            // IllegalStateException: o Firebase não foi inicializado (sem google-services.json no app).
            pushLog("O Firebase não está configurado no app (google-services.json): o push da BonifiQ fica desligado. ${e.message}")
        }
    }

    override fun onPushConfig(config: AppConfig) {
        val context = PushAndroid.contextOrNull ?: return
        val channelId = config.pushAndroidChannelId?.takeIf { it.isNotBlank() } ?: PushNotifications.DEFAULT_CHANNEL_ID
        val channelName = config.pushAndroidChannelName?.takeIf { it.isNotBlank() } ?: PushNotifications.DEFAULT_CHANNEL_NAME
        PushPrefs.saveChannel(context, channelId, channelName)
        // O FCM mostra sozinho as notificações com o app em segundo plano, mas só no canal que já existe.
        PushNotifications.ensureChannel(context, channelId, channelName)

        val appProject = try {
            FirebaseApp.getInstance().options.projectId
        } catch (_: IllegalStateException) {
            null
        }
        firebaseProjectWarning(appProject, config.pushFirebaseProjectId)?.let(::pushLog)
    }

    override suspend fun permissionStatus(): PushPermissionStatus {
        val context = PushAndroid.context
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < 33) {
            return if (enabled) PushPermissionStatus.Granted else PushPermissionStatus.Denied
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return when {
            granted && enabled -> PushPermissionStatus.Granted
            granted || PushPrefs.permissionAsked(context) -> PushPermissionStatus.Denied
            else -> PushPermissionStatus.NotDetermined
        }
    }

    override suspend fun requestPermission(): PushPermissionStatus {
        val current = permissionStatus()
        if (current == PushPermissionStatus.Granted || Build.VERSION.SDK_INT < 33) return current
        // Depois de duas recusas o Android não mostra mais o pedido; a resposta volta na hora como recusa.
        NotificationPermissionRequest.request()
        PushPrefs.setPermissionAsked(PushAndroid.context)
        return permissionStatus()
    }

    override fun openNotificationSettings(): Boolean {
        val context = PushAndroid.context
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        PushAndroid.startActivity(intent)
        return true
    }
}
