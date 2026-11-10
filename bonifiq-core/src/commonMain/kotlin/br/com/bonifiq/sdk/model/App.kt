package br.com.bonifiq.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Config da loja para apps (`GET /pub/widget/app/config`): a mesma que o loader entrega ao widget web. O SDK só usa
 * os campos abaixo; o widget lê o restante sozinho. Os campos de push vêm vazios de servidores mais antigos.
 */
@Serializable
public data class AppConfig(
    /** URL do widget web da BonifiQ (onde o hub em WebView é carregado). */
    @SerialName("ClientURL") public val widgetUrl: String? = null,
    @SerialName("ProgramName") public val programName: String? = null,
    /** A loja ligou as notificações push do programa no painel BonifiQ (módulo `bonifiq-push`). */
    @SerialName("PushEnabled") public val pushEnabled: Boolean = false,
    /** Projeto Firebase pelo qual a BonifiQ envia os pushes; `null` com o push desligado. */
    @SerialName("PushFirebaseProjectId") public val pushFirebaseProjectId: String? = null,
    /** Canal de notificação do Android usado nos pushes do programa. */
    @SerialName("PushAndroidChannelId") public val pushAndroidChannelId: String? = null,
    /** Nome do canal, exibido nas configurações de notificação do app (ex.: "Programa de fidelidade"). */
    @SerialName("PushAndroidChannelName") public val pushAndroidChannelName: String? = null,
)
