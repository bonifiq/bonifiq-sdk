package br.com.bonifiq.sdk.fitness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Dados lidos quando a conexão está pronta. */
public data class FitnessSnapshot(
    val platform: HealthPlatform,
    val permission: FitnessPermissionStatus,
    /** Um item por dia, do mais antigo ao mais novo. */
    val steps: List<DailySteps>,
    /** Do mais recente ao mais antigo. */
    val workouts: List<Workout>,
) {
    val hasData: Boolean get() = steps.any { it.count > 0 } || workouts.isNotEmpty()
}

/** Estado do [FitnessConnect]. Crie com [rememberFitnessConnectState]. */
@Stable
public class FitnessConnectState internal constructor(
    private val fitness: BonifiQFitness,
    private val scope: CoroutineScope,
    private val stepsDays: Int,
    private val workoutDays: Int,
) {
    internal var phase: ConnectPhase by mutableStateOf(ConnectPhase.Loading)
        private set

    /** Recarregando dados já exibidos (ex.: para um indicador na top bar). */
    public var refreshing: Boolean by mutableStateOf(false)
        private set

    /** Os dados, quando a conexão está pronta e há algo para mostrar. */
    public val snapshot: FitnessSnapshot? get() = (phase as? ConnectPhase.Ready)?.snapshot

    internal val platform: HealthPlatform get() = fitness.platform

    private var job: Job? = null
    private var denied = false

    /** Lê de novo (ex.: botão atualizar). O componente também recarrega quando o app volta ao primeiro plano. */
    public fun refresh() {
        if ((phase as? ConnectPhase.NotConnected)?.requesting == true) return // o pedido em andamento recarrega
        if (job?.isActive == true) return
        job = scope.launch { load(afterRequest = false) }
    }

    internal fun connect() {
        job?.cancel()
        job = scope.launch {
            phase = ConnectPhase.NotConnected(requesting = true, denied = denied)
            try {
                fitness.requestPermissions()
            } catch (e: FitnessException) {
                phase = ConnectPhase.Error(e.message)
                return@launch
            }
            load(afterRequest = true)
        }
    }

    internal fun openHealthAppInstall() = fitness.openHealthAppInstall()

    internal fun openHealthSettings() = fitness.openHealthSettings()

    internal fun openHealthApp(app: HealthApp) = fitness.openHealthApp(app)

    private suspend fun load(afterRequest: Boolean) {
        if (phase is ConnectPhase.Ready) refreshing = true
        phase = try {
            when (fitness.availability()) {
                FitnessAvailability.Unsupported -> ConnectPhase.Unsupported
                FitnessAvailability.NeedsInstall -> ConnectPhase.NeedsInstall
                FitnessAvailability.Available -> {
                    val permission = fitness.permissionStatus()
                    if (permission == FitnessPermissionStatus.NotGranted) {
                        if (afterRequest) denied = true
                        ConnectPhase.NotConnected(requesting = false, denied = denied)
                    } else {
                        val snapshot = FitnessSnapshot(
                            platform = fitness.platform,
                            permission = permission,
                            steps = fitness.dailySteps(lastDays = stepsDays),
                            workouts = fitness.workouts(lastDays = workoutDays),
                        )
                        if (snapshot.hasData) {
                            ConnectPhase.Ready(snapshot)
                        } else {
                            ConnectPhase.NoData(fitness.installedHealthApps())
                        }
                    }
                }
            }
        } catch (e: FitnessException) {
            ConnectPhase.Error(e.message)
        } finally {
            refreshing = false
        }
    }
}

internal sealed interface ConnectPhase {
    data object Loading : ConnectPhase
    data object Unsupported : ConnectPhase
    data object NeedsInstall : ConnectPhase
    data class NotConnected(val requesting: Boolean, val denied: Boolean) : ConnectPhase

    /** Conectado, mas a plataforma não tem dados: falta ligar o envio no app do relógio/pulseira. */
    data class NoData(val installedApps: List<HealthApp>) : ConnectPhase
    data class Ready(val snapshot: FitnessSnapshot) : ConnectPhase
    data class Error(val message: String?) : ConnectPhase
}

/**
 * Estado do [FitnessConnect]: lê ao entrar na tela e sempre que o app volta ao primeiro plano (ex.: depois de ligar
 * o envio no Samsung Health). [stepsDays] e [workoutDays] definem o período lido.
 */
@Composable
public fun rememberFitnessConnectState(
    fitness: BonifiQFitness,
    stepsDays: Int = 7,
    workoutDays: Int = 30,
): FitnessConnectState {
    val scope = rememberCoroutineScope()
    val state = remember(fitness, stepsDays, workoutDays) {
        FitnessConnectState(fitness, scope, stepsDays, workoutDays)
    }
    LaunchedEffect(state) {
        state.refresh()
        appResumedEvents().collect { state.refresh() }
    }
    return state
}

