package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.expense.ExpensesAction
import io.github.yesidrobayo.shopledger.ui.expense.ExpensesScreen
import io.github.yesidrobayo.shopledger.ui.expense.ExpensesUiState
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseHistoryAction
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseHistoryScreen
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseHistoryUiState
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: como segmentos de Registros, `Ventas`, `Compras` y `Gastos` llevan la misma
 * barra de arriba, sin atrás y con los ajustes, para que cambiar de uno a otro no mueva nada. Como
 * destino propio, compras y gastos van sin segmentos, con su nombre y con atrás.
 */
@RunWith(AndroidJUnit4::class)
class RecordsSegmentsTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int) = resources.getString(id)

    private fun show(screen: @Composable () -> Unit) {
        compose.setContent { PreviewTheme { screen() } }
    }

    /** La barra de la raíz: `Registros`, los tres segmentos, los ajustes y ninguna flecha. */
    private fun assertRootBar() {
        compose.onNodeWithText(text(R.string.nav_records)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.records_tab_sales)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.a11y_settings)).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(text(R.string.a11y_back)).assertCountEquals(0)
    }

    @Test
    fun salesHasTheRootBar() {
        show { SaleHistoryScreen(SaleHistoryUiState(), {}) }
        assertRootBar()
    }

    @Test
    fun purchasesAsASegmentHaveTheRootBar() {
        show { PurchaseHistoryScreen(PurchaseHistoryUiState(), {}) }
        assertRootBar()
    }

    @Test
    fun expensesAsASegmentHaveTheRootBar() {
        show { ExpensesScreen(ExpensesUiState(), {}) }
        assertRootBar()
    }

    @Test
    fun purchasesOnTheirOwnGoBackWithoutSegments() {
        val actions = mutableListOf<PurchaseHistoryAction>()
        show { PurchaseHistoryScreen(PurchaseHistoryUiState(), { actions += it }, asTab = false) }

        compose.onNodeWithText(text(R.string.records_tab_purchases)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.records_tab_sales)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(text(R.string.a11y_settings)).assertCountEquals(0)
        compose.onNodeWithContentDescription(text(R.string.a11y_back)).performClick()

        assertEquals(listOf<PurchaseHistoryAction>(PurchaseHistoryAction.ShowSales), actions)
    }

    @Test
    fun expensesOnTheirOwnGoBackWithoutSegments() {
        val actions = mutableListOf<ExpensesAction>()
        show { ExpensesScreen(ExpensesUiState(), { actions += it }, asTab = false) }

        compose.onNodeWithText(text(R.string.records_tab_expenses)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.records_tab_sales)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(text(R.string.a11y_settings)).assertCountEquals(0)
        compose.onNodeWithContentDescription(text(R.string.a11y_back)).performClick()

        assertEquals(listOf<ExpensesAction>(ExpensesAction.ShowSales), actions)
    }
}
