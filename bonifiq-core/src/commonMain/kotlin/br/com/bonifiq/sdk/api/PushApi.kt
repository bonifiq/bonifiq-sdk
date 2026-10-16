package br.com.bonifiq.sdk.api

import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.BonifiQException
import br.com.bonifiq.sdk.BonifiQInternalApi
import br.com.bonifiq.sdk.internal.AuthMode
import br.com.bonifiq.sdk.internal.WidgetHttp
import br.com.bonifiq.sdk.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Aparelhos do cliente para notificações push (FCM). Quem chama é o módulo opcional `bonifiq-push`, que cuida do
 * ciclo de vida sozinho (registra no login, remove no logout); o app não usa estas funções direto.
 */
public class PushApi internal constructor(private val http: WidgetHttp) {

    /**
     * Associa o token FCM deste aparelho ao cliente logado. Exige sessão: a BonifiQ identifica o cliente pelo
     * SecureToken. Devolve `false` se a BonifiQ não registrou (ex.: push desligado na loja).
     *
     * @param platform `android` ou `ios`.
     */
    @BonifiQInternalApi
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun registerDevice(
        token: String,
        platform: String,
        appId: String?,
        appVersion: String?,
        locale: String?,
    ): Boolean = http.send<PushRegistration?>(HttpMethod.Post, "push/devices", AuthMode.Required) {
        jsonBody(buildJsonObject {
            put("Token", token)
            put("Platform", platform)
            put("AppId", appId)
            put("AppVersion", appVersion)
            put("SdkVersion", BonifiQ.VERSION)
            put("Locale", locale)
        })
    }?.registered ?: false

    /** Desassocia o token do cliente. Sem SecureToken: no logout ele pode já ter expirado; basta o tenant. */
    @BonifiQInternalApi
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun unregisterDevice(token: String) {
        http.sendIgnoringBody(HttpMethod.Post, "push/devices/unregister", AuthMode.None) {
            jsonBody(buildJsonObject { put("Token", token) })
        }
    }

    /** Registra que o cliente tocou na notificação ([communicationId] é o `bq_cid` da mensagem). Só o tenant. */
    @BonifiQInternalApi
    @Throws(BonifiQException::class, CancellationException::class)
    public suspend fun reportOpened(communicationId: String) {
        http.sendIgnoringBody(HttpMethod.Post, "push/opened", AuthMode.None) {
            jsonBody(buildJsonObject { put("CommunicationId", communicationId) })
        }
    }
}

@Serializable
internal data class PushRegistration(
    @SerialName("Registered") val registered: Boolean = false,
)
