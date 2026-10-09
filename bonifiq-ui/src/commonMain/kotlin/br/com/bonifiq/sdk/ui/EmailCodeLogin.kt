package br.com.bonifiq.sdk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.auth.CustomerIdentity
import br.com.bonifiq.sdk.ui.internal.attempt
import br.com.bonifiq.sdk.ui.internal.fill
import br.com.bonifiq.sdk.ui.internal.userMessage
import kotlinx.coroutines.launch

/**
 * Login BonifiQ por código enviado ao e-mail — para lojas sem login de plataforma integrado.
 * Lojas VTEX normalmente usam `bonifiq.auth.loginWithVtex` direto, sem esta tela.
 */
@Composable
public fun EmailCodeLogin(
    bonifiq: BonifiQ,
    onLoggedIn: (CustomerIdentity) -> Unit,
    modifier: Modifier = Modifier,
) {
    BonifiQTheme(bonifiq) {
        val strings = LocalBonifiQStrings.current
        val scope = rememberCoroutineScope()
        var email by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var codeSent by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        fun run(action: suspend () -> Unit) {
            scope.launch {
                loading = true
                error = null
                attempt { action() }.onFailure { error = it.userMessage }
                loading = false
            }
        }

        Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(strings.emailLoginTitle, style = MaterialTheme.typography.headlineSmall)
            if (!codeSent) {
                Text(strings.emailLoginMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(strings.emailLabel) },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(strings.codeSentTo.fill(email.trim()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = code,
                    onValueChange = { text -> code = text.filter { it.isDigit() }.take(6) },
                    label = { Text(strings.codeLabel) },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    if (!codeSent) {
                        run {
                            bonifiq.auth.requestEmailCode(email)
                            codeSent = true
                        }
                    } else {
                        run { onLoggedIn(bonifiq.auth.loginWithEmailCode(email, code)) }
                    }
                },
                enabled = !loading && if (codeSent) code.length == 6 else email.contains('@'),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (codeSent) strings.confirmCode else strings.sendCode)
                }
            }
            if (codeSent) {
                TextButton(onClick = { codeSent = false; code = "" }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.changeEmail)
                }
            }
        }
    }
}