/**
 * Fluxo de conexão pronto para dados de atividade. Ele cuida, na ordem:
 * 1. de aparelho sem suporte e de Health Connect não instalado (Android 9 a 13);
 * 2. do pedido de permissão, e da recusa (abre o Health Connect para liberar);
 * 3. de conectado sem dados: mostra, para o app de saúde instalado (Samsung Health, Mi Fitness, Zepp, Garmin...),
 *    como ligar o envio ao Health Connect, com um botão que abre o app.
 *
 * Com dados, mostra [content]. Use o `MaterialTheme` do app; os textos vêm de [LocalFitnessStrings].
 *
 * ```
 * FitnessConnect(fitness) { dados ->
 *     MeuResumo(dados.steps, dados.workouts)
 * }
 * ```
 */
@Composable
public fun FitnessConnect(
    fitness: BonifiQFitness,
    modifier: Modifier = Modifier,
    content: @Composable (FitnessSnapshot) -> Unit,
) {
    FitnessConnect(rememberFitnessConnectState(fitness), modifier, content)
}

/** Versão com estado próprio, para o app ler [FitnessConnectState.refreshing] ou chamar `refresh()`. */
@Composable
public fun FitnessConnect(
    state: FitnessConnectState,
    modifier: Modifier = Modifier,
    content: @Composable (FitnessSnapshot) -> Unit,
) {
    val strings = LocalFitnessStrings.current
    val apple = state.platform == HealthPlatform.AppleHealth
    Box(modifier.fillMaxSize()) {
        when (val phase = state.phase) {
            ConnectPhase.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            ConnectPhase.Unsupported -> Message(
                icon = Icons.Filled.Favorite,
                title = strings.unsupportedTitle,
                message = when (state.platform) {
                    HealthPlatform.None -> strings.unsupportedNoPlatform
                    HealthPlatform.AppleHealth -> strings.unsupportedAppleHealth
                    HealthPlatform.HealthConnect -> strings.unsupportedHealthConnect
                },
            )

            ConnectPhase.NeedsInstall -> Message(
                icon = Icons.Filled.Favorite,
                title = strings.installTitle,
                message = strings.installMessage,
            ) {
                Button(onClick = { state.openHealthAppInstall() }) { Text(strings.installAction) }
            }

            is ConnectPhase.NotConnected -> Message(
                icon = Icons.Filled.Favorite,
                title = strings.connectTitle,
                message = if (apple) strings.connectMessageAppleHealth else strings.connectMessageHealthConnect,
            ) {
                Button(onClick = state::connect, enabled = !phase.requesting) {
                    if (phase.requesting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(strings.connectAction)
                    }
                }
                if (phase.denied && state.platform == HealthPlatform.HealthConnect) {
                    // Depois de duas recusas o Android não mostra mais o pedido: libera-se no Health Connect.
                    Text(
                        strings.connectDeniedHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { state.openHealthSettings() }) { Text(strings.openHealthConnect) }
                }
            }

            is ConnectPhase.NoData -> NoDataGuide(state, phase.installedApps, strings)

            is ConnectPhase.Error -> Message(
                icon = Icons.Filled.Warning,
                title = strings.errorTitle,
                message = phase.message ?: strings.errorMessage,
            ) {
                OutlinedButton(onClick = state::refresh) { Text(strings.retry) }
            }

            is ConnectPhase.Ready -> Column(Modifier.fillMaxSize()) {
                if (phase.snapshot.permission == FitnessPermissionStatus.Partial) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    ) {
                        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
                            Text(strings.partialNotice, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { state.openHealthSettings() }) { Text(strings.managePermissions) }
                        }
                    }
                }
                Box(Modifier.weight(1f)) { content(phase.snapshot) }
            }
        }
    }
}

@Composable
private fun NoDataGuide(state: FitnessConnectState, installedApps: List<HealthApp>, strings: FitnessStrings) {
    val apple = state.platform == HealthPlatform.AppleHealth
    val syncing = installedApps.filter { it.syncsWithHealthPlatform }
    val notSyncing = installedApps.filterNot { it.syncsWithHealthPlatform }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (apple) strings.noDataAppleTitle else strings.noDataTitle, style = MaterialTheme.typography.titleLarge)
        Text(
            when {
                apple -> strings.noDataAppleMessage
                syncing.isEmpty() -> strings.noDataNoAppsMessage
                else -> strings.noDataMessage
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        syncing.forEach { app ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium)
                    app.setupSteps.forEachIndexed { index, step ->
                        Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = { state.openHealthApp(app) }, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.openApp.replace("%s", app.name))
                    }
                }
            }
        }
        if (!apple) {
            Text(
                strings.noDataFootnote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        notSyncing.forEach { app ->
            Text(
                strings.appDoesNotSync.replace("%s", app.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            OutlinedButton(onClick = state::refresh, enabled = !state.refreshing) { Text(strings.doneRefresh) }
            TextButton(onClick = { state.openHealthSettings() }) {
                Text(if (apple) strings.openAppleHealth else strings.openHealthConnect)
            }
        }
    }
}

@Composable
private fun Message(
    icon: ImageVector,
    title: String,
    message: String,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        actions()
    }
}
