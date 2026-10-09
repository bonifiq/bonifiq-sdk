package br.com.bonifiq.sdk.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Estado da sessão do cliente na BonifiQ. */
public sealed interface AuthState {
    /** Nenhum cliente logado. Endpoints públicos continuam funcionando. */
    public data object Anonymous : AuthState

    /** Cliente autenticado; o SDK cuida do SecureToken (validade de 60 min) por conta própria. */
    public data class Authenticated(public val customer: CustomerIdentity) : AuthState

    /** A sessão expirou e não pôde ser renovada sozinha (ex.: login por código). Peça login novamente. */
    public data object Expired : AuthState
}

/** Identificação do cliente devolvida pelo login seguro. */
public data class CustomerIdentity(
    public val email: String?,
    public val name: String?,
    public val userId: String?,
)

/** Tokens da sessão VTEX do cliente logado (Session Manager: `POST /api/sessions`). */
public data class VtexSessionTokens(
    public val sessionToken: String,
    public val segmentToken: String,
)

@Serializable
internal data class SecureLogin(
    @SerialName("Email") val email: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("UserId") val userId: String? = null,
    @SerialName("SecureToken") val secureToken: String,
) {
    val identity: CustomerIdentity get() = CustomerIdentity(email, name, userId)
}
