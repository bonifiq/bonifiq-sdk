package br.com.bonifiq.sdk.model

import br.com.bonifiq.sdk.internal.FlexibleLongSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Recompensa do programa. Com cliente logado, [canUse]/[useReason] refletem o saldo dele. */
@Serializable
public data class Reward(
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("Id") public val id: Long,
    @SerialName("Type") public val typeCode: Int = -1,
    @SerialName("Title") public val title: String = "",
    @SerialName("Description") public val description: String? = null,
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("Points") public val points: Long = 0,
    @SerialName("Enabled") public val enabled: Boolean = true,
    @SerialName("DisabledReason") public val disabledReason: String? = null,
    @SerialName("CanUse") public val canUse: Boolean = false,
    @SerialName("UseReason") public val useReasonCode: Int? = null,
    @SerialName("NoPointCost") public val noPointCost: Boolean = false,
    @SerialName("CashbackBalance") public val cashbackBalance: Double? = null,
    @SerialName("CashbackPointValue") public val cashbackPointValue: Double? = null,
    @SerialName("CashbackRules") public val cashbackRules: String? = null,
    @SerialName("IsCashbackInformativeOnly") public val isCashbackInformativeOnly: Boolean = false,
    @SerialName("CashbackInformativeText") public val cashbackInformativeText: String? = null,
    @SerialName("MinValueToUse") public val minValueToUse: Double? = null,
    @SerialName("RemainingToUse") public val remainingToUse: Double? = null,
    @SerialName("WidgetIcon") public val iconUrl: String? = null,
) {
    public val type: RewardType get() = RewardType.fromCode(typeCode)
    public val useReason: UseReason get() = UseReason.fromCode(useReasonCode)

    /** Cashback é usado no checkout, não resgatado na lista. */
    public val isRedeemable: Boolean
        get() = enabled && type != RewardType.PointToCashback && !isCashbackInformativeOnly
}

public enum class RewardType(public val code: Int) {
    PercentDiscountCoupon(0),
    ValueDiscountCoupon(1),
    FreightDiscountCoupon(2),
    PointToCashback(3),
    Customized(4),
    Unknown(-1),
    ;

    public companion object {
        public fun fromCode(code: Int?): RewardType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

public enum class UseReason(public val code: Int) {
    CanUse(0),
    NotEnoughPoints(1),
    MinValueNotReached(2),
    CashbackNotAvailable(3),
    NoCustomer(4),
    ValueRewardBiggerThanPurchase(5),
    MinimumPercentPurchaseNotReached(6),
    CustomerNotEnrolled(7),
    CannotUseCumulativeDiscount(8),
    Unknown(-1),
    ;

    public companion object {
        public fun fromCode(code: Int?): UseReason = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** Resultado de um resgate bem-sucedido; [couponCode] é o cupom a aplicar no carrinho. */
@Serializable
public data class RedeemResult(
    @SerialName("Success") public val success: Boolean = false,
    @SerialName("ResultMessage") public val resultMessage: String? = null,
    @SerialName("CouponCode") public val couponCode: String? = null,
    @SerialName("AfterMessage") public val afterMessage: String? = null,
    @SerialName("Reward") public val reward: RedeemedRewardInfo? = null,
)

@Serializable
public data class RedeemedRewardInfo(
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("RedeemedId") public val redeemedId: Long? = null,
    @SerialName("Title") public val title: String? = null,
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("Points") public val points: Long = 0,
)

/** Recompensa já resgatada e ainda não usada (a "carteira" de cupons do cliente). */
@Serializable
public data class RedeemedReward(
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("RedeemedId") public val redeemedId: Long = 0,
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("Id") public val rewardId: Long? = null,
    @SerialName("Type") public val typeCode: Int = -1,
    @SerialName("Title") public val title: String? = null,
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("Points") public val points: Long = 0,
    @SerialName("CouponCode") public val couponCode: String? = null,
    @SerialName("RedeemedValue") public val redeemedValue: Double? = null,
) {
    public val type: RewardType get() = RewardType.fromCode(typeCode)
}
