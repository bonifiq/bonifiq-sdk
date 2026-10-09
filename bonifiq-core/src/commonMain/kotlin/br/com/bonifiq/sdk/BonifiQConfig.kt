package br.com.bonifiq.sdk

/**
 * Configuração do SDK.
 *
 * @param tenant identificador público da loja na BonifiQ (header `X-Bq-Tenant`).
 * @param baseUrl host da API BonifiQ; o SDK usa a API Widget em `{baseUrl}/pub/widget`.
 * @param enableLogging registra método, URL e status das chamadas (nunca headers, tokens ou corpos).
 * @param widgetUrl URL do widget web que o hub (`BonifiQLoyalty`) abre; `null` usa a configurada para a loja na BonifiQ.
 */
public data class BonifiQConfig(
    public val tenant: String,
    public val baseUrl: String = DEFAULT_BASE_URL,
    public val enableLogging: Boolean = false,
    public val widgetUrl: String? = null,
) {
    init {
        require(tenant.isNotBlank()) { "tenant não pode ser vazio" }
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.bonifiq.com.br"
    }
}
