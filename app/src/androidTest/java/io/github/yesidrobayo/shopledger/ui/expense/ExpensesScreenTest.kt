package io.github.yesidrobayo.shopledger.ui.expense

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los gastos en el teléfono: tocar un gasto lo abre, el anulado se ve con su etiqueta, los segmentos
 * y el botón flotante avisan su acción, y cada estado vacío ofrece su salida. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class ExpensesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<ExpensesAction>()

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: ExpensesUiState) {
        compose.setContent { PreviewTheme { ExpensesScreen(state, { actions += it }) } }
    }

    @Test
    fun touchingAnExpenseOpensIt() {
        show(previewExpenses())

        compose.onNodeWithText("Bolsas de papel").performClick()

        assertEquals(listOf<ExpensesAction>(ExpensesAction.OpenExpense(2L)), actions)
    }

    @Test
    fun aVoidedExpenseShowsItsTag() {
        show(previewExpenses())

        // La etiqueta va dentro de la lectura del monto, para que TalkBack no la repita.
        val voided = resources.getString(R.string.a11y_voided_expense_amount, "")
        compose.onNodeWithContentDescription(voided, substring = true).assertIsDisplayed()
    }

    @Test
    fun theSegmentsAndTheButtonReportTheirAction() {
        show(previewExpenses())

        compose.onNodeWithText(text(R.string.records_tab_sales)).performClick()
        compose.onNodeWithText(text(R.string.records_tab_purchases)).performClick()
        compose.onNodeWithText(text(R.string.empty_expenses_action)).performClick()

        assertEquals(
            listOf(
                ExpensesAction.ShowSales,
                ExpensesAction.ShowPurchases,
                ExpensesAction.NewExpense,
            ),
            actions,
        )
    }

    @Test
    fun anEmptyMonthInvitesTheFirstExpense() {
        show(previewExpenses().copy(content = ExpensesContent.Empty))

        compose.onNodeWithText(text(R.string.empty_expenses_title)).assertIsDisplayed()
        // El botón flotante y el del estado vacío dicen lo mismo: los dos abren el gasto nuevo.
        val buttons = compose.onAllNodesWithText(text(R.string.empty_expenses_action))
        buttons[0].performClick()
        buttons[1].performClick()
        assertEquals(List(2) { ExpensesAction.NewExpense }, actions)
    }

    @Test
    fun anEmptyFilterClearsTheCategory() {
        show(previewExpenses().copy(content = ExpensesContent.EmptyFiltered))

        compose.onNodeWithText(text(R.string.action_clear_filters)).performClick()

        assertEquals(listOf<ExpensesAction>(ExpensesAction.CategoryFilterChange(null)), actions)
    }

    @Test
    fun aReadErrorCanBeRetried() {
        show(previewExpenses().copy(content = ExpensesContent.ReadError))

        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertTrue(ExpensesAction.Retry in actions)
    }
}
