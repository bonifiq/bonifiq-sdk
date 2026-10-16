package br.com.bonifiq.sdk.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Estado do processo, compartilhado entre as entradas estáticas ([BonifiQPush.setToken], o serviço do FCM, o toque na
 * notificação) e a instância de [BonifiQPush] do app. O token e o toque podem chegar antes de o app criar a instância
 * (ex.: o app aberto pela notificação): o toque fica guardado até ela existir.
 */
internal object PushRuntime {
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val active = MutableStateFlow<BonifiQPush?>(null)
    private val pendingOpens = MutableStateFlow<List<BonifiQMessage>>(emptyList())

    fun setToken(token: String?) {
        val value = token?.trim()
        if (!value.isNullOrEmpty()) _token.value = value
    }

    fun attach(push: BonifiQPush) {
        active.value = push
        deliverPending()
    }

    fun detach(push: BonifiQPush) {
        active.compareAndSet(push, null)
    }

    fun opened(message: BonifiQMessage) {
        pendingOpens.update { it + message }
        deliverPending()
    }

    /** Cada toque guardado vai uma vez só, mesmo com [attach] e [opened] ao mesmo tempo em threads diferentes. */
    private fun deliverPending() {
        val push = active.value ?: return
        pendingOpens.getAndUpdate { emptyList() }.forEach(push::deliverOpen)
    }

    /** Para os testes. */
    fun reset() {
        _token.value = null
        active.value = null
        pendingOpens.value = emptyList()
    }
}
