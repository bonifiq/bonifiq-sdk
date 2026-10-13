@file:OptIn(ExperimentalForeignApi::class)

package br.com.bonifiq.sdk.fitness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSBundle
import platform.Foundation.NSCompoundPredicate
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSSortDescriptor
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.HealthKit.*
import platform.UIKit.UIApplication
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Instant

internal actual fun platformHealthSource(): HealthSource = HealthKitSource()

/**
 * Apple Saúde (HealthKit). O app precisa da capability HealthKit (entitlement `com.apple.developer.healthkit`) e de
 * `NSHealthShareUsageDescription` no Info.plist.
 */
internal class HealthKitSource : HealthSource {

    override val platform: HealthPlatform = HealthPlatform.AppleHealth

    private val store by lazy { HKHealthStore() }

    private val stepType get() = HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierStepCount)
    private val heartRateType get() = HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierHeartRate)

    private val readTypes: Set<HKObjectType> by lazy {
        setOfNotNull(
            stepType,
            HKObjectType.workoutType(),
            HKSeriesType.workoutRouteType(),
            heartRateType,
            HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierActiveEnergyBurned),
            HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierDistanceWalkingRunning),
            HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierDistanceCycling),
            HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierDistanceSwimming),
        )
    }

    override suspend fun availability(): FitnessAvailability =
        if (HKHealthStore.isHealthDataAvailable()) FitnessAvailability.Available else FitnessAvailability.Unsupported

    override fun openHealthAppInstall(): Boolean = false

    /** Abre o app Saúde (as permissões ficam em Perfil → Apps). */
    override fun openHealthSettings(): Boolean {
        val url = NSURL.URLWithString("x-apple-health://") ?: return false
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
        return true
    }

    override suspend fun permissionStatus(): FitnessPermissionStatus {
        requireUsageDescription()
        return suspendCancellableCoroutine { continuation ->
            store.getRequestStatusForAuthorizationToShareTypes(
                typesToShare = emptySet<HKSampleType>(),
                readTypes = readTypes,
            ) { status, error ->
                if (!continuation.isActive) return@getRequestStatusForAuthorizationToShareTypes
                if (error != null) {
                    continuation.resumeWithException(error.toException())
                } else {
                    // "Unnecessary" = o cliente já respondeu. Se negou a leitura, a Apple não conta: os dados vêm vazios.
                    continuation.resume(
                        if (status == HKAuthorizationRequestStatusUnnecessary) {
                            FitnessPermissionStatus.Granted
                        } else {
                            FitnessPermissionStatus.NotGranted
                        },
                    )
                }
            }
        }
    }

    override suspend fun requestPermissions(): FitnessPermissionStatus {
        requireUsageDescription()
        suspendCancellableCoroutine { continuation ->
            store.requestAuthorizationToShareTypes(
                typesToShare = emptySet<HKSampleType>(),
                readTypes = readTypes,
            ) { _, error ->
                if (!continuation.isActive) return@requestAuthorizationToShareTypes
                if (error != null) continuation.resumeWithException(error.toException()) else continuation.resume(Unit)
            }
        }
        return permissionStatus()
    }

    /** Sem o texto no Info.plist o HealthKit encerra o app com uma exceção de Objective-C, que o Kotlin não captura. */
    private fun requireUsageDescription() {
        if (NSBundle.mainBundle.objectForInfoDictionaryKey("NSHealthShareUsageDescription") == null) {
            throw FitnessException.Unavailable("Adicione NSHealthShareUsageDescription ao Info.plist do app.")
        }
    }

    override suspend fun dailySteps(start: LocalDate, endInclusive: LocalDate): List<DailySteps> {
        val type = stepType ?: return emptyList()
        val zone = TimeZone.currentSystemDefault()
        val from = start.atStartOfDayIn(zone).asNSDate()
        val to = endInclusive.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).asNSDate()
        // Estatística por dia: o HealthKit remove a contagem em dobro entre iPhone, relógio e outros apps.
        val query = HKStatisticsCollectionQuery(
            quantityType = type,
            quantitySamplePredicate = HKQuery.predicateForSamplesWithStartDate(
                startDate = from,
                endDate = to,
                options = HKQueryOptionStrictStartDate,
            ),
            options = HKStatisticsOptionCumulativeSum or HKStatisticsOptionSeparateBySource,
            anchorDate = from,
            intervalComponents = NSDateComponents().apply { day = 1 },
        )
        return suspendCancellableCoroutine { continuation ->
            query.initialResultsHandler = handler@{ _, collection, error ->
                if (!continuation.isActive) return@handler
                if (error != null) {
                    continuation.resumeWithException(error.toException())
                    return@handler
                }
                try {
                    val days = collection?.statistics().orEmpty().filterIsInstance<HKStatistics>().map { day ->
                        DailySteps(
                            date = day.startDate.asInstant().toLocalDateTime(zone).date,
                            count = day.sumQuantity()?.doubleValueForUnit(HKUnit.countUnit())?.toLong() ?: 0,
                            sources = day.sources.orEmpty().filterIsInstance<HKSource>().map { it.toFitnessSource(null) },
                        )
                    }
                    continuation.resume(days)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
            continuation.invokeOnCancellation { store.stopQuery(query) }
            store.executeQuery(query)
        }
    }

    override suspend fun workouts(start: Instant, end: Instant): List<Workout> {
        val samples: List<HKWorkout> = suspendCancellableCoroutine { continuation ->
            val query = HKSampleQuery(
                sampleType = HKObjectType.workoutType(),
                predicate = HKQuery.predicateForSamplesWithStartDate(
                    startDate = start.asNSDate(),
                    endDate = end.asNSDate(),
                    options = HKQueryOptionStrictStartDate,
                ),
                limit = MAX_WORKOUTS.toULong(),
                sortDescriptors = listOf(NSSortDescriptor(HKSampleSortIdentifierStartDate, ascending = false)),
            ) { _, results, error ->
                if (!continuation.isActive) return@HKSampleQuery
                if (error != null) {
                    continuation.resumeWithException(error.toException())
                } else {
                    continuation.resume(results.orEmpty().filterIsInstance<HKWorkout>())
                }
            }
            continuation.invokeOnCancellation { store.stopQuery(query) }
            store.executeQuery(query)
        }
        return samples.map { workoutOf(it) }
    }

    @Suppress("DEPRECATION") // totalDistance/totalEnergyBurned: substitutos só existem do iOS 16/18 em diante
    private suspend fun workoutOf(workout: HKWorkout): Workout {
        val device = workout.device?.toFitnessDevice() ?: deviceFromProductType(workout.sourceRevision.productType)
        val metadata = workout.metadata
        return Workout(
            id = workout.UUID.UUIDString,
            type = workoutTypeOf(workout.workoutActivityType),
            title = metadata?.get(HKMetadataKeyWorkoutBrandName) as? String,
            start = workout.startDate.asInstant(),
            end = workout.endDate.asInstant(),
            distanceMeters = workout.totalDistance?.doubleValueForUnit(HKUnit.meterUnit()),
            activeCaloriesKcal = workout.totalEnergyBurned?.doubleValueForUnit(HKUnit.kilocalorieUnit()),
            heartRate = heartRateDuring(workout),
            hasRoute = hasRoute(workout),
            source = workout.sourceRevision.source.toFitnessSource(device),
            recordingMethod = when {
                metadata?.get(HKMetadataKeyWasUserEntered).isTrue() -> RecordingMethod.Manual
                device != null -> RecordingMethod.ActivelyRecorded
                else -> RecordingMethod.Unknown
            },
        )
    }

    /** Batimentos gravados pelo mesmo app/relógio durante o treino. Sem dados (ou sem permissão), `null`. */
    private suspend fun heartRateDuring(workout: HKWorkout): HeartRateSummary? {
        val type = heartRateType ?: return null
        val bpm = HKUnit.countUnit().unitDividedByUnit(HKUnit.minuteUnit())
        val predicate = NSCompoundPredicate.andPredicateWithSubpredicates(
            listOf(
                HKQuery.predicateForSamplesWithStartDate(
                    startDate = workout.startDate,
                    endDate = workout.endDate,
                    options = HKQueryOptionStrictStartDate,
                ),
                HKQuery.predicateForObjectsFromSource(workout.sourceRevision.source),
            ),
        )
        return suspendCancellableCoroutine { continuation ->
            val query = HKStatisticsQuery(
                quantityType = type,
                quantitySamplePredicate = predicate,
                options = HKStatisticsOptionDiscreteAverage or HKStatisticsOptionDiscreteMin or HKStatisticsOptionDiscreteMax,
            ) { _, statistics, _ ->
                if (!continuation.isActive) return@HKStatisticsQuery
                // Sem amostras o HealthKit devolve erro "sem dados": vale como treino sem batimentos.
                val avg = statistics?.averageQuantity()?.doubleValueForUnit(bpm)
                val min = statistics?.minimumQuantity()?.doubleValueForUnit(bpm)
                val max = statistics?.maximumQuantity()?.doubleValueForUnit(bpm)
                continuation.resume(
                    if (avg != null && min != null && max != null) {
                        HeartRateSummary(averageBpm = avg.toInt(), minBpm = min.toInt(), maxBpm = max.toInt())
                    } else {
                        null
                    },
                )
            }
            continuation.invokeOnCancellation { store.stopQuery(query) }
            store.executeQuery(query)
        }
    }

    /** Há trajeto de GPS associado ao treino (exige a permissão "Trajetos de exercício"). */
    private suspend fun hasRoute(workout: HKWorkout): Boolean = suspendCancellableCoroutine { continuation ->
        val query = HKSampleQuery(
            sampleType = HKSeriesType.workoutRouteType(),
            predicate = HKQuery.predicateForObjectsFromWorkout(workout),
            limit = 1u,
            sortDescriptors = null,
        ) { _, results, _ ->
            if (!continuation.isActive) return@HKSampleQuery
            continuation.resume(!results.isNullOrEmpty())
        }
        continuation.invokeOnCancellation { store.stopQuery(query) }
        store.executeQuery(query)
    }

    private companion object {
        const val MAX_WORKOUTS = 200

        fun workoutTypeOf(type: HKWorkoutActivityType): WorkoutType = when (type) {
            HKWorkoutActivityTypeRunning -> WorkoutType.Running
            HKWorkoutActivityTypeWalking -> WorkoutType.Walking
            HKWorkoutActivityTypeHiking -> WorkoutType.Hiking
            HKWorkoutActivityTypeCycling, HKWorkoutActivityTypeHandCycling -> WorkoutType.Cycling
            HKWorkoutActivityTypeSwimming -> WorkoutType.Swimming
            HKWorkoutActivityTypeTraditionalStrengthTraining,
            HKWorkoutActivityTypeFunctionalStrengthTraining,
            -> WorkoutType.Strength
            HKWorkoutActivityTypeYoga -> WorkoutType.Yoga
            HKWorkoutActivityTypeSocialDance, HKWorkoutActivityTypeCardioDance -> WorkoutType.Dancing
            HKWorkoutActivityTypeSoccer -> WorkoutType.Soccer
            else -> WorkoutType.Other
        }

        fun HKDevice.toFitnessDevice(): FitnessDevice = FitnessDevice(
            type = deviceTypeOf(model ?: name),
            manufacturer = manufacturer,
            model = model ?: name,
        )

        /** Ex.: "Watch6,2" (Apple Watch) ou "iPhone15,3", quando o app não informa o aparelho. */
        fun deviceFromProductType(productType: String?): FitnessDevice? {
            if (productType == null) return null
            val type = deviceTypeOf(productType)
            if (type == DeviceType.Unknown) return null
            return FitnessDevice(type = type, manufacturer = "Apple", model = productType)
        }

        fun deviceTypeOf(model: String?): DeviceType = when {
            model == null -> DeviceType.Unknown
            model.contains("Watch", ignoreCase = true) -> DeviceType.Watch
            model.contains("iPhone", ignoreCase = true) -> DeviceType.Phone
            else -> DeviceType.Unknown
        }

        fun HKSource.toFitnessSource(device: FitnessDevice?): FitnessSource =
            FitnessSource(appId = bundleIdentifier, appName = name, device = device)

        /** Metadados do HealthKit chegam como NSNumber. */
        fun Any?.isTrue(): Boolean = when (this) {
            is Boolean -> this
            is NSNumber -> boolValue
            else -> false
        }

        fun NSError.toException(): FitnessException =
            FitnessException.Platform(localizedDescription)

        fun Instant.asNSDate(): NSDate = NSDate.dateWithTimeIntervalSince1970(toEpochMilliseconds() / 1000.0)

        fun NSDate.asInstant(): Instant = Instant.fromEpochMilliseconds((timeIntervalSince1970 * 1000.0).toLong())
    }
}
