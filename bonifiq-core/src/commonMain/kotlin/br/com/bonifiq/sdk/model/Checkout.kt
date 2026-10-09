package br.com.bonifiq.sdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Configuração do programa para exibição (nome, cor) e quais mecânicas estão ativas. */
@Serializable
public data class ProgramConfiguration(
    @SerialName("ProgramName") public val programName: String? = null,
    /** Cor principal em hexadecimal (ex.: "#0088ff"). */
    @SerialName("Color") public val color: String? = null,
    @SerialName("Text") public val text: String? = null,
    @SerialName("Active") public val active: Boolean = true,
    @SerialName("UseCashback") public val useCashback: Boolean = false,
    @SerialName("UsePoints") public val usePoints: Boolean = true,
)

/** Quanto cashback o cliente pode usar no carrinho atual. Valores em reais. */
@Serializable
public data class CashbackOptions(
    @SerialName("CashbackBalance") public val balance: Double = 0.0,
    @SerialName("UsableCashback") public val usable: Double = 0.0,
    @SerialName("MaxUsableCashback") public val maxUsable: Double? = null,
    @SerialName("CanUse") public val canUse: Boolean = false,
    @SerialName("IsCashbackAvailable") public val isAvailable: Boolean = false,
    @SerialName("CashbackRules") public val rules: String? = null,
    /** Ex.: "Você receberá R$ 5,00 de cashback nesta compra". */
    @SerialName("CashbackWillReceiveText") public val willReceiveText: String? = null,
    /** Ex.: "Faltam R$ 10,00 para usar seu cashback". */
    @SerialName("RequiredToAddMoreText") public val requiredToAddMoreText: String? = null,
    @SerialName("MinValueToUse") public val minCartValue: Double? = null,
    @SerialName("RemainingToUse") public val remainingToUse: Double? = null,
    @SerialName("HasProductsIneligibleForCashback") public val hasIneligibleProducts: Boolean = false,
)

/**
 * Cashback aplicado (ou consultado) num carrinho. Se [couponCode] vier preenchido, o app deve aplicar
 * esse cupom no carrinho da plataforma; caso contrário a BonifiQ já aplicou o desconto diretamente.
 */
@Serializable
public data class CashbackApplication(
    @SerialName("AppliedCashback") public val applied: Double = 0.0,
    @SerialName("Success") public val success: Boolean = false,
    @SerialName("CustomerMessage") public val message: String? = null,
    @SerialName("CashbackRules") public val rules: String? = null,
    @SerialName("CouponCode") public val couponCode: String? = null,
    @SerialName("InternalMessage") public val internalMessage: String? = null,
)

/** Resultado da revalidação do cashback após mudança no carrinho. */
@Serializable
public data class CashbackRefresh(
    /** `true` se o cashback foi removido (ex.: carrinho abaixo do mínimo). Mostre [message] ao cliente. */
    @SerialName("CashbackRemoved") public val removed: Boolean = false,
    @SerialName("CustomerMessage") public val message: String? = null,
)

@Serializable
public data class CashbackRemoval(
    @SerialName("Success") public val success: Boolean = false,
    @SerialName("CustomerMessage") public val message: String? = null,
)

/** Regras gerais de cashback do programa (independem do carrinho). */
@Serializable
public data class CashbackSettings(
    @SerialName("Enabled") public val enabled: Boolean = false,
    /** Valor em reais de cada ponto convertido em cashback. */
    @SerialName("CashbackPointValue") public val pointValue: Double? = null,
    @SerialName("MinimalValue") public val minCartValue: Double? = null,
    @SerialName("MaxUsableCashback") public val maxUsable: Double? = null,
)

@Serializable
internal data class TermsResponse(
    @SerialName("Terms") val terms: String? = null,
)
