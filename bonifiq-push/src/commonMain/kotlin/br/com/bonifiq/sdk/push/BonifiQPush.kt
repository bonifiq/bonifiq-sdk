package br.com.bonifiq.sdk.push

import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.BonifiQException
import br.com.bonifiq.sdk.BonifiQInternalApi
import br.com.bonifiq.sdk.auth.AuthState
import br.com.bonifiq.sdk.auth.CustomerIdentity
import br.com.bonifiq.sdk.model.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmStatic

/**
 * Módulo opcional de notificações push do programa de fidelidade (Firebase Cloud Messaging). Crie **uma instância
 * por app**, logo depois do [BonifiQ], e reutilize.
 *
 * O ciclo de vida é automático: com a loja com push ligado no painel BonifiQ, o aparelho é registrado quando o cliente
 * faz login e removido no logout ([br.com.bonifiq.sdk.auth.BonifiQAuth.logout]). Um token novo é registrado de novo.
 * A sessão expirada não remove o aparelho: ele continua do cliente até o logout.
 *
 * ```
 * val push = BonifiQPush(bonifiq)
 * push.requestPermission()                  // num momento que faça sentido para o cliente
 * push.opens.collect { abrirHub() }         // toque numa notificação da BonifiQ
 * ```
 *
 * - **Android:** o app configura o Firebase (`google-services.json`). O SDK lê o token, mostra a notificação com o app
 *   aberto e recebe o toque por `BonifiQPush.handleIntent(intent)` na Activity.
 * - **iOS:** o app usa o Firebase iOS SDK e repassa o token ([setToken]) e o toque ([handleNotification]).
 */
