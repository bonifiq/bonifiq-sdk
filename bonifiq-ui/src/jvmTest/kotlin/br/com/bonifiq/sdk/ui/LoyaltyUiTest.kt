package br.com.bonifiq.sdk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import br.com.bonifiq.sdk.auth.VtexSessionTokens
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Testes de UI com screenshots em `bonifiq-ui/build/screenshots` (dados de um servidor fake). */
@OptIn(ExperimentalTestApi::class)
class LoyaltyUiTest {

    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** Desktop não tem WebView: o hub avisa e deixa o cliente voltar (o app recebe `Close`). */
    @Test
    fun hubWithoutWebViewSaysItIsUnavailable() = runDesktopComposeUiTest(width = 412, height = 400) {
        val server = FakeBonifiQServer()
        val events = mutableListOf<LoyaltyEvent>()
        setContent { Host { BonifiQLoyalty(server.sdk) { events += it } } }

        waitForText("O programa de fidelidade não está disponível neste aparelho.")
        onNodeWithText("Fechar").performClick()
        assertEquals(listOf<LoyaltyEvent>(LoyaltyEvent.Close), events)
    }

    /** Versão "tudo": um toque usa todo o disponível; depois de remover, dá para usar tudo de novo. */
    @Test
    fun cashbackAllAppliesEverythingAgainAfterRemoving() = runDesktopComposeUiTest(width = 412, height = 520) {
        val server = FakeBonifiQServer()
        server.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }
        var cartChanges = 0
        setContent {
            Host {
                Column(Modifier.padding(16.dp)) {
                    PointsBalanceChip(server.sdk, onClick = {})
                    CashbackCheckoutCard(server.sdk, cartId = "of-123", cartTotal = 150.0, onCartChanged = { cartChanges++ })
                }
            }
        }

        waitForText("Usar R$ 75,00 de cashback")
        waitForText("1.500 Coins")
        snapshot("cashback-01-tudo")

        onNodeWithText("Usar R$ 75,00 de cashback").performClick()
        waitForText("Você está usando R$ 75,00 de cashback.")
        snapshot("cashback-02-aplicado")
        assertEquals(75.0, server.appliedCashback)

        onNodeWithText("Remover").performClick()
        waitForText("Usar R$ 75,00 de cashback")
        assertEquals(0.0, server.appliedCashback)

        onNodeWithText("Usar R$ 75,00 de cashback").performClick()
        waitForText("Você está usando R$ 75,00 de cashback.")
        assertEquals(75.0, server.appliedCashback, "aplica tudo de novo, e não R$ 0,00")
        assertEquals(3, cartChanges)
    }

    /**
     * Versão "escolher valor". Regressão: depois de aplicar, o disponível vira R$ 0,00; ao remover, o campo ficava em
     * "0,00" e o botão aplicava R$ 0,01.
     */
    @Test
    fun cashbackCustomAmountFollowsAvailableAndNeverAppliesZero() = runDesktopComposeUiTest(width = 412, height = 560) {
        val server = FakeBonifiQServer()
        server.sdk.auth.loginWithVtex { VtexSessionTokens("s", "g") }
        setContent {
            Host {
                CashbackCheckoutCard(
                    server.sdk,
                    cartId = "of-123",
                    cartTotal = 150.0,
                    onCartChanged = {},
                    modifier = Modifier.padding(16.dp),
                    amount = CashbackAmount.Custom,
                )
            }
        }

        waitForText("Você pode usar até R$ 75,00 nesta compra.")
        onNode(hasSetTextAction()).performTextReplacement("30")
        onNodeWithText("Usar cashback").performClick()
        waitForText("Você está usando R$ 30,00 de cashback.")
        assertEquals(30.0, server.appliedCashback)

        onNodeWithText("Remover").performClick()
        waitForText("Você pode usar até R$ 75,00 nesta compra.")
        onNode(hasSetTextAction()).assertTextContains("75,00")
        snapshot("cashback-03-escolher-valor")

        onNode(hasSetTextAction()).performTextReplacement("0")
        onNodeWithText("Usar cashback").assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextReplacement("80")
        onNodeWithText("Usar cashback").assertIsNotEnabled()
        waitForText("Máximo de R$ 75,00")
        assertEquals(0.0, server.appliedCashback, "nada foi aplicado com 0 ou acima do disponível")
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface(Modifier.fillMaxSize()) { content() } }
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
