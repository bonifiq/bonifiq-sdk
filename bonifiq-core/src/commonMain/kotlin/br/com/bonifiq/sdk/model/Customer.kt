package br.com.bonifiq.sdk.model

import br.com.bonifiq.sdk.internal.FlexibleLongSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Resumo do cliente: saldo de pontos, texto formatado ("1.500 pontos") e nível atual. */
@Serializable
public data class CustomerInfo(
    @SerialName("Name") public val name: String? = null,
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("PointsBalance") public val pointsBalance: Long = 0,
    /** Saldo formatado com o nome dos pontos configurado no painel (ex.: "1.500 Coins"). */
    @SerialName("PointsText") public val pointsText: String? = null,
    @SerialName("CurrentTier") public val currentTier: Tier? = null,
    @SerialName("IsAffiliate") public val isAffiliate: Boolean = false,
    @SerialName("LastPointUpdateDate") public val lastPointUpdateDate: String? = null,
) {
    /** Nome dos pontos extraído de [pointsText] ("1.500 Coins" → "Coins"); "pontos" se não houver. */
    public val pointsName: String
        get() = pointsText?.trim()?.substringAfter(' ', missingDelimiterValue = "")?.takeIf { it.isNotBlank() } ?: "pontos"
}

@Serializable
public data class Tier(
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("Id") public val id: Long? = null,
    @SerialName("Name") public val name: String? = null,
    @SerialName("Color") public val color: String? = null,
)

@Serializable
internal data class PointsBalanceResponse(
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("PointsBalance") val pointsBalance: Long = 0,
)

/** Uma página do extrato de pontos. */
@Serializable
public data class PointsHistoryPage(
    @SerialName("Items") public val items: List<PointsTransaction> = emptyList(),
    @SerialName("TotalPages") public val totalPages: Int = 0,
    @SerialName("TotalItems") public val totalItems: Int = 0,
)

/** Movimentação no extrato: [points] positivo é crédito, negativo é débito. */
@Serializable
public data class PointsTransaction(
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("Id") public val id: Long = 0,
    @SerialName("Type") public val type: Int? = null,
    @SerialName("Title") public val title: String? = null,
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("Points") public val points: Long = 0,
    @SerialName("Value") public val value: Double? = null,
    @SerialName("Date") public val date: String? = null,
    @SerialName("OrderState") public val orderState: Int? = null,
    @SerialName("CancelledDate") public val cancelledDate: String? = null,
    @SerialName("OriginalId") public val originalId: String? = null,
    @SerialName("CouponCode") public val couponCode: String? = null,
    @SerialName("IsCanceled") public val isCanceled: Boolean = false,
)

/** Lote de pontos que vai expirar em [date]. */
@Serializable
public data class ExpiringPoints(
    @Serializable(with = FlexibleLongSerializer::class)
    @SerialName("Value") public val points: Long = 0,
    @SerialName("Date") public val date: String? = null,
)