public class BonifiQPush internal constructor(
    private val bonifiq: BonifiQ,
    private val platform: PushPlatform,
    private val scope: CoroutineScope,
) {
    public constructor(bonifiq: BonifiQ) :
        this(bonifiq, platformPush(), CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val openEvents = Channel<PushOpen>(Channel.UNLIMITED)

    /**
     * Toques do cliente em notificações da BonifiQ. Leve-o ao hub do programa. Cada toque é entregue uma vez, mesmo se
     * aconteceu antes da coleta (ex.: o app aberto pela notificação); colete num lugar só.
     */
    public val opens: Flow<PushOpen> = openEvents.receiveAsFlow()

    /** Token registrado e o cliente dono dele, para não registrar de novo o mesmo par na sessão. */
    private data class Registration(val token: String, val customer: String?)

    // Só a coroutine de followSession lê e altera estes campos.
    private var registered: Registration? = null
    private var pushConfigApplied = false

    /** Último par (sessão, token) já tratado; os testes esperam por ele em [awaitIdle]. */
    private val processed = MutableStateFlow<Pair<AuthState, String?>?>(null)

    init {
        scope.launch { followSession() }
        PushRuntime.attach(this)
        platform.start()
    }

    /** Se o app pode mostrar notificações. Não mostra nada ao cliente. */
    @Throws(CancellationException::class)
    public suspend fun permissionStatus(): PushPermissionStatus = guarded { platform.permissionStatus() }

    /**
     * Pede ao cliente para mostrar notificações e devolve o resultado. Chame num momento que faça sentido para ele
     * (ex.: depois do login ou num botão "Ativar notificações").
     * - **Android 13 ou mais novo:** pede a permissão `POST_NOTIFICATIONS`. Antes do 13 não há pedido: responde
     *   conforme as notificações do app estejam ligadas nas configurações.
     * - **iOS:** pede a autorização (alerta, som e badge) e registra o app no APNs.
     *
     * Se já estiver liberado, não mostra nada. Depois de uma recusa, use [openNotificationSettings].
     */
    @Throws(CancellationException::class)
    public suspend fun requestPermission(): PushPermissionStatus = guarded { platform.requestPermission() }

    /** Abre as configurações de notificação do app, para o cliente liberar depois de recusar. */
    public fun openNotificationSettings(): Boolean = try {
        platform.openNotificationSettings()
    } catch (e: Exception) {
        pushLog("Não foi possível abrir as configurações de notificação: ${e.message}")
        false
    }

    /** Para de acompanhar o login. O aparelho continua registrado na BonifiQ. */
    public fun close() {
        PushRuntime.detach(this)
        scope.cancel()
        openEvents.close()
    }

    private suspend fun followSession() {
        combine(bonifiq.auth.state, PushRuntime.token) { auth, token -> auth to token }.collect { (auth, token) ->
            try {
                sync(auth, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Uma falha inesperada não pode encerrar o acompanhamento (nem o app, no Android).
                pushLog("Falha ao sincronizar o aparelho com a BonifiQ: ${e.message}")
            }
            processed.value = auth to token
        }
    }

    private suspend fun sync(auth: AuthState, token: String?) {
        val config = appConfig()
        when (auth) {
            is AuthState.Authenticated -> if (token != null && config?.pushEnabled == true) register(token, auth.customer)
            AuthState.Anonymous -> unregister()
            AuthState.Expired -> Unit // o aparelho continua do cliente até o logout
        }
    }

    /** Config da loja (fica em memória no core). Na primeira com push ligado, prepara a plataforma (canal etc.). */
    private suspend fun appConfig(): AppConfig? {
        val config = try {
            bonifiq.program.appConfig()
        } catch (e: BonifiQException) {
            pushLog("Não foi possível ler a config da loja: ${e.message}")
            return null
        }
        if (config.pushEnabled && !pushConfigApplied) {
            pushConfigApplied = true
            try {
                platform.onPushConfig(config)
            } catch (e: Exception) {
                pushLog("Não foi possível preparar as notificações: ${e.message}")
            }
        }
        return config
    }

    @OptIn(BonifiQInternalApi::class)
    private suspend fun register(token: String, customer: CustomerIdentity) {
        val platformName = platform.name ?: return
        val registration = Registration(token, customer.userId ?: customer.email)
        if (registration == registered) return
        try {
            val accepted = bonifiq.push.registerDevice(
                token = token,
                platform = platformName,
                appId = platform.appId,
                appVersion = platform.appVersion,
                locale = platform.locale,
            )
            if (accepted) registered = registration
        } catch (e: BonifiQException) {
            pushLog("Não foi possível registrar o aparelho para push: ${e.message}")
        }
    }

    @OptIn(BonifiQInternalApi::class)
    private suspend fun unregister() {
        val last = registered ?: return
        registered = null
        try {
            bonifiq.push.unregisterDevice(last.token)
        } catch (e: BonifiQException) {
            pushLog("Não foi possível remover o aparelho do push: ${e.message}")
        }
    }

    /** Toque numa notificação da BonifiQ: emite em [opens] e registra a abertura (sem SecureToken). */
    @OptIn(BonifiQInternalApi::class)
    internal fun deliverOpen(message: BonifiQMessage) {
        openEvents.trySend(message.open)
        val communicationId = message.communicationId ?: return
        scope.launch {
            try {
                bonifiq.push.reportOpened(communicationId)
            } catch (e: BonifiQException) {
                pushLog("Não foi possível registrar a abertura da notificação: ${e.message}")
            }
        }
    }

    /** Para os testes: espera o ciclo de vida tratar a sessão e o token atuais. */
    internal suspend fun awaitIdle() {
        processed.first { it == (bonifiq.auth.state.value to PushRuntime.token.value) }
    }

    private inline fun guarded(block: () -> PushPermissionStatus): PushPermissionStatus = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        pushLog("Não foi possível consultar a permissão de notificação: ${e.message}")
        PushPermissionStatus.Denied
    }

    public companion object {
        /**
         * Entrega ao SDK o token FCM do aparelho.
         * - **iOS:** obrigatório. Chame no `messaging(_:didReceiveRegistrationToken:)` do Firebase iOS SDK.
         * - **Android:** o SDK lê o token sozinho; só use (ou `onNewToken`) se o app tiver o próprio
         *   `FirebaseMessagingService`.
         *
         * Pode ser chamado antes de criar o [BonifiQPush]. Token vazio ou `null` é ignorado.
         */
        @JvmStatic
        public fun setToken(token: String?) {
            PushRuntime.setToken(token)
        }

        /**
         * Toque numa notificação. No iOS, repasse o `userInfo` do `userNotificationCenter(_:didReceive:)`.
         * Se a notificação é da BonifiQ, registra a abertura, emite em [opens] e devolve `true`; as outras devolvem
         * `false` e ficam com o app. Pode ser chamado antes de criar o [BonifiQPush]: o toque é entregue quando ele
         * for criado.
         */
        @JvmStatic
        public fun handleNotification(userInfo: Map<*, *>): Boolean {
            val message = BonifiQMessage.from { key -> userInfo[key]?.toString() } ?: return false
            PushRuntime.opened(message)
            return true
        }
    }
}
