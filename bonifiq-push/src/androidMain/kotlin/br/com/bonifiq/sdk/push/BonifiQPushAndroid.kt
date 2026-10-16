@file:JvmName("BonifiQPushAndroid")

package br.com.bonifiq.sdk.push

import android.content.Intent
import com.google.firebase.messaging.RemoteMessage

/** Marca o Intent já tratado, para a mesma abertura não contar de novo (ex.: `onCreate` depois de uma rotação). */
private const val EXTRA_HANDLED = "br.com.bonifiq.sdk.push.HANDLED"

/**
 * Mensagem do FCM recebida pelo `FirebaseMessagingService` do app. Chame no `onMessageReceived` do serviço:
 *
 * ```
 * override fun onMessageReceived(message: RemoteMessage) {
 *     if (BonifiQPush.onMessageReceived(message)) return   // da BonifiQ: o SDK cuidou
 *     // mensagens do próprio app
 * }
 * ```
 *
 * Se a mensagem é da BonifiQ, mostra a notificação (o FCM só mostra sozinho com o app em segundo plano) e devolve
 * `true`. As outras mensagens devolvem `false`, sem nada feito: são do app.
 */
public fun BonifiQPush.Companion.onMessageReceived(message: RemoteMessage): Boolean {
    if (!isBonifiQMessage(message.data)) return false
    val context = PushAndroid.contextOrNull
    if (context == null) {
        pushLog("Notificação da BonifiQ ignorada: o app removeu o androidx.startup.InitializationProvider do manifest.")
        return true
    }
    PushNotifications.show(context, message)
    return true
}

/** Token novo recebido pelo `FirebaseMessagingService` do app. Chame no `onNewToken` do serviço. */
public fun BonifiQPush.Companion.onNewToken(token: String) {
    setToken(token)
}

/**
 * Toque numa notificação. Chame no `onCreate` e no `onNewIntent` da Activity de launcher, que é a que a notificação
 * abre (se ela é só um splash, repasse o Intent dela):
 *
 * ```
 * if (BonifiQPush.handleIntent(intent)) abrirHub()   // ou leve ao hub ao coletar push.opens (use um dos dois)
 * ```
 *
 * Se o Intent veio de uma notificação da BonifiQ, registra a abertura, emite em [BonifiQPush.opens] e devolve `true`.
 * Funciona com a notificação mostrada pelo FCM (app em segundo plano) e pelo SDK (app aberto). Outros Intents, e o
 * mesmo Intent de novo (rotação, reabrir pelos recentes), devolvem `false`.
 */
public fun BonifiQPush.Companion.handleIntent(intent: Intent?): Boolean {
    val extras = intent?.extras ?: return false
    if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
    if (extras.getBoolean(EXTRA_HANDLED, false)) return false
    val message = BonifiQMessage.from { key -> extras.getString(key) } ?: return false
    intent.putExtra(EXTRA_HANDLED, true)
    PushRuntime.opened(message)
    return true
}
