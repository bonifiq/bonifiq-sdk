package br.com.bonifiq.sdk.fitness

/** Preferências locais do módulo (ex.: o cliente já ligou o envio no app do relógio). */
internal interface FitnessStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

internal class InMemoryFitnessStore : FitnessStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

internal expect fun platformFitnessStore(): FitnessStore
