package br.com.bonifiq.sdk.auth

import br.com.bonifiq.sdk.BonifiQException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Guarda o SecureToken (válido por 60 min) e o renova:
 * - **proativamente** a partir de [RENEW_AFTER_MS], se houver um [renewer] (login por plataforma, ex. VTEX);
 * - **após um 401**, uma única vez por token recusado.
 *
 * Sem [renewer] (login por código de e-mail), o token vale até expirar; depois o estado vira [AuthState.Expired].
 * O [Mutex] garante uma só renovação mesmo com várias chamadas concorrentes.
 */
internal class SecureTokenManager(private val clock: () -> Long) {

    private class Session(val login: SecureLogin, val obtainedAt: Long)

    private val mutex = Mutex()
    private var session: Session? = null
    private var renewer: (suspend () -> SecureLogin)? = null

    private val _state = MutableStateFlow<AuthState>(AuthState.Anonymous)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    suspend fun start(login: SecureLogin, renewer: (suspend () -> SecureLogin)?) = mutex.withLock {
        this.renewer = renewer
        session = Session(login, clock())
        _state.value = AuthState.Authenticated(login.identity)
    }

    /** Token atual se ainda estiver dentro da validade, sem renovar (para endpoints de autenticação opcional). */
    fun currentTokenOrNull(): String? =
        session?.takeIf { clock() - it.obtainedAt < TOKEN_TTL_MS }?.login?.secureToken

    /** Executa [block] com um token válido; se a BonifiQ recusar o token (401), renova e tenta mais uma vez. */
    suspend fun <T> withToken(block: suspend (token: String) -> T): T {
        val token = validToken()
        return try {
            block(token)
        } catch (e: BonifiQException.Unauthorized) {
            val renewed = renewAfterRejection(token) ?: throw e
            block(renewed)
        }
    }

    suspend fun validToken(): String = mutex.withLock {
        val current = session ?: throw BonifiQException.Unauthorized("Cliente não autenticado na BonifiQ.")
        val age = clock() - current.obtainedAt
        when {
            age < RENEW_AFTER_MS -> current.login.secureToken
            renewer != null -> renewLocked()
            age < TOKEN_TTL_MS -> current.login.secureToken
            else -> {
                expireLocked()
                throw BonifiQException.Unauthorized("A sessão BonifiQ expirou. Faça login novamente.")
            }
        }
    }

    private suspend fun renewAfterRejection(rejected: String): String? = mutex.withLock {
        val current = session ?: return null
        // Outra chamada concorrente já renovou: usa o token novo.
        if (current.login.secureToken != rejected) return current.login.secureToken
        if (renewer == null) {
            expireLocked()
            return null
        }
        renewLocked()
    }

    /**
     * Renova agora (o token atual foi recusado em outro lugar, ex.: pela página do widget no WebView).
     * Sem forma de renovar sozinho (login por código), expira a sessão e devolve `null`.
     */
    suspend fun forceRenew(): String? = mutex.withLock {
        if (session == null) return null
        if (renewer == null) {
            expireLocked()
            return null
        }
        renewLocked()
    }

    fun clear() {
        session = null
        renewer = null
        _state.value = AuthState.Anonymous
    }

    private suspend fun renewLocked(): String {
        val renew = renewer ?: throw BonifiQException.Unauthorized("Sessão BonifiQ sem renovação disponível.")
        val login = try {
            renew()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BonifiQException) {
            expireLocked()
            throw e
        } catch (e: Exception) {
            expireLocked()
            throw BonifiQException.Network(e)
        }
        session = Session(login, clock())
        _state.value = AuthState.Authenticated(login.identity)
        return login.secureToken
    }

    private fun expireLocked() {
        session = null
        renewer = null
        _state.value = AuthState.Expired
    }

    companion object {
        const val TOKEN_TTL_MS = 60 * 60 * 1000L
        const val RENEW_AFTER_MS = 55 * 60 * 1000L
    }
}
