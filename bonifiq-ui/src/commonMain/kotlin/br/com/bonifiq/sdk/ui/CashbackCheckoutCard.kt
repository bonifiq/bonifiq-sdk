package br.com.bonifiq.sdk.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.auth.AuthState
import br.com.bonifiq.sdk.model.CashbackApplication
import br.com.bonifiq.sdk.model.CashbackOptions
import br.com.bonifiq.sdk.model.PurchaseEarnings
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
 * Como no checkout web da BonifiQ, quando o cliente não tem cashback para usar nesta compra, o card diz quanto ele
 * ganha de cashback ao concluí-la ou, abaixo do valor mínimo, quanto falta no carrinho para ganhar. Nada disso aparece
 * se a compra não gera cashback (ex.: sem o objetivo de compra ativo) nem quando há saldo para usar.
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
        var earnings by remember(cartId) { mutableStateOf<PurchaseEarnings?>(null) }
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
            // Em paralelo com as outras consultas: o ganho aparece junto com o saldo, sem a tela "pular".
            // Sem a estimativa, o card não mostra o ganho (nunca um "R$ 0,00").
            launch { earnings = attempt { bonifiq.checkout.purchaseEarnings(cartId, cartTotal) }.getOrNull() }
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
                        val hasBalance = current.balance > 0
                        if (hasBalance) {
                            Text(strings.cashbackBalance.fill(formatMoney(current.balance)), style = MaterialTheme.typography.bodyLarge)
                        }
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
                            // Sem cashback para usar nesta compra: os mesmos avisos do checkout web da BonifiQ.
                            when {
                                current.hasIneligibleProducts -> Text(strings.cashbackIneligible, style = MaterialTheme.typography.bodyMedium)
                                !hasBalance -> Text(strings.cashbackNoBalance, style = MaterialTheme.typography.bodyMedium)
                                else -> (current.requiredToAddMoreText ?: current.rules)?.let {
                                    Text(htmlToAnnotatedString(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            // Quanto a compra rende ou quanto falta para render. Com saldo para usar, o web não mostra:
                            // o ganho é do carrinho cheio e muda ao aplicar o cashback.
                            val earned = earnedCashback(earnings, cartTotal)
                            val missing = missingToEarnCashback(earnings, cartTotal)
                            when {
                                earned != null -> EarnHighlight(strings.cashbackWillEarn.fill(moneyNoBreak(earned)))
                                missing != null -> MissingToEarn(
                                    boldFill(strings.cashbackAddToEarn, moneyNoBreak(missing)),
                                    progress = earnings?.minCartValue?.takeIf { it > 0 }?.let { (cartTotal / it).toFloat() } ?: 0f,
                                )
                            }
                        }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Cashback que a compra gera, ou `null` quando não há o que mostrar (como o checkout web da BonifiQ). */
internal fun earnedCashback(earnings: PurchaseEarnings?, cartTotal: Double): Double? {
    val cashback = earnings?.cashback ?: return null
    if (!earnings.hasPurchaseObjective || cartTotal < earnings.minCartValue) return null
    return cashback.takeIf { round(it * 100) > 0 }
}

/** Quanto falta no carrinho para a compra gerar cashback, ou `null` se já alcançou o mínimo ou não há o que mostrar. */
internal fun missingToEarnCashback(earnings: PurchaseEarnings?, cartTotal: Double): Double? {
    if (earnings?.cashback == null || !earnings.hasPurchaseObjective) return null
    return (earnings.minCartValue - cartTotal).takeIf { round(it * 100) > 0 }
}

/** Valor em reais com espaço não separável: o "R$" não fica numa linha e o valor na outra. */
private fun moneyNoBreak(value: Double): String = formatMoney(value).replace(' ', '\u00A0')

/** [template] com o `%s` trocado por [value] em negrito, como o `<strong>` do checkout web. */
private fun boldFill(template: String, value: String): AnnotatedString = buildAnnotatedString {
    val at = template.indexOf("%s")
    if (at < 0) {
        append(template)
    } else {
        append(template.substring(0, at))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(value) }
        append(template.substring(at + 2))
    }
}

/** Quanto falta para ganhar cashback, com a barra de progresso do carrinho até o mínimo, como no checkout web. */
@Composable
private fun MissingToEarn(text: AnnotatedString, progress: Float) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { progress.coerceIn(0.01f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
        }
    }
}

/** Destaque verde do ganho, nas cores do alerta de sucesso do checkout web. */
@Composable
private fun EarnHighlight(text: String) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Surface(
        color = if (dark) Color(0xFF0C130D) else Color(0xFFEDF7ED),
        contentColor = if (dark) Color(0xFFCCE8CD) else Color(0xFF1E4620),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (dark) Color(0xFF66BB6A) else Color(0xFF2E7D32),
                modifier = Modifier.size(20.dp),
            )
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
        }
    }
}
