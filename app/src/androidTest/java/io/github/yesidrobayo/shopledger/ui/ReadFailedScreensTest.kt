package io.github.yesidrobayo.shopledger.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.settings.TaxSettingsAction
import io.github.yesidrobayo.shopledger.ui.settings.TaxSettingsScreen
import io.github.yesidrobayo.shopledger.ui.settings.TaxSettingsUiState
import io.github.yesidrobayo.shopledger.ui.stock.StockAdjustmentAction
import io.github.yesidrobayo.shopledger.ui.stock.StockAdjustmentContent
import io.github.yesidrobayo.shopledger.ui.stock.StockAdjustmentScreen
import io.github.yesidrobayo.shopledger.ui.stock.StockAdjustmentUiState
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.unit.UnitsAction
import io.github.yesidrobayo.shopledger.ui.unit.UnitsScreen
import io.github.yesidrobayo.shopledger.ui.unit.UnitsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Una lectura que falla se ve como un error con `Reintentar`, y el botón pide volver a leer: en
 * unidades, en impuestos y en el ajuste de stock, tres pantallas de grupos distintos.
 */
@RunWith(AndroidJUnit4::class)
class ReadFailedScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int) = resources.getString(id)

    private fun assertErrorWithRetry() {
        compose.onNodeWithText(text(R.string.state_error_read)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
    }

    @Test
    fun unitsShowTheReadErrorAndRetry() {
        val actions = mutableListOf<UnitsAction>()
        compose.setContent {
            val failed = UnitsUiState(loading = false, readFailed = true)
            PreviewTheme { UnitsScreen(failed, onAction = { actions += it }) }
        }

        assertErrorWithRetry()

        assertEquals(listOf<UnitsAction>(UnitsAction.Retry), actions)
    }

    @Test
    fun taxesShowTheReadErrorAndRetry() {
        val actions = mutableListOf<TaxSettingsAction>()
        compose.setContent {
            PreviewTheme { TaxSettingsScreen(TaxSettingsUiState.ReadFailed, { actions += it }) }
        }

        assertErrorWithRetry()

        assertEquals(listOf<TaxSettingsAction>(TaxSettingsAction.Retry), actions)
    }

    @Test
    fun theStockAdjustmentShowsTheReadErrorAndRetry() {
        val actions = mutableListOf<StockAdjustmentAction>()
        compose.setContent {
            PreviewTheme {
                StockAdjustmentScreen(
                    StockAdjustmentUiState(content = StockAdjustmentContent.ReadFailed),
                    { actions += it },
                )
            }
        }

        assertErrorWithRetry()

        assertEquals(listOf<StockAdjustmentAction>(StockAdjustmentAction.Retry), actions)
    }
}
