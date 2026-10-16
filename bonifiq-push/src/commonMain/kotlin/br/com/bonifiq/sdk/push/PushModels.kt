package br.com.bonifiq.sdk.push

/** Toque do cliente numa notificação da BonifiQ. Leve o cliente ao hub do programa (`BonifiQLoyalty`). */
public data class PushOpen(
    /** Evento que gerou a notificação (ex.: `PointsEarned`). */
    public val type: String?,
    /** Destino sugerido no app, quando a BonifiQ manda um. Por enquanto, abra o hub em qualquer caso. */
    public val target: String?,
    /** Envio de teste feito no painel BonifiQ. */
    public val isTest: Boolean,
)

/** Se o app pode mostrar notificações. */
public enum class PushPermissionStatus {
    /** Liberadas. No iOS, inclui a autorização provisória. */
    Granted,

    /**
     * O cliente recusou ou desligou as notificações do app nas configurações. Use
     * [BonifiQPush.openNotificationSettings]. Também é a resposta onde não há push (desktop).
     */
    Denied,

    /** Ainda não perguntado: [BonifiQPush.requestPermission] mostra o pedido do sistema. */
    NotDetermined,
}
