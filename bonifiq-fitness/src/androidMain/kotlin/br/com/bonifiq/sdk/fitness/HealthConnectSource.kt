package br.com.bonifiq.sdk.fitness

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import java.time.Period
import kotlin.time.Instant

/**
 * Health Connect (Android 9+). Os dados de Samsung Health, Fitbit, Mi Fitness, Zepp e Garmin chegam aqui quando o
 * cliente liga a sincronização no app de cada um.
 */
internal class HealthConnectSource(contextProvider: () -> Context) : HealthSource {

    override val platform: HealthPlatform = HealthPlatform.HealthConnect

    // Lido só no uso: sem a inicialização do módulo, availability() responde Unsupported em vez de quebrar o construtor.
    private val context by lazy(contextProvider)
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    override suspend fun availability(): FitnessAvailability = when (sdkStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> FitnessAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> FitnessAvailability.NeedsInstall
        else -> FitnessAvailability.Unsupported
    }

    private fun sdkStatus(): Int =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            HealthConnectClient.SDK_UNAVAILABLE
        } else {
            HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)
        }

    override fun openHealthAppInstall(): Boolean {
        if (sdkStatus() != HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) return false
        // Abre a Play Store e, depois de instalar, o onboarding do Health Connect.
        val market = Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
        return start(Intent(Intent.ACTION_VIEW, market).setPackage("com.android.vending")) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PROVIDER_PACKAGE")))
    }

    override fun openHealthSettings(): Boolean {
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return false
        return start(HealthConnectClient.getHealthConnectManageDataIntent(context, PROVIDER_PACKAGE)) ||
            start(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
    }

    override fun installedHealthApps(): List<HealthApp> = HealthAppCatalog.apps.filter { isInstalled(it.appId) }

    override fun openHealthApp(appId: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(appId) ?: return false
        return start(launch)
    }

    /** Os pacotes do catálogo estão em <queries> no manifest do módulo (visibilidade de pacotes, Android 11+). */
    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun start(intent: Intent): Boolean = try {
        FitnessAndroid.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    override suspend fun permissionStatus(): FitnessPermissionStatus = statusOf(granted())

    override suspend fun requestPermissions(): FitnessPermissionStatus {
        if (granted().containsAll(READ_PERMISSIONS)) return FitnessPermissionStatus.Granted
        HealthPermissionRequest.request(READ_PERMISSIONS)
        // O resultado do contrato pode vir incompleto (ex.: tela recriada); a fonte de verdade é o Health Connect.
        return statusOf(granted())
    }

    private suspend fun granted(): Set<String> = client.permissionController.getGrantedPermissions()

    private fun statusOf(granted: Set<String>): FitnessPermissionStatus {
        val count = READ_PERMISSIONS.count { it in granted }
        return when (count) {
            READ_PERMISSIONS.size -> FitnessPermissionStatus.Granted
            0 -> FitnessPermissionStatus.NotGranted
            else -> FitnessPermissionStatus.Partial
        }
    }

    override suspend fun dailySteps(start: LocalDate, endInclusive: LocalDate): List<DailySteps> {
        if (STEPS !in granted()) return emptyList()
        // Agregação por dia em hora local: o Health Connect remove a contagem em dobro entre apps (prioridade de fontes).
        val request = AggregateGroupByPeriodRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(
                start.toJavaLocalDate().atStartOfDay(),
                endInclusive.toJavaLocalDate().plusDays(1).atStartOfDay(),
            ),
            timeRangeSlicer = Period.ofDays(1),
        )
        return client.aggregateGroupByPeriod(request).map { group ->
            DailySteps(
                date = group.startTime.toLocalDate().toKotlinLocalDate(),
                count = group.result[StepsRecord.COUNT_TOTAL] ?: 0,
                sources = group.result.dataOrigins.map { sourceOf(it.packageName, device = null) },
            )
        }
    }

    override suspend fun workouts(start: Instant, end: Instant): List<Workout> {
        val granted = granted()
        if (EXERCISE !in granted) return emptyList()

        val sessions = mutableListOf<ExerciseSessionRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start.toJavaInstant(), end.toJavaInstant()),
                    ascendingOrder = false,
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                ),
            )
            sessions += response.records
            pageToken = response.pageToken
        } while (pageToken != null && sessions.size < MAX_WORKOUTS)

        val metrics = buildSet<AggregateMetric<*>> {
            if (DISTANCE in granted) add(DistanceRecord.DISTANCE_TOTAL)
            if (CALORIES in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (HEART_RATE in granted) {
                add(HeartRateRecord.BPM_AVG)
                add(HeartRateRecord.BPM_MIN)
                add(HeartRateRecord.BPM_MAX)
            }
        }
        return sessions.take(MAX_WORKOUTS).map { session -> workoutOf(session, metrics) }
    }

    private suspend fun workoutOf(session: ExerciseSessionRecord, metrics: Set<AggregateMetric<*>>): Workout {
        // Distância, calorias e batimentos do mesmo app que gravou o treino, no intervalo do treino.
        val totals = if (metrics.isEmpty()) {
            null
        } else {
            client.aggregate(
                AggregateRequest(
                    metrics = metrics,
                    timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime),
                    dataOriginFilter = setOf(session.metadata.dataOrigin),
                ),
            )
        }
        val avg = totals?.get(HeartRateRecord.BPM_AVG)
        val min = totals?.get(HeartRateRecord.BPM_MIN)
        val max = totals?.get(HeartRateRecord.BPM_MAX)
        return Workout(
            id = session.metadata.id,
            type = workoutTypeOf(session.exerciseType),
            title = session.title?.takeIf { it.isNotBlank() },
            start = session.startTime.toKotlinInstant(),
            end = session.endTime.toKotlinInstant(),
            distanceMeters = totals?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            activeCaloriesKcal = totals?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
            heartRate = if (avg != null && min != null && max != null) {
                HeartRateSummary(averageBpm = avg.toInt(), minBpm = min.toInt(), maxBpm = max.toInt())
            } else {
                null
            },
            // Sem a permissão de trajeto o Health Connect não entrega os pontos, mas diz se eles existem.
            hasRoute = session.exerciseRouteResult !is ExerciseRouteResult.NoData,
            source = sourceOf(session.metadata.dataOrigin.packageName, session.metadata.device),
            recordingMethod = recordingMethodOf(session.metadata.recordingMethod),
        )
    }

    private fun sourceOf(packageName: String, device: Device?): FitnessSource = FitnessSource(
        appId = packageName,
        appName = appLabel(packageName) ?: HealthAppCatalog.nameOf(packageName),
        device = device?.let {
            FitnessDevice(type = deviceTypeOf(it.type), manufacturer = it.manufacturer, model = it.model)
        },
    )

    private fun appLabel(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null // Android 11+: só vemos os pacotes declarados em <queries>
    }

    internal companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val PAGE_SIZE = 100
        private const val MAX_WORKOUTS = 200

        val STEPS = HealthPermission.getReadPermission(StepsRecord::class)
        val EXERCISE = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        val DISTANCE = HealthPermission.getReadPermission(DistanceRecord::class)
        val CALORIES = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
        val HEART_RATE = HealthPermission.getReadPermission(HeartRateRecord::class)

        /** Precisam estar no AndroidManifest do módulo (uses-permission android.permission.health.READ_*). */
        val READ_PERMISSIONS: Set<String> = setOf(STEPS, EXERCISE, DISTANCE, CALORIES, HEART_RATE)

        fun workoutTypeOf(exerciseType: Int): WorkoutType = when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            -> WorkoutType.Running
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> WorkoutType.Walking
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> WorkoutType.Hiking
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
            -> WorkoutType.Cycling
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            -> WorkoutType.Swimming
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
            -> WorkoutType.Strength
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> WorkoutType.Yoga
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> WorkoutType.Dancing
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> WorkoutType.Soccer
            else -> WorkoutType.Other
        }

        fun recordingMethodOf(method: Int): RecordingMethod = when (method) {
            Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> RecordingMethod.ActivelyRecorded
            Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> RecordingMethod.Automatic
            Metadata.RECORDING_METHOD_MANUAL_ENTRY -> RecordingMethod.Manual
            else -> RecordingMethod.Unknown
        }

        fun deviceTypeOf(type: Int): DeviceType = when (type) {
            Device.TYPE_PHONE -> DeviceType.Phone
            Device.TYPE_WATCH -> DeviceType.Watch
            Device.TYPE_FITNESS_BAND -> DeviceType.FitnessBand
            Device.TYPE_RING -> DeviceType.Ring
            Device.TYPE_CHEST_STRAP -> DeviceType.ChestStrap
            Device.TYPE_SCALE -> DeviceType.Scale
            Device.TYPE_UNKNOWN -> DeviceType.Unknown
            else -> DeviceType.Other
        }
    }
}

private fun Instant.toJavaInstant(): java.time.Instant =
    java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

private fun java.time.Instant.toKotlinInstant(): Instant = Instant.fromEpochSeconds(epochSecond, nano)
