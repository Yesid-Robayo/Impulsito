package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowHorizon
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowProjection
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Flujo proyectado` en el teléfono: rotulado como estimación, con lo disponible y los dos
 * horizontes como filas; un error ofrece reintentar, y sin datos no aparece. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class CashFlowToolTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val today = BusinessDate(LocalDate(2026, 11, 2))

    private fun text(id: Int) = resources.getString(id)

    private fun horizon(days: Int, projected: Long) = CashFlowHorizon(
        days = days,
        until = today.plusDays(days),
        settlements = Money(15_000),
        receivables = Money(20_000),
        payables = Money(30_000),
        recurring = Money(25_000),
        projected = Money(projected),
    )

    @Test
    fun theProjectionIsLabelledAsAnEstimate() {
        val horizons = listOf(horizon(7, 65_000), horizon(30, -5_000))
        val projection = CashFlowProjection(today, Money(85_000), horizons)
        compose.setContent {
            PreviewTheme { CashFlowTool(CashFlowUiState.Ready(projection), onRetry = {}) }
        }

        compose.onNodeWithText(text(R.string.stats_tool_cash_flow)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cash_flow_estimate_tag)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cash_flow_available_today)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cash_flow_in_7_days)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cash_flow_in_30_days)).assertIsDisplayed()
    }

    @Test
    fun aFailedReadOffersToRetryAndHiddenDrawsNothing() {
        var retries = 0
        compose.setContent {
            PreviewTheme { CashFlowTool(CashFlowUiState.Failed, onRetry = { retries++ }) }
        }

        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun withoutDataTheToolDoesNotAppear() {
        compose.setContent {
            PreviewTheme { CashFlowTool(CashFlowUiState.Hidden, onRetry = {}) }
        }

        compose.onNodeWithText(text(R.string.stats_tool_cash_flow)).assertDoesNotExist()
    }
}
