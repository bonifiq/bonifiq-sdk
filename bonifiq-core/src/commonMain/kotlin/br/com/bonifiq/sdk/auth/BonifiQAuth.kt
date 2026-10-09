package br.com.bonifiq.sdk.auth

import br.com.bonifiq.sdk.BonifiQException
import br.com.bonifiq.sdk.BonifiQInternalApi
import br.com.bonifiq.sdk.internal.AuthMode
import br.com.bonifiq.sdk.internal.WidgetHttp
import br.com.bonifiq.sdk.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Login seguro do cliente na BonifiQ. Gera o SecureToken que o SDK usa nos endpoints autenticados.
 *
 * - [loginWithVtex]: para lojas VTEX com o cliente já logado no app. O SDK guarda o provedor de tokens e
 *   renova a sessão sozinho, sem interação.
 * - [requestEmailCode] + [loginWithEmailCode]: método universal por código de 6 dígitos enviado por e-mail.
 */
public class BonifiQAuth internal constructor(
    private val http: WidgetHttp,
    private val tokens: SecureTokenManager,
    private val onSessionCleared: () -> Unit,
) {
    public val state: StateFlow<AuthState> get() = tokens.state

    public val isAuthenticated: Boolean get() = tokens.state.value is AuthState.Authenticated

    /**
     * Autentica com a sessão VTEX do cliente. [sessionTokens] é chamado agora e de novo a cada renovação
     * (o SecureToken vale 60 min), então deve sempre devolver tokens da sessão VTEX atual.
     */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun loginWithVtex(sessionTokens: suspend () -> VtexSessionTokens): CustomerIdentity {
        val renewer: suspend () -> SecureLogin = {
            // O provedor é código do app: uma falha dele também vira BonifiQException, como o SDK promete.
            val vtex = try {
                sessionTokens()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BonifiQException) {
                throw e
            } catch (e: Exception) {
                throw BonifiQException.Network(e)
            }
            http.send<SecureLogin>(HttpMethod.Post, "vendors/vtex/securelogin", AuthMode.None) {
                jsonBody(buildJsonObject {
                    put("sessionToken", vtex.sessionToken)
                    put("segmentToken", vtex.segmentToken)
                })
            }
        }
        val login = renewer()
        tokens.start(login, renewer)
        return login.identity
    }

    /** Envia um código de 6 dígitos para o e-mail do cliente (expira em 10 min). */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun requestEmailCode(email: String) {
        http.sendIgnoringBody(HttpMethod.Post, "account/challenge", AuthMode.None) {
            jsonBody(buildJsonObject { put("email", email.trim()) })
        }
    }

    /** Valida o código recebido por e-mail. Esta sessão não é renovada sozinha: após 60 min vira [AuthState.Expired]. */
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun loginWithEmailCode(email: String, code: String): CustomerIdentity {
        val login = http.send<SecureLogin>(HttpMethod.Post, "account/validatechallenge", AuthMode.None) {
            jsonBody(buildJsonObject {
                put("email", email.trim())
                put("code", code.trim())
            })
        }.let { it.copy(email = it.email ?: email.trim()) } // o validatechallenge não devolve o e-mail
        tokens.start(login, renewer = null)
        return login.identity
    }

    /** SecureToken válido do cliente logado (renova se preciso), para entregar ao widget web no WebView. */
    @BonifiQInternalApi
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun secureTokenForEmbed(): String = tokens.validToken()

    /** Renova o SecureToken a pedido do widget web (`tokenExpired`). `null` se a sessão não puder ser renovada. */
    @BonifiQInternalApi
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun renewSecureTokenForEmbed(): String? = tokens.forceRenew()

    /** Encerra a sessão BonifiQ no app (o SecureToken é descartado). */
    public fun logout() {
        tokens.clear()
        onSessionCleared()
    }
}
