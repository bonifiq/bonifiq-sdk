package br.com.bonifiq.sdk.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Textos dos componentes (pt-BR). Para trocar, forneça outra instância:
 * `CompositionLocalProvider(LocalBonifiQStrings provides BonifiQStrings(cashbackApply = "Usar saldo")) { … }`.
 */
public data class BonifiQStrings(
    public val loginAction: String = "Entrar",
    public val hubLoadFailed: String = "Não foi possível carregar o programa de fidelidade.",
    public val hubUnsupported: String = "O programa de fidelidade não está disponível neste aparelho.",
    public val retry: String = "Tentar novamente",
    public val close: String = "Fechar",
    public val cashbackTitle: String = "Cashback",
    public val cashbackBalance: String = "Saldo: %s",
    public val cashbackUsable: String = "Você pode usar até %s nesta compra.",
    public val cashbackValueLabel: String = "Valor a usar",
    public val cashbackApply: String = "Usar cashback",
    public val cashbackApplyAll: String = "Usar %s de cashback",
    public val cashbackMax: String = "Máximo de %s",
    public val cashbackApplied: String = "Você está usando %s de cashback.",
    public val cashbackRemove: String = "Remover",
    public val cashbackLogin: String = "Entre para usar seu cashback nesta compra.",
    public val emailLoginTitle: String = "Acesse com seu e-mail",
    public val emailLoginMessage: String = "Enviaremos um código de 6 dígitos para o seu e-mail.",
    public val emailLabel: String = "E-mail",
    public val sendCode: String = "Enviar código",
    public val codeLabel: String = "Código",
    public val codeSentTo: String = "Enviamos um código para %s.",
    public val confirmCode: String = "Entrar",
    public val changeEmail: String = "Usar outro e-mail",
)

public val LocalBonifiQStrings: androidx.compose.runtime.ProvidableCompositionLocal<BonifiQStrings> =
    staticCompositionLocalOf { BonifiQStrings() }
