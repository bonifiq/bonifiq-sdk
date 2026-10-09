package br.com.bonifiq.sdk

/**
 * API de uso interno entre os módulos do SDK (ex.: o hub em WebView do `bonifiq-ui` entrega o SecureToken à
 * página do widget). Não faz parte do contrato público: pode mudar sem aviso.
 */
@RequiresOptIn(
    message = "API interna do SDK BonifiQ — não use no app.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class BonifiQInternalApi
