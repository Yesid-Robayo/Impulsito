package io.github.yesidrobayo.shopledger.ui.expense

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseCategorySummary
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseShareRow
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los gastos en el teléfono: tocar un gasto lo abre, el anulado se ve con su etiqueta, los segmentos
 * y el botón flotante avisan su acción, y cada estado vacío ofrece su salida. Como pestaña va sin
 * segmentos ni atrás. El resumen por categoría lee cada parte del mes, filtra al tocarla y oculta
 * sus montos en modo discreto. Datos inventados.
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
    fun asItsOwnTabTheListShowsWithoutSegmentsNorBack() {
        compose.setContent {
            PreviewTheme { ExpensesScreen(previewExpenses(), { actions += it }, ownTab = true) }
        }

        // La pestaña Gastos abre la misma lista del mes, con su filtro y `Nuevo gasto`.
        compose.onNodeWithText("Bolsas de papel").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.filter_category)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.records_tab_sales)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.records_tab_purchases)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(text(R.string.a11y_back)).assertCountEquals(0)
        compose.onNodeWithContentDescription(text(R.string.a11y_settings)).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.empty_expenses_action)).performClick()
        assertEquals(listOf<ExpensesAction>(ExpensesAction.NewExpense), actions)
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

    @Test
    fun anActiveExpenseOffersVoidAndCorrect() {
        val state = previewExpenses()
        val row = (state.content as ExpensesContent.Days).days.last().rows.single()
        show(state.copy(detail = ExpenseDetailSheet(row)))

        compose.onNodeWithText(text(R.string.dialog_correct_action)).performClick()
        assertEquals(listOf<ExpensesAction>(ExpensesAction.StartCorrect), actions)
    }

    @Test
    fun voidAndCorrectWarnsThatTheOriginalStaysVoided() {
        val state = previewExpenses()
        val row = (state.content as ExpensesContent.Days).days.last().rows.single()
        show(state.copy(detail = ExpenseDetailSheet(row, confirmCorrect = true)))

        compose.onNodeWithText(text(R.string.dialog_correct_expense_body)).assertIsDisplayed()
    }

    private val percent = previewFormats().percent

    private fun showSummary(state: ExpensesUiState, discreet: Boolean = false) {
        compose.setContent {
            PreviewTheme(discreet = discreet) { ExpensesScreen(state, { actions += it }) }
        }
    }

    /** La fila del resumen de [name]: TalkBack la lee como `nombre, monto, parte del mes`. */
    private fun shareRow(name: String, share: BasisPoints, hidden: Boolean? = null) =
        SemanticsMatcher("fila del resumen de $name") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {
                it.startsWith("$name,") &&
                    it.contains(percent.format(share)) &&
                    (hidden == null || it.contains(text(R.string.a11y_discreet_hidden)) == hidden)
            }
        }

    private val rent get() = text(R.string.seed_expense_categories_rent)

    @Test
    fun theSummaryReadsEachCategoryWithItsAmountAndShareOfTheMonth() {
        showSummary(previewExpenses().copy(categorySummary = previewCategorySummary()))

        compose.onNodeWithText(text(R.string.expense_by_category)).assertIsDisplayed()
        compose.onNode(shareRow(rent, BasisPoints(6_098), hidden = false)).assertIsDisplayed()
        compose.onNode(
            shareRow(text(R.string.seed_expense_categories_utilities), BasisPoints(2_439)),
        ).assertIsDisplayed()
        compose.onNode(shareRow("c".repeat(40), BasisPoints(1_463))).assertExists()
    }

    @Test
    fun touchingACategoryOfTheSummaryFiltersTheList() {
        showSummary(previewExpenses().copy(categorySummary = previewCategorySummary()))

        compose.onNode(shareRow(rent, BasisPoints(6_098))).assertIsNotSelected().performClick()

        assertEquals(listOf<ExpensesAction>(ExpensesAction.CategoryFilterChange(1L)), actions)
    }

    @Test
    fun theFilteredCategoryIsMarkedAndTouchingItAgainClearsTheFilter() {
        showSummary(
            previewExpenses().copy(categorySummary = previewCategorySummary(), categoryId = 1L),
        )

        compose.onNode(shareRow(rent, BasisPoints(6_098))).assertIsSelected().performClick()

        assertEquals(listOf<ExpensesAction>(ExpensesAction.CategoryFilterChange(null)), actions)
    }

    @Test
    fun theRowOfOtherCategoriesOpensTheCategories() {
        val summary =
            ExpenseCategorySummary(
                total = Money(1_000),
                rows =
                    listOf(
                        ExpenseShareRow.Category(
                            ExpenseCategoryTotal(1L, null, "RENT", Money(900)),
                            BasisPoints(9_000),
                        ),
                        ExpenseShareRow.Others(2, Money(100), BasisPoints(1_000)),
                    ),
            )
        showSummary(previewExpenses().copy(categorySummary = summary))

        val others = resources.getQuantityString(R.plurals.expense_share_others, 2, 2)
        compose.onNode(shareRow(others, BasisPoints(1_000))).performClick()

        assertEquals(listOf<ExpensesAction>(ExpensesAction.OpenCategories), actions)
    }

    @Test
    fun discreetModeHidesTheAmountsOfTheSummaryAndKeepsTheShares() {
        showSummary(
            previewExpenses().copy(categorySummary = previewCategorySummary()),
            discreet = true,
        )

        compose.onNode(shareRow(rent, BasisPoints(6_098), hidden = true)).assertIsDisplayed()
    }

    @Test
    fun aSingleCategoryIsTheWholeMonth() {
        val summary =
            ExpenseCategorySummary(
                total = Money(35_000),
                rows =
                    listOf(
                        ExpenseShareRow.Category(
                            ExpenseCategoryTotal(1L, null, "RENT", Money(35_000)),
                            BasisPoints(10_000),
                        ),
                    ),
            )
        showSummary(previewExpenses().copy(categorySummary = summary))

        compose.onNode(shareRow(rent, BasisPoints(10_000))).assertIsDisplayed()
    }

    @Test
    fun anEmptyMonthHasNoSummary() {
        showSummary(
            previewExpenses().copy(
                content = ExpensesContent.Empty,
                categorySummary = previewCategorySummary(),
            ),
        )

        compose.onAllNodesWithText(text(R.string.expense_by_category)).assertCountEquals(0)
    }
}
