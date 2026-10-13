package br.com.bonifiq.sdk.fitness

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/** Fluxo do [FitnessConnect] com uma plataforma de saúde fake; screenshots em `bonifiq-fitness/build/screenshots`. */
@OptIn(ExperimentalTestApi::class)
class FitnessConnectUiTest {

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private val samsungHealth = HealthApp(
        appId = "com.sec.android.app.shealth",
        name = "Samsung Health",
        syncsWithHealthPlatform = true,
        setupSteps = listOf(
            "Abra o Samsung Health e toque em ⋮ (Mais opções) → Configurações.",
            "Toque em Conexão Saúde e permita todos os dados.",
        ),
    )
    private val huawei = HealthApp("com.huawei.health", "Huawei Health", syncsWithHealthPlatform = false, emptyList())

    private val someSteps = listOf(DailySteps(LocalDate(2026, 10, 13), 4230, emptyList()))

    @Test
    fun connectingShowsTheData() = runDesktopComposeUiTest(width = 412, height = 640) {
        val source = FakeHealthSource(permission = FitnessPermissionStatus.NotGranted, steps = someSteps)
        setContent { Host(source) }

        waitForText("Conecte sua atividade")
        snapshot("fitness-01-conectar")
        onNodeWithText("Conectar").performClick()

        waitForText("dados: 4230 passos")
    }

    @Test
    fun connectedWithoutDataExplainsTheMissingStepForTheInstalledApp() = runDesktopComposeUiTest(width = 412, height = 760) {
        val source = FakeHealthSource(installedApps = listOf(samsungHealth, huawei))
        setContent { Host(source) }

        waitForText("Falta 1 passo")
        waitForText("O Huawei Health não envia dados à Conexão Saúde.")
        snapshot("fitness-02-falta-1-passo")
        onNodeWithText("Abrir o Samsung Health").performClick()
        assertEquals(listOf("com.sec.android.app.shealth"), source.opened)

        // O cliente ligou o envio no Samsung Health e voltou.
        source.steps = someSteps
        onNodeWithText("Já ativei, atualizar").performClick()
        waitForText("dados: 4230 passos")
    }

    @Test
    fun withoutKnownAppsTheGuideListsTheCommonOnes() = runDesktopComposeUiTest(width = 412, height = 520) {
        setContent { Host(FakeHealthSource()) }

        waitForText("No app do seu relógio ou pulseira (Samsung Health, Mi Fitness")
    }

    @Test
    fun refusingOffersTheHealthConnectShortcut() = runDesktopComposeUiTest(width = 412, height = 640) {
        val source = FakeHealthSource(
            permission = FitnessPermissionStatus.NotGranted,
            grantOnRequest = FitnessPermissionStatus.NotGranted,
        )
        setContent { Host(source) }

        waitForText("Conecte sua atividade")
        onNodeWithText("Conectar").performClick()
        waitForText("O pedido não apareceu ou você recusou?")
        snapshot("fitness-03-recusou")
        onNodeWithText("Abrir a Conexão Saúde").performClick()
        assertEquals(listOf("settings"), source.opened)
    }

    @Test
    fun missingHealthConnectOffersTheInstall() = runDesktopComposeUiTest(width = 412, height = 640) {
        val source = FakeHealthSource(availability = FitnessAvailability.NeedsInstall)
        setContent { Host(source) }

        waitForText("Instale a Conexão Saúde")
        snapshot("fitness-04-instalar")
        onNodeWithText("Instalar a Conexão Saúde").performClick()
        assertEquals(listOf("install"), source.opened)
    }

    @Test
    fun partialAccessShowsANoticeAboveTheData() = runDesktopComposeUiTest(width = 412, height = 400) {
        val source = FakeHealthSource(permission = FitnessPermissionStatus.Partial, steps = someSteps)
        setContent { Host(source) }

        waitForText("Parte dos dados não foi liberada.")
        waitForText("dados: 4230 passos")
    }

    @Test
    fun iPhoneWithoutDataPointsToTheHealthApp() = runDesktopComposeUiTest(width = 412, height = 520) {
        setContent { Host(FakeHealthSource(platform = HealthPlatform.AppleHealth)) }

        waitForText("Nenhum dado de atividade")
        waitForText("Abrir o app Saúde")
    }

    @Test
    fun desktopIsUnsupported() = runDesktopComposeUiTest(width = 412, height = 400) {
        setContent { MaterialTheme { Surface { FitnessConnect(BonifiQFitness()) { Text("dados") } } } }

        waitForText("Atividade física indisponível")
    }

    @Composable
    private fun Host(source: FakeHealthSource) {
        val fitness = BonifiQFitness(source, Clock.System) { TimeZone.currentSystemDefault() }
        MaterialTheme {
            Surface {
                FitnessConnect(fitness) { data ->
                    Column { Text("dados: ${data.steps.sumOf { it.count }} passos") }
                }
            }
        }
    }

    private fun ComposeUiTest.waitForText(text: String) {
        waitUntil(conditionDescription = "texto '$text'", timeoutMillis = 10_000) {
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeUiTest.snapshot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
    }
}
