package br.com.bonifiq.sdk.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.auth.AuthState
import br.com.bonifiq.sdk.ui.internal.attempt
import br.com.bonifiq.sdk.ui.internal.formatPoints

/**
 * Saldo compacto do cliente (ex.: "1.500 Coins") para top bars. Não aparece sem cliente logado.
 * Atualiza sozinho depois de resgates e uso de cashback.
 */
@Composable
public fun PointsBalanceChip(
    bonifiq: BonifiQ,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val auth by bonifiq.auth.state.collectAsState()
    val summary by bonifiq.customer.summary.collectAsState()
    if (auth !is AuthState.Authenticated) return

    LaunchedEffect(auth) {
        if (bonifiq.customer.summary.value == null) attempt { bonifiq.customer.info() }
    }

    BonifiQTheme(bonifiq) {
        AssistChip(
            onClick = onClick,
            modifier = modifier,
            label = { Text(summary?.pointsText ?: summary?.let { "${formatPoints(it.pointsBalance)} ${it.pointsName}" } ?: "…") },
            leadingIcon = {
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier)
            },
            colors = AssistChipDefaults.assistChipColors(),
        )
    }
}
