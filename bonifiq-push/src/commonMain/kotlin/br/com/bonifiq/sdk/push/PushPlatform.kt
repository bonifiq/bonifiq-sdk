package br.com.bonifiq.sdk.push

import br.com.bonifiq.sdk.model.AppConfig

/** Ponte para o sistema de notificações de cada plataforma. */
internal interface PushPlatform {
    /** `android` ou `ios`, como a BonifiQ espera; `null` onde não há push (desktop): o SDK não registra nada. */
    val name: String?

    /** Pacote (Android) ou bundle id (iOS) do app. */
    val appId: String?

    val appVersion: String?

    /** Idioma do aparelho (ex.: `pt-BR`). */
    val locale: String?

    /** Começa a acompanhar o token. No Android, pede o token atual ao Firebase; no iOS, o app entrega o token. */
    fun start()

    /** Config da loja com o push ligado: no Android, cria o canal de notificação e confere o projeto Firebase. */
    fun onPushConfig(config: AppConfig)

    suspend fun permissionStatus(): PushPermissionStatus

    /** Mostra o pedido do sistema, se ainda couber, e devolve o estado depois da resposta do cliente. */
    suspend fun requestPermission(): PushPermissionStatus

    fun openNotificationSettings(): Boolean
}

internal expect fun platformPush(): PushPlatform

/** Avisos de configuração e falhas do módulo (Logcat no Android, console no iOS). Nunca registra tokens. */
internal expect fun pushLog(message: String)
