package br.com.bonifiq.sdk.fitness

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

internal actual fun platformHealthSource(): HealthSource = UnsupportedHealthSource

/** Desktop: sem plataforma de saúde. */
internal object UnsupportedHealthSource : HealthSource {
    override val platform: HealthPlatform = HealthPlatform.None
    override suspend fun availability(): FitnessAvailability = FitnessAvailability.Unsupported
    override fun openHealthAppInstall(): Boolean = false
    override fun openHealthSettings(): Boolean = false
    override suspend fun permissionStatus(): FitnessPermissionStatus = FitnessPermissionStatus.NotGranted
    override suspend fun requestPermissions(): FitnessPermissionStatus = FitnessPermissionStatus.NotGranted
    override suspend fun dailySteps(start: LocalDate, endInclusive: LocalDate): List<DailySteps> = emptyList()
    override suspend fun workouts(start: Instant, end: Instant): List<Workout> = emptyList()
}
