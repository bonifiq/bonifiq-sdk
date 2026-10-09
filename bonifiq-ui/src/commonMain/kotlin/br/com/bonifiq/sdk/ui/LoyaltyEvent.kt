package br.com.bonifiq.sdk.ui

/**
 * Eventos que o hub de fidelidade ([BonifiQLoyalty]) envia ao app. São os mesmos que a versão WebView do hub
 * vai emitir (ver `docs/webview-bridge.md`), então o código do app não muda quando a implementação trocar.
 */
public sealed interface LoyaltyEvent {

    /** O cliente quer usar este cupom: aplique [couponCode] no carrinho da plataforma. */
    public data class ApplyCoupon(public val couponCode: String, public val redeemedId: Long?) : LoyaltyEvent

    /** É preciso login para continuar: abra o login do app e depois autentique na BonifiQ. */
    public data object LoginRequired : LoyaltyEvent

    /** O cliente pediu para fechar o hub. */
    public data object Close : LoyaltyEvent
}
