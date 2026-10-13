package br.com.bonifiq.sdk.fitness

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Módulo opcional de atividade física: lê passos, treinos e batimentos que o aparelho já reúne.
 * - **Android:** Health Connect. Recebe Samsung Health, Fitbit, Mi Fitness (Xiaomi), Zepp (Amazfit), Garmin e outros.
 * - **iOS:** Apple Saúde (HealthKit). Recebe o iPhone, o Apple Watch e os apps conectados ao Saúde.
 *
 * Só lê; nada é gravado na plataforma de saúde nem enviado à BonifiQ. Crie uma instância e reutilize.
 *
 * ```
 * val fitness = BonifiQFitness()
 * if (fitness.availability() == FitnessAvailability.Available) {
 *     fitness.requestPermissions()          // abre o pedido do sistema (Health Connect ou Saúde)
 *     val passos = fitness.dailySteps(lastDays = 7)
 *     val treinos = fitness.workouts(lastDays = 30)
 * }
 * ```
 */
public class BonifiQFitness internal constructor(
    private val source: HealthSource,
    private val clock: Clock,
    private val timeZone: () -> TimeZone,
) {
    public constructor() : this(platformHealthSource(), Clock.System, { TimeZone.currentSystemDefault() })

    /** Plataforma de saúde usada neste aparelho. */
    public val platform: HealthPlatform get() = source.platform

    /** Se dá para ler dados agora. Confira antes de pedir permissão. */
    @Throws(FitnessException::class, CancellationException::class)
    public suspend fun availability(): FitnessAvailability = try {
        source.availability()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        FitnessAvailability.Unsupported
    }

    /** Android: abre o Health Connect na Play Store (caso [FitnessAvailability.NeedsInstall]). No iOS, `false`. */
    public fun openHealthAppInstall(): Boolean = runCatching { source.openHealthAppInstall() }.getOrDefault(false)

    /**
     * Abre onde o cliente gerencia as permissões: as configurações do Health Connect no Android e o app Saúde no iOS.
     * Útil quando ele negou antes: depois de duas recusas, o Android não mostra mais o pedido.
     */
    public fun openHealthSettings(): Boolean = runCatching { source.openHealthSettings() }.getOrDefault(false)

    @Throws(FitnessException::class, CancellationException::class)
    public suspend fun permissionStatus(): FitnessPermissionStatus {
        requireAvailable()
        return guarded { source.permissionStatus() }
    }

    /**
     * Pede ao cliente acesso de leitura a passos, treinos, distância, calorias e batimentos e devolve o resultado.
     * Se tudo já estiver liberado, não mostra nada. Chame a partir de um gesto do cliente (ex.: botão "Conectar").
     */
    @Throws(FitnessException::class, CancellationException::class)
    public suspend fun requestPermissions(): FitnessPermissionStatus {
        requireAvailable()
        return guarded { source.requestPermissions() }
    }

    /**
     * Passos por dia, de [start] a [end] (inclusive), no fuso do aparelho, do mais antigo ao mais novo.
     * Todo dia do intervalo aparece, com 0 quando não há dado. Sem permissão de passos, todos vêm com 0.
     */
    @Throws(FitnessException::class, CancellationException::class)
    public suspend fun dailySteps(start: LocalDate, end: LocalDate): List<DailySteps> {
        if (end < start) return emptyList()
        requireAvailable()
        val byDate = guarded { source.dailySteps(start, end) }.associateBy { it.date }
        return generateSequence(start) { it.plus(1, DateTimeUnit.DAY) }
            .takeWhile { it <= end }
            .map { date -> byDate[date] ?: DailySteps(date, 0, emptyList()) }
            .toList()
    }

    /** Passos dos últimos [lastDays] dias, incluindo hoje. */
    @Throws(FitnessException::class, CancellationException::class)
    public suspend fun dailySteps(lastDays: Int): List<DailySteps> {
        val today = clock.todayIn(timeZone())
        return dailySteps(today.minus(lastDays.coerceAtLeast(1) - 1, DateTimeUnit.DAY), today)
    }

    /** Treinos que começaram entre [start] e [end], do mais recente ao mais antigo. Sem permissão, lista vazia. */
    @Throws(FitnessException::class, CancellationException::class)
    public suspend fun workouts(start: Instant, end: Instant): List<Workout> {
        if (end <= start) return emptyList()
        requireAvailable()
        return guarded { source.workouts(start, end) }
            .filter { it.start >= start && it.start < end }
            .sortedByDescending { it.start }
    }

    /** Treinos dos últimos [lastDays] dias, incluindo hoje. */
    @Throws(FitnessException::class, CancellationException::class)
    public suspend fun workouts(lastDays: Int): List<Workout> {
        val zone = timeZone()
        val now = clock.now()
        val firstDay = clock.todayIn(zone).minus(lastDays.coerceAtLeast(1) - 1, DateTimeUnit.DAY)
        return workouts(firstDay.atStartOfDayIn(zone), now)
    }

    private suspend fun requireAvailable() {
        when (availability()) {
            FitnessAvailability.Available -> Unit
            FitnessAvailability.NeedsInstall -> throw FitnessException.Unavailable(
                "Instale ou atualize o Health Connect para ler os dados de atividade.",
            )
            FitnessAvailability.Unsupported -> throw FitnessException.Unavailable(
                "Este aparelho não tem uma plataforma de saúde compatível.",
            )
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: FitnessException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw FitnessException.Platform(e.message ?: "Não foi possível ler os dados de saúde.", e)
    }
}
