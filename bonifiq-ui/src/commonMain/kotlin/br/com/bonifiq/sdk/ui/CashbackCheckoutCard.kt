package br.com.bonifiq.sdk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.auth.AuthState
import br.com.bonifiq.sdk.model.CashbackApplication
import br.com.bonifiq.sdk.model.CashbackOptions
import br.com.bonifiq.sdk.ui.internal.attempt
import br.com.bonifiq.sdk.ui.internal.fill
import br.com.bonifiq.sdk.ui.internal.formatMoney
import br.com.bonifiq.sdk.ui.internal.htmlToAnnotatedString
import br.com.bonifiq.sdk.ui.internal.moneyInput
import br.com.bonifiq.sdk.ui.internal.parseMoney
import br.com.bonifiq.sdk.ui.internal.userMessage
import kotlinx.coroutines.launch
import kotlin.math.round

/** Como o cliente escolhe quanto cashback usar no [CashbackCheckoutCard]. */
public enum class CashbackAmount {
    /** Um toque usa todo o cashback disponível para a compra. */
    All,

    /** O cliente digita quanto quer usar, até o disponível. */
    Custom,
}

/**
 * Bloco de cashback para o checkout: mostra saldo e quanto pode ser usado no carrinho, aplica e remove o cashback.
 * Não aparece se o programa não usa cashback.
 *
 * Depois de aplicar ou remover, [onCartChanged] é chamado: recarregue o carrinho da plataforma (o desconto é
 * aplicado pela BonifiQ direto no carrinho). Se a BonifiQ devolver um cupom, ele chega em [onApplyCoupon].
 *
 * @param cartId identificador do carrinho na plataforma (na VTEX, o `orderFormId`).
 * @param cartTotal total atual do carrinho, em reais; o bloco se atualiza quando ele muda.
 * @param amount [CashbackAmount.All] (padrão) usa todo o disponível num toque; [CashbackAmount.Custom] deixa o cliente
 * escolher o valor.
 */
@Composable
public fun CashbackCheckoutCard(
    bonifiq: BonifiQ,
    cartId: String,
    cartTotal: Double,
    onCartChanged: () -> Unit,
    modifier: Modifier = Modifier,
    amount: CashbackAmount = CashbackAmount.All,
    onApplyCoupon: (String) -> Unit = {},
    onLoginRequired: () -> Unit = {},
) {
    BonifiQTheme(bonifiq) {
        val program = LocalBonifiQProgram.current
        if (program != null && !program.useCashback) return@BonifiQTheme

        val strings = LocalBonifiQStrings.current
        val scope = rememberCoroutineScope()
        val auth by bonifiq.auth.state.collectAsState()
        val authenticated = auth is AuthState.Authenticated

        var options by remember(cartId) { mutableStateOf<CashbackOptions?>(null) }
        var applied by remember(cartId) { mutableStateOf<CashbackApplication?>(null) }
        var input by remember(cartId) { mutableStateOf("") }
        // O campo acompanha o disponível até o cliente digitar; depois de aplicar ou remover, volta a acompanhar.
        var inputEdited by remember(cartId) { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var reload by remember { mutableIntStateOf(0) }

        LaunchedEffect(cartId, cartTotal, authenticated, reload) {
            if (!authenticated) return@LaunchedEffect
            attempt { bonifiq.checkout.cashbackOptions(cartId, cartTotal) }
                .onSuccess {
                    options = it
                    error = null
                    val typed = parseMoney(input)
                    if (!inputEdited || typed == null || typed > it.usable) input = moneyInput(it.usable)
                }
                .onFailure { error = it.userMessage }
            // Se a consulta falhar, fica o último estado conhecido: o "Remover" não pode sumir com o cashback aplicado.
            attempt { bonifiq.checkout.appliedCashback(cartId) }.onSuccess { applied = it }
        }

        fun run(action: suspend () -> Unit) {
            scope.launch {
                busy = true
                error = null
                message = null
                attempt { action() }.onFailure { error = it.userMessage }
                busy = false
                reload++
            }
        }

        fun apply(value: Double) = run {
            val result = bonifiq.checkout.applyCashback(cartId, value, cartTotal)
            if (result.success) {
                applied = result.takeIf { it.applied > 0 }
                inputEdited = false
                message = result.message
                result.couponCode?.let(onApplyCoupon)
                onCartChanged()
            } else {
                error = result.message ?: "Não foi possível usar o cashback."
            }
        }

        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(strings.cashbackTitle, program?.programName).joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }

                val current = options
                val currentApplied = applied
                when {
                    !authenticated -> {
                        Text(strings.cashbackLogin, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onLoginRequired) { Text(strings.loginAction) }
                    }
                    current == null && error == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    currentApplied != null -> {
                        Text(strings.cashbackApplied.fill(formatMoney(currentApplied.applied)), style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(
                            onClick = {
                                run {
                                    val removal = bonifiq.checkout.removeCashback(cartId, cartTotal)
                                    if (removal.success) {
                                        applied = null
                                        inputEdited = false
                                        message = removal.message
                                        onCartChanged()
                                    } else {
                                        error = removal.message ?: "Não foi possível remover o cashback."
                                    }
                                }
                            },
                            enabled = !busy,
                        ) { Text(strings.cashbackRemove) }
                    }
                    current != null -> {
                        Text(strings.cashbackBalance.fill(formatMoney(current.balance)), style = MaterialTheme.typography.bodyLarge)
                        if (current.canUse && current.usable > 0) {
                            Text(strings.cashbackUsable.fill(formatMoney(current.usable)), style = MaterialTheme.typography.bodyMedium)
                            when (amount) {
                                CashbackAmount.All -> Button(onClick = { apply(current.usable) }, enabled = !busy) {
                                    Text(strings.cashbackApplyAll.fill(formatMoney(current.usable)))
                                }
                                CashbackAmount.Custom -> {
                                    // Valor digitado, em centavos; vazio, zero ou acima do disponível não aplica.
                                    val typed = parseMoney(input)?.let { round(it * 100) / 100 }
                                    val overLimit = typed != null && typed > current.usable
                                    val valid = typed != null && typed > 0 && !overLimit
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = input,
                                            onValueChange = { text ->
                                                input = text.filter { it.isDigit() || it == ',' || it == '.' }
                                                inputEdited = true
                                            },
                                            label = { Text(strings.cashbackValueLabel) },
                                            prefix = { Text("R$ ") },
                                            singleLine = true,
                                            enabled = !busy,
                                            isError = overLimit,
                                            supportingText = if (overLimit) {
                                                { Text(strings.cashbackMax.fill(formatMoney(current.usable))) }
                                            } else {
                                                null
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            modifier = Modifier.weight(1f),
                                        )
                                        Button(onClick = { typed?.let(::apply) }, enabled = !busy && valid) {
                                            Text(strings.cashbackApply)
                                        }
                                    }
                                }
                            }
                        } else {
                            (current.requiredToAddMoreText ?: current.rules)?.let {
                                Text(htmlToAnnotatedString(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        current.willReceiveText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
