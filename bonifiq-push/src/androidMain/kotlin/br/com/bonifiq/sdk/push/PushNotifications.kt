package br.com.bonifiq.sdk.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.RemoteMessage

/**
 * Notificações da BonifiQ com o app em primeiro plano. Em segundo plano o FCM mostra sozinho (pelo bloco
 * `notification` da mensagem) e o toque abre o app com os mesmos dados; aqui o SDK faz o equivalente.
 */
internal object PushNotifications {
    const val DEFAULT_CHANNEL_ID = "bonifiq_loyalty"
    const val DEFAULT_CHANNEL_NAME = "Programa de fidelidade"

    private const val NOTIFICATION_TAG = "bonifiq"

    // Os mesmos meta-data que o FCM usa nas notificações em segundo plano: o ícone e a cor ficam iguais.
    private const val META_ICON = "com.google.firebase.messaging.default_notification_icon"
    private const val META_COLOR = "com.google.firebase.messaging.default_notification_color"

    /** Cria o canal (ou atualiza o nome). Android 8 ou mais novo; antes disso não há canais. */
    fun ensureChannel(context: Context, id: String, name: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Mostra a notificação; `false` se a mensagem não tem título nem texto ou se as notificações estão desligadas. */
    @SuppressLint("MissingPermission") // conferido em areNotificationsEnabled(); a SecurityException também é tratada
    fun show(context: Context, message: RemoteMessage): Boolean {
        val notification = message.notification ?: return false
        val title = notification.title
        val body = notification.body
        if (title.isNullOrBlank() && body.isNullOrBlank()) return false

        val savedChannel = PushPrefs.channelId(context)
        val channelId = notification.channelId?.takeIf { it.isNotBlank() } ?: savedChannel ?: DEFAULT_CHANNEL_ID
        val channelName = PushPrefs.channelName(context).takeIf { channelId == savedChannel } ?: DEFAULT_CHANNEL_NAME
        ensureChannel(context, channelId, channelName)

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        val id = (message.messageId ?: message.data[PushKeys.COMMUNICATION_ID] ?: "$title|$body").hashCode()
        val tap = tapIntent(context, message.data) ?: return false
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        accentColor(context)?.let(builder::setColor)
        return try {
            manager.notify(NOTIFICATION_TAG, id, builder.build())
            true
        } catch (e: SecurityException) {
            pushLog("Sem permissão para mostrar a notificação: ${e.message}")
            false
        }
    }

    /**
     * Abre o app pela tela inicial com os dados da mensagem nos extras, como o FCM faz em segundo plano. Com o app
     * aberto, a tela recebe o Intent em `onNewIntent`; por isso o app chama `BonifiQPush.handleIntent` nos dois.
     */
    private fun tapIntent(context: Context, data: Map<String, String>): Intent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        data.forEach { (key, value) -> launch.putExtra(key, value) }
        return launch
    }

    private fun smallIcon(context: Context): Int =
        metaData(context)?.getInt(META_ICON, 0)?.takeIf { it != 0 }
            ?: context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info

    private fun accentColor(context: Context): Int? {
        val resource = metaData(context)?.getInt(META_COLOR, 0)?.takeIf { it != 0 } ?: return null
        return runCatching { ContextCompat.getColor(context, resource) }.getOrNull()
    }

    @Suppress("DEPRECATION") // ApplicationInfoFlags só existe a partir do Android 13
    private fun metaData(context: Context): Bundle? = try {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
