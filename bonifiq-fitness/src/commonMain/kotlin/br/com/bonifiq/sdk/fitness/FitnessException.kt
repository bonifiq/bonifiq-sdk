package br.com.bonifiq.sdk.fitness

/** Erros do módulo Fitness. Toda chamada suspensa do módulo lança apenas estes tipos (ou cancelamento de coroutine). */
public sealed class FitnessException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Sem plataforma de saúde utilizável agora: confira [BonifiQFitness.availability] antes de pedir dados. */
    public class Unavailable(message: String) : FitnessException(message)

    /** A plataforma de saúde recusou ou falhou a consulta (ex.: limite de consultas do Health Connect). */
    public class Platform(message: String, cause: Throwable? = null) : FitnessException(message, cause)
}
