package br.com.bonifiq.sdk.fitness

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class BonifiQFitnessTest {

    private val now = Instant.parse("2026-10-13T15:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    private fun fitness(source: FakeHealthSource) = BonifiQFitness(source, clock) { TimeZone.of("America/Sao_Paulo") }

    @Test
    fun dailyStepsFillsMissingDaysInOrder() = runTest {
        val source = FakeHealthSource(
            steps = listOf(
                DailySteps(LocalDate(2026, 10, 13), 4200, listOf(samsung)),
                DailySteps(LocalDate(2026, 10, 11), 9000, listOf(samsung)),
            ),
        )

        val days = fitness(source).dailySteps(lastDays = 3)

        assertEquals(listOf(LocalDate(2026, 10, 11), LocalDate(2026, 10, 12), LocalDate(2026, 10, 13)), days.map { it.date })
        assertEquals(listOf(9000L, 0L, 4200L), days.map { it.count })
        assertEquals(LocalDate(2026, 10, 11), source.lastStepsRange?.first)
        assertEquals(LocalDate(2026, 10, 13), source.lastStepsRange?.second)
    }

    @Test
    fun workoutsComeNewestFirstAndInsideTheRange() = runTest {
        val old = workout("antigo", now - 40.hours)
        val recent = workout("recente", now - 1.hours)
        val beforeRange = workout("fora", now - 400.hours)
        val source = FakeHealthSource(workouts = listOf(old, beforeRange, recent))

        val result = fitness(source).workouts(lastDays = 7)

        assertEquals(listOf("recente", "antigo"), result.map { it.id })
        // Últimos 7 dias contam a partir da meia-noite local (São Paulo, UTC-3) de 7/10.
        assertEquals(Instant.parse("2026-10-07T03:00:00Z"), source.lastWorkoutsRange?.first)
        assertEquals(now, source.lastWorkoutsRange?.second)
        assertEquals(60.minutes, recent.duration)
    }

    @Test
    fun readingWithoutHealthPlatformFails() = runTest {
        val fitness = fitness(FakeHealthSource(availability = FitnessAvailability.NeedsInstall))

        assertEquals(FitnessAvailability.NeedsInstall, fitness.availability())
        assertFailsWith<FitnessException.Unavailable> { fitness.dailySteps(lastDays = 7) }
        assertFailsWith<FitnessException.Unavailable> { fitness.requestPermissions() }
    }

    @Test
    fun platformErrorsBecomeFitnessException() = runTest {
        val fitness = fitness(FakeHealthSource(failure = IllegalStateException("rate limited")))

        val error = assertFailsWith<FitnessException.Platform> { fitness.workouts(lastDays = 30) }
        assertEquals("rate limited", error.message)
    }

    @Test
    fun availabilityFailureMeansUnsupported() = runTest {
        val fitness = fitness(FakeHealthSource(availabilityFailure = IllegalStateException("sem contexto")))

        assertEquals(FitnessAvailability.Unsupported, fitness.availability())
    }

    @Test
    fun invalidRangesReturnNothing() = runTest {
        val source = FakeHealthSource()
        val fitness = fitness(source)

        assertTrue(fitness.dailySteps(LocalDate(2026, 10, 13), LocalDate(2026, 10, 1)).isEmpty())
        assertTrue(fitness.workouts(now, now - 1.hours).isEmpty())
        assertEquals(null, source.lastStepsRange)
    }

    private val samsung = FitnessSource("com.sec.android.app.shealth", "Samsung Health", null)

    private fun workout(id: String, start: Instant) = Workout(
        id = id,
        type = WorkoutType.Running,
        title = null,
        start = start,
        end = start + 60.minutes,
        distanceMeters = 8000.0,
        activeCaloriesKcal = 520.0,
        heartRate = HeartRateSummary(averageBpm = 148, minBpm = 95, maxBpm = 172),
        hasRoute = true,
        source = FitnessSource(
            "com.sec.android.app.shealth",
            "Samsung Health",
            FitnessDevice(DeviceType.Watch, "Samsung", "Galaxy Watch6"),
        ),
        recordingMethod = RecordingMethod.ActivelyRecorded,
    )
}

internal class FakeHealthSource(
    var availability: FitnessAvailability = FitnessAvailability.Available,
    private val availabilityFailure: Exception? = null,
    var steps: List<DailySteps> = emptyList(),
    var workouts: List<Workout> = emptyList(),
    private val failure: Exception? = null,
    var permission: FitnessPermissionStatus = FitnessPermissionStatus.Granted,
    /** Resposta do cliente ao pedido de permissão. */
    var grantOnRequest: FitnessPermissionStatus = FitnessPermissionStatus.Granted,
    var installedApps: List<HealthApp> = emptyList(),
    override val platform: HealthPlatform = HealthPlatform.HealthConnect,
) : HealthSource {
    var lastStepsRange: Pair<LocalDate, LocalDate>? = null
    var lastWorkoutsRange: Pair<Instant, Instant>? = null
    val opened = mutableListOf<String>()

    override suspend fun availability(): FitnessAvailability {
        availabilityFailure?.let { throw it }
        return availability
    }

    override fun openHealthAppInstall(): Boolean = opened.add("install")
    override fun openHealthSettings(): Boolean = opened.add("settings")
    override fun installedHealthApps(): List<HealthApp> = installedApps
    override fun openHealthApp(appId: String): Boolean = opened.add(appId)
    override suspend fun permissionStatus(): FitnessPermissionStatus = permission
    override suspend fun requestPermissions(): FitnessPermissionStatus {
        permission = grantOnRequest
        return permission
    }

    override suspend fun dailySteps(start: LocalDate, endInclusive: LocalDate): List<DailySteps> {
        failure?.let { throw it }
        lastStepsRange = start to endInclusive
        return steps
    }

    override suspend fun workouts(start: Instant, end: Instant): List<Workout> {
        failure?.let { throw it }
        lastWorkoutsRange = start to end
        return workouts
    }
}
