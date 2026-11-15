package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.report.CustomerSales
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProduct
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProducts
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodAnalysis
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitConcentration
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitLeader
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.DISCREET_TEXT
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Análisis` en el teléfono: cada parte aparece solo con algo que decir, sin ganancia muestra `—`
 * y por qué, un error deja reintentar, y el modo discreto oculta los montos y deja los nombres.
 * Nombres y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class AnalysisSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private var retries = 0

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: AnalysisUiState, discreet: Boolean = false) {
        compose.setContent {
            PreviewTheme(discreet = discreet) {
                Column { AnalysisSection(state, emptyMap(), onRetry = { retries++ }) }
            }
        }
    }

    @Test
    fun everyPartShowsItsRowsAndTheExtraIdleCount() {
        show(AnalysisUiState.Ready(FULL))

        compose.onNodeWithText(text(R.string.stats_tool_analysis)).assertExists()
        compose.onNodeWithText("Café molido").assertExists()
        compose.onNodeWithText(text(R.string.stats_analysis_idle_title)).assertExists()
        compose.onNodeWithText("Yerba").assertExists()
        compose.onNodeWithText(
            resources.getQuantityString(R.plurals.stats_analysis_idle_more, 2, "2"),
        ).assertExists()
        compose.onNodeWithText(text(R.string.stats_analysis_customers_title)).assertExists()
        // Un cliente anonimizado se nombra como tal, sin dato personal.
        compose.onNodeWithText(text(R.string.customer_anonymous)).assertExists()
    }

    @Test
    fun withoutProfitTheShareIsADashAndANotice() {
        show(AnalysisUiState.Ready(FULL.copy(concentration = ProfitConcentration.NoProfit)))

        compose.onNodeWithText(NO_VALUE_TEXT).assertExists()
        compose.onNodeWithText(text(R.string.stats_analysis_no_profit)).assertExists()
    }

    @Test
    fun aPeriodWithNothingToSayDrawsNothing() {
        show(AnalysisUiState.Hidden)

        assertEquals(
            0,
            compose.onAllNodesWithText(text(R.string.stats_tool_analysis))
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun aReadErrorOffersRetry() {
        show(AnalysisUiState.ReadError)

        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun discreetModeHidesTheAmountsAndKeepsTheNames() {
        show(AnalysisUiState.Ready(FULL), discreet = true)

        compose.onNodeWithText("Café molido").assertExists()
        assertTrue(
            compose.onAllNodesWithText(DISCREET_TEXT, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private companion object {
        val FULL =
            PeriodAnalysis(
                concentration =
                    ProfitConcentration.Leaders(
                        listOf(ProfitLeader(1, "Café molido", Money(8_000), BasisPoints(8_000))),
                        soldProducts = 3,
                        reachedShare = BasisPoints(8_000),
                    ),
                idle =
                    IdleProducts(
                        listOf(IdleProduct(2, "Yerba", 1, QuantityMilli(2_000), Money(9_000))),
                        total = 3,
                    ),
                customers = listOf(CustomerSales(5, null, 2, Money(12_000))),
            )
    }
}
