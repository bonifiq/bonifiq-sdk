package br.com.bonifiq.sdk.push

import br.com.bonifiq.sdk.BonifiQ
import br.com.bonifiq.sdk.BonifiQException
import br.com.bonifiq.sdk.BonifiQInternalApi
import br.com.bonifiq.sdk.auth.AuthState
import br.com.bonifiq.sdk.auth.CustomerIdentity
import br.com.bonifiq.sdk.model.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Módulo opcional de notificações push do programa de fidelidade (Firebase Cloud Messaging). Crie **uma instância
 * por app**, logo depois do [BonifiQ], e reutilize.
 *
 * O ciclo de vida é automático: com a loja com push ligado no painel BonifiQ, o aparelho é registrado quando o cliente
 * faz login e removido no logout ([br.com.bonifiq.sdk.auth.BonifiQAuth.logout]), inclusive quando o app foi reaberto e
 * o SDK ainda não tinha logado. Um token novo é registrado de novo. A sessão expirada não remove o aparelho: ele
 * continua do cliente até o logout.
 *
 * Falhas passageiras (sem rede, timeout, erro do servidor) no registro e na remoção são repetidas sozinhas, com espera
 * crescente. A remoção pendente fica guardada no aparelho e é retomada na próxima abertura do app.
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
    /** Esperas entre as novas tentativas depois de falhas passageiras; a última se repete. */
    private val retryDelays: List<Duration> = RETRY_DELAYS,
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

    /** Sessão, token e quantos logouts o app já fez: cada mudança é tratada por [sync]. */
    private data class Snapshot(val auth: AuthState, val token: String?, val logouts: Int)

    // Só a coroutine de followSession lê e altera estes campos (um bloco por vez).
    private var registered: Registration? = null
    private var pushConfigApplied = false
    private var handledLogouts = 0

    /** Logout antes de o token chegar: ele é removido quando chegar, se ninguém logar antes. */
    private var removeTokenWhenKnown = false

    /** Último estado já tratado; os testes esperam por ele em [awaitIdle]. */
    private val processed = MutableStateFlow<Snapshot?>(null)

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

    @OptIn(BonifiQInternalApi::class)
    private suspend fun followSession() {
        // collectLatest: um novo login, logout ou token cancela as novas tentativas do estado anterior. As chamadas à
        // BonifiQ já em andamento terminam antes (ver [retrying]), para um registro e uma remoção não se cruzarem.
        combine(bonifiq.auth.state, PushRuntime.token, bonifiq.auth.logouts) { auth, token, logouts ->
            Snapshot(auth, token, logouts)
        }.collectLatest { snapshot ->
            try {
                sync(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Uma falha inesperada não pode encerrar o acompanhamento (nem o app, no Android).
                pushLog("Falha ao sincronizar o aparelho com a BonifiQ: ${e.message}")
            }
            processed.value = snapshot
        }
    }

    private suspend fun sync(snapshot: Snapshot) {
        val (auth, token, logouts) = snapshot
        if (platform.name == null) return // desktop: sem push
        if (logouts > handledLogouts) {
            handledLogouts = logouts
            onLogout(token)
        }
        if (auth is AuthState.Authenticated) {
            removeTokenWhenKnown = false
        } else if (removeTokenWhenKnown && token != null) {
            removeTokenWhenKnown = false
            platform.pendingRemoval = token
        }
        when (auth) {
            is AuthState.Authenticated -> {
                val config = appConfig(retry = true)
                val pushOn = config?.pushEnabled == true
                // Novo login com o mesmo token: o registro passa o aparelho para o cliente atual, e uma remoção antiga
                // chegando depois desfaria isso.
                if (pushOn && token != null && platform.pendingRemoval == token) platform.pendingRemoval = null
                // Daqui em diante a remoção é de outro token: uma não espera a outra.
                coroutineScope {
                    launch { removePending(config) }
                    if (pushOn && token != null) register(token, auth.customer)
                }
            }
            // Sem cliente, ou sessão expirada (que não é logout): só termina uma remoção pendente.
            else -> removePending(appConfig(retry = false))
        }
    }

    /**
     * Logout do app: o aparelho deixa de ser do cliente. Vale também quando o SDK não sabe se o aparelho foi
     * registrado (ex.: o app reaberto, sem login nesta execução): remove o token atual.
     */
    private fun onLogout(token: String?) {
        val target = registered?.token ?: token
        registered = null
        if (target != null) platform.pendingRemoval = target else removeTokenWhenKnown = true
    }

    /**
     * Config da loja (fica em memória no core). Na primeira com push ligado, prepara a plataforma (canal etc.).
     * Com [retry], repete falhas passageiras: o registro depende dela.
     */
    private suspend fun appConfig(retry: Boolean): AppConfig? {
        val failure = "Não foi possível ler a config da loja"
        val config = if (retry) {
            retrying(failure) { bonifiq.program.appConfig() }
        } else {
            try {
                bonifiq.program.appConfig()
            } catch (e: BonifiQException) {
                pushLog("$failure: ${e.message}")
                null
            }
        } ?: return null
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
        val accepted = retrying("Não foi possível registrar o aparelho para push") {
            bonifiq.push.registerDevice(
                token = token,
                platform = platformName,
                appId = platform.appId,
                appVersion = platform.appVersion,
                locale = platform.locale,
            )
        }
        if (accepted == true) registered = registration
    }

    /** Termina a remoção pendente (guardada no aparelho), repetindo enquanto a falha for passageira. */
    @OptIn(BonifiQInternalApi::class)
    private suspend fun removePending(config: AppConfig?) {
        val token = platform.pendingRemoval ?: return
        // Loja com o push desligado não manda notificações: não há o que remover.
        if (config?.pushEnabled == false) {
            platform.pendingRemoval = null
            return
        }
        retrying("Não foi possível remover o aparelho do push") { bonifiq.push.unregisterDevice(token) }
        // Removido, ou recusado de vez (ex.: 4xx), quando não adianta repetir.
        if (platform.pendingRemoval == token) platform.pendingRemoval = null
    }

    /**
     * Executa [block] e repete com espera crescente ([retryDelays]) enquanto a falha for passageira. Devolve `null` se
     * a BonifiQ recusar de vez. Um novo estado cancela só a espera: a chamada em andamento termina antes, para o
     * servidor não receber um registro e uma remoção fora de ordem.
     */
    private suspend fun <T> retrying(failure: String, block: suspend () -> T): T? {
        var attempt = 0
        while (true) {
            try {
                return withContext(NonCancellable) { block() }
            } catch (e: BonifiQException) {
                if (!e.isTransient) {
                    pushLog("$failure: ${e.message}")
                    return null
                }
                val wait = retryDelays[minOf(attempt++, retryDelays.lastIndex)]
                pushLog("$failure: ${e.message} Nova tentativa em $wait.")
                delay(wait)
            }
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

    /** Para os testes: espera o ciclo de vida tratar a sessão, o token e os logouts atuais. */
    @OptIn(BonifiQInternalApi::class)
    internal suspend fun awaitIdle() {
        processed.first {
            it == Snapshot(bonifiq.auth.state.value, PushRuntime.token.value, bonifiq.auth.logouts.value)
        }
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

/** Esperas entre as novas tentativas: rápidas no começo (rede oscilando), depois a cada 15 min. */
private val RETRY_DELAYS = listOf(5.seconds, 15.seconds, 1.minutes, 5.minutes, 15.minutes)

/** Sem rede, timeout, erro do servidor ou limite de requisições: vale tentar de novo. */
private val BonifiQException.isTransient: Boolean
    get() = this is BonifiQException.Network ||
        (this is BonifiQException.Api && (httpStatus >= 500 || httpStatus == 408 || httpStatus == 429))
