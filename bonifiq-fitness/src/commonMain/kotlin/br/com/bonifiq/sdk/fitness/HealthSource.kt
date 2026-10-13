package br.com.bonifiq.sdk.fitness

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** Ponte para a plataforma de saúde de cada sistema (Health Connect, HealthKit). */
internal interface HealthSource {
    val platform: HealthPlatform

    suspend fun availability(): FitnessAvailability

    fun openHealthAppInstall(): Boolean

    fun openHealthSettings(): Boolean

    suspend fun permissionStatus(): FitnessPermissionStatus

    /** Abre o pedido de permissão do sistema e devolve o estado depois da resposta do cliente. */
    suspend fun requestPermissions(): FitnessPermissionStatus

    /** Dias com passos entre [start] e [endInclusive]; os dias sem dado podem faltar. Sem permissão, lista vazia. */
    suspend fun dailySteps(start: LocalDate, endInclusive: LocalDate): List<DailySteps>

    /** Treinos que começaram entre [start] e [end]. Sem permissão, lista vazia. */
    suspend fun workouts(start: Instant, end: Instant): List<Workout>
}

internal expect fun platformHealthSource(): HealthSource
