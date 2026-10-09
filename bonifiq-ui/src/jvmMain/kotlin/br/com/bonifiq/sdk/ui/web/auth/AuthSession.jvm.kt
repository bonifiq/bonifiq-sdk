package br.com.bonifiq.sdk.ui.web.auth

import androidx.compose.runtime.Composable

/** No desktop o hub é sempre o nativo: não há widget pedindo login de terceiros. */
@Composable
internal actual fun rememberAuthSessionLauncher(): AuthSessionLauncher? = null
