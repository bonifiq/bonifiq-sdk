package br.com.bonifiq.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test contra a API real com o tenant de testes (somente endpoints públicos, sem login).
 * Roda apenas com `BONIFIQ_SMOKE=1`.
 */
class PublicApiSmokeTest {

    @Test
    fun publicEndpointsRespond() = runBlocking {
        assumeTrue("defina BONIFIQ_SMOKE=1 para rodar", System.getenv("BONIFIQ_SMOKE") == "1")
        val bonifiq = BonifiQ(BonifiQConfig(tenant = "e2d54497-52b2-4f53-b882-c719189ab26f"))

        val config = bonifiq.program.configuration()
        val rewards = bonifiq.rewards.list()
        val terms = bonifiq.program.terms()
        val cashback = bonifiq.program.cashbackSettings()
        bonifiq.close()

        println("Programa: ${config.programName} cor=${config.color} cashback=${config.useCashback} pontos=${config.usePoints}")
        println("Recompensas: ${rewards.map { "${it.id}:${it.type}:${it.title}:${it.points}" }}")
        println("Cashback: $cashback · termos: ${terms.length} caracteres")
        assertTrue(rewards.isNotEmpty())
        assertTrue(terms.isNotBlank())
    }
}
