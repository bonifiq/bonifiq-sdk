package br.com.bonifiq.sdk.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Recebe as mensagens e o token do FCM. Declarado no manifest do módulo. O app que já tem o próprio
 * `FirebaseMessagingService` remove este no manifest (`tools:node="remove"`) e repassa as chamadas a
 * [BonifiQPush.Companion.onMessageReceived] e [BonifiQPush.Companion.onNewToken].
 */
internal class BonifiQMessagingService : FirebaseMessagingService() {
    // A BonifiQ envia pelo token de registro do FCM. O firebase-messaging 25.1 marcou o token como obsoleto em favor
    // do registro por Firebase Installation ID (onRegistered), que entrega outro identificador e exige envio por `fid`
    // no servidor; os dois modos seguem suportados. Por isso o SDK não sobrescreve onRegistered.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        BonifiQPush.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        BonifiQPush.onMessageReceived(message)
    }
}
