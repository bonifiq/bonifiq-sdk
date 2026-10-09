package br.com.bonifiq.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Config da loja para apps (`GET /pub/widget/app/config`): a mesma que o loader entrega ao widget web. O SDK só usa
 * os campos abaixo; o widget lê o restante sozinho.
 */
@Serializable
public data class AppConfig(
    /** URL do widget web da BonifiQ (onde o hub em WebView é carregado). */
    @SerialName("ClientURL") public val widgetUrl: String? = null,
    @SerialName("ProgramName") public val programName: String? = null,
)
