package br.com.bonifiq.sdk.ui.internal

import kotlin.math.abs
import kotlin.math.roundToLong

/** 1234.5 → "R$ 1.234,50". */
internal fun formatMoney(value: Double): String {
    val cents = (value * 100).roundToLong()
    val absolute = abs(cents)
    val reais = groupThousands(absolute / 100)
    val centavos = (absolute % 100).toString().padStart(2, '0')
    return "${if (cents < 0) "-" else ""}R$ $reais,$centavos"
}

/** 1500 → "1.500". */
internal fun formatPoints(points: Long): String = (if (points < 0) "-" else "") + groupThousands(abs(points))

private fun groupThousands(value: Long): String = value.toString().reversed().chunked(3).joinToString(".").reversed()

/** "25,50" ou "25.50" → 25.5 */
internal fun parseMoney(text: String): Double? =
    text.trim().removePrefix("R$").trim().replace(".", "").replace(',', '.').toDoubleOrNull()

/** 25.5 → "25,50" (para preencher campos). */
internal fun moneyInput(value: Double): String = formatMoney(value).removePrefix("R$ ")
