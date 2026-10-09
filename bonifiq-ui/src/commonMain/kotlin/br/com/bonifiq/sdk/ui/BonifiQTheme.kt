package br.com.bonifiq.sdk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.BonifiQException
import br.com.bonifiq.sdk.model.ProgramConfiguration
import kotlinx.coroutines.CancellationException

/** Configuração do programa disponível para os componentes (nome, cor, mecânicas). `null` enquanto carrega. */
public val LocalBonifiQProgram: androidx.compose.runtime.ProvidableCompositionLocal<ProgramConfiguration?> =
    staticCompositionLocalOf { null }

/**
 * Aplica a cor do programa (configurada no painel BonifiQ) como cor primária, mantendo o restante do tema do app.
 * Todos os componentes do SDK já usam este tema; use-o diretamente só se for montar telas próprias.
 */
@Composable
public fun BonifiQTheme(bonifiq: BonifiQ, content: @Composable () -> Unit) {
    val program by produceState<ProgramConfiguration?>(initialValue = null, bonifiq) {
        value = try {
            bonifiq.program.configuration()
        } catch (e: CancellationException) {
            throw e
        } catch (_: BonifiQException) {
            null
        }
    }
    val base = MaterialTheme.colorScheme
    val accent = program?.color?.let(::parseHexColor)
    val colors = if (accent == null) {
        base
    } else {
        val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
        base.copy(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.14f).compositeOver(base.surface),
            onPrimaryContainer = base.onSurface,
        )
    }
    CompositionLocalProvider(LocalBonifiQProgram provides program) {
        MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
    }
}

internal fun parseHexColor(hex: String): Color? {
    val digits = hex.trim().removePrefix("#")
    val argb = when (digits.length) {
        6 -> digits.toLongOrNull(16)?.let { 0xFF000000L or it }
        8 -> digits.toLongOrNull(16)
        else -> null
    } ?: return null
    return Color(argb.toInt())
}
