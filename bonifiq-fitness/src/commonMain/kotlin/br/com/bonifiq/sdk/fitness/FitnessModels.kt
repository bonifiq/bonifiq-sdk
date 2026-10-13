package br.com.bonifiq.sdk.fitness

import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Instant

/** De onde o aparelho lê os dados de saúde. */
public enum class HealthPlatform {
    /** Android: Health Connect. Recebe dados de Samsung Health, Fitbit, Mi Fitness, Zepp (Amazfit), Garmin e outros. */
    HealthConnect,

    /** iOS: Apple Saúde (HealthKit). Recebe dados do iPhone, do Apple Watch e dos apps conectados ao Saúde. */
    AppleHealth,

    /** Sem plataforma de saúde (ex.: desktop). */
    None,
}

public enum class FitnessAvailability {
    Available,

    /**
     * Android 9 a 13: o app Health Connect não está instalado ou precisa de atualização.
     * [BonifiQFitness.openHealthAppInstall] abre a página dele na Play Store.
     */
    NeedsInstall,

    /** O aparelho não tem plataforma de saúde (Android 8 ou anterior, iPad sem o app Saúde, desktop). */
    Unsupported,
}

public enum class FitnessPermissionStatus {
    /**
     * Android: todas as leituras liberadas.
     * iOS: o cliente já respondeu ao pedido. A Apple não revela se ele negou a leitura: nesse caso os dados
     * simplesmente voltam vazios.
     */
    Granted,

    /** Android: só parte das leituras liberada (ex.: passos sim, batimentos não). Os dados negados voltam vazios. */
    Partial,

    /** Ainda não pedidas, ou todas negadas. */
    NotGranted,
}

/** Total de passos de um dia (no fuso do aparelho). Os passos de vários apps não são somados em dobro. */
public data class DailySteps(
    val date: LocalDate,
    val count: Long,
    /** Apps que gravaram passos nesse dia. Vazio quando não há passos. */
    val sources: List<FitnessSource>,
)

/** Um treino gravado por um app ou relógio (corrida, caminhada, bike...). */
public data class Workout(
    /** Identificador do registro na plataforma de saúde. */
    val id: String,
    val type: WorkoutType,
    /** Nome dado pelo app ou pelo cliente, quando houver. */
    val title: String?,
    val start: Instant,
    val end: Instant,
    /** Distância percorrida, em metros. `null` sem permissão ou quando o app não gravou distância. */
    val distanceMeters: Double?,
    /** Calorias ativas, em kcal. `null` sem permissão ou quando o app não gravou. */
    val activeCaloriesKcal: Double?,
    /** Batimentos durante o treino, gravados pelo mesmo app. `null` sem permissão ou sem sensor. */
    val heartRate: HeartRateSummary?,
    /** O treino tem trajeto de GPS gravado. */
    val hasRoute: Boolean,
    /** App e aparelho que gravaram o treino. */
    val source: FitnessSource,
    val recordingMethod: RecordingMethod,
) {
    val duration: Duration get() = end - start
}

public data class HeartRateSummary(val averageBpm: Int, val minBpm: Int, val maxBpm: Int)

/** App (e aparelho, quando informado) que gravou um dado. */
public data class FitnessSource(
    /** Pacote no Android (ex.: `com.sec.android.app.shealth`) ou bundle id no iOS. */
    val appId: String,
    /** Nome do app para exibir (ex.: "Samsung Health"), quando conhecido. */
    val appName: String?,
    val device: FitnessDevice?,
)

public data class FitnessDevice(
    val type: DeviceType,
    val manufacturer: String?,
    val model: String?,
)

public enum class DeviceType { Phone, Watch, FitnessBand, Ring, ChestStrap, Scale, Other, Unknown }

/** Como o dado foi registrado: ajuda a separar atividade real de lançamento manual. */
public enum class RecordingMethod {
    /** O cliente iniciou o treino no relógio ou no app (ex.: "começar corrida"). */
    ActivelyRecorded,

    /** Registrado sozinho pelo aparelho (ex.: caminhada detectada automaticamente). */
    Automatic,

    /** Digitado à mão pelo cliente: não comprova atividade. */
    Manual,

    /** O app não informou. */
    Unknown,
}

public enum class WorkoutType { Running, Walking, Hiking, Cycling, Swimming, Strength, Yoga, Dancing, Soccer, Other }
