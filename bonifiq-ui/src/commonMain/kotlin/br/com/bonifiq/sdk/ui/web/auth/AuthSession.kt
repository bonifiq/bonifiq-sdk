package br.com.bonifiq.sdk.ui.web.auth

import androidx.compose.runtime.Composable

/**
 * Login em serviço de terceiros (ex.: Strava) pedido pelo widget web. O widget manda `openAuth`; o app abre o app do
 * provedor, se der, ou a tela de login do sistema por cima do app, e devolve a URL de retorno em `authResult`.
 * O código de autorização volta para o app e o widget o entrega ao backend da BonifiQ, que é quem tem o segredo do
 * provedor. Nunca passa por uma página web.
 */
internal sealed interface AuthSessionResult {
    data class Success(val url: String) : AuthSessionResult
    data object Cancelled : AuthSessionResult
    data class Failed(val message: String) : AuthSessionResult
}

/** Abre a sessão de login da plataforma. O resultado chega pelo [AuthSessionRegistry]. */
internal interface AuthSessionLauncher {
    /** Esquema do retorno, `bq.<id do app>`: só o app que abriu a sessão o recebe. */
    val callbackScheme: String

    /** [url] é o login web (https); [appUrl], quando houver, abre o app do provedor (iOS). */
    fun launch(url: String, appUrl: String?)
}

/** Launcher da plataforma, ou `null` se ela não consegue abrir a sessão e receber o retorno. */
@Composable
internal expect fun rememberAuthSessionLauncher(): AuthSessionLauncher?

private val CALLBACK_SCHEME = Regex("^bq\\.[a-z0-9]+(?:[.-][a-z0-9]+)*$")

/**
 * `bq.<appId>`, ou `null` se o id do app não vira um esquema seguro (ex.: tem `_` ou maiúsculas, que o Android
 * compara com diferença de caixa). Sem esquema, o widget segue no fluxo antigo, pelo navegador.
 */
internal fun callbackSchemeFor(appId: String): String? =
    "bq.$appId".takeIf { it.length <= 100 && CALLBACK_SCHEME.matches(it) }

private val BLOCKED_APP_SCHEMES = setOf("http", "https", "javascript", "data", "file", "content", "intent", "about", "blob")

/** `appUrl` só pode abrir outro app pelo esquema dele: nada de web, scripts, arquivos ou intents do Android. */
internal fun isSafeAppUrl(url: String, callbackScheme: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return Regex("^[a-z][a-z0-9+.-]*$").matches(scheme) &&
        scheme !in BLOCKED_APP_SCHEMES &&
        scheme != callbackScheme &&
        url.startsWith("$scheme://", ignoreCase = true)
}

/**
 * A sessão de login em andamento. Uma por vez: abrir outra cancela a anterior. Chamado só na main thread.
 */
internal object AuthSessionRegistry {
    private class Pending(val id: Long, val scheme: String, val onResult: (AuthSessionResult) -> Unit)

    private var pending: Pending? = null
    private var lastId = 0L

    /** Começa uma sessão e devolve o id dela. */
    fun start(callbackScheme: String, onResult: (AuthSessionResult) -> Unit): Long {
        finish(AuthSessionResult.Cancelled)
        val id = ++lastId
        pending = Pending(id, callbackScheme, onResult)
        return id
    }

    val isPending: Boolean get() = pending != null

    /** Id da sessão atual, ou 0 se não houver. */
    fun currentId(): Long = pending?.id ?: 0L

    /** Entrega a URL de retorno. Só aceita a do esquema esperado; devolve `true` se era o retorno da sessão. */
    fun complete(url: String): Boolean {
        val current = pending ?: return false
        if (!url.startsWith("${current.scheme}:", ignoreCase = true)) return false
        pending = null
        current.onResult(AuthSessionResult.Success(url))
        return true
    }

    /** Encerra a sessão atual com [result]. */
    fun finish(result: AuthSessionResult) {
        val current = pending ?: return
        pending = null
        current.onResult(result)
    }

    /** Encerra a sessão [id] se ela ainda for a atual (ex.: cancelamento atrasado). */
    fun finish(id: Long, result: AuthSessionResult) {
        if (pending?.id == id) finish(result)
    }
}

/**
 * Retorno de login de terceiros (ex.: Strava) para o hub web.
 *
 * Só é preciso no iOS, e só para abrir o **app** do provedor: registre o esquema `bq.<bundle id em minúsculas>` em
 * `CFBundleURLTypes` e repasse as URLs que o app recebe. Sem isso, o SDK usa a tela de login do sistema, que não
 * precisa de configuração. No Android o SDK já declara tudo no manifest.
 *
 * ```swift
 * .onOpenURL { url in _ = BonifiQAuthRedirect.shared.handle(url: url.absoluteString) }
 * ```
 */
public object BonifiQAuthRedirect {
    /** Entrega ao SDK uma URL aberta no app. Devolve `true` se era um retorno de login do SDK. */
    public fun handle(url: String): Boolean = AuthSessionRegistry.complete(url)
}
