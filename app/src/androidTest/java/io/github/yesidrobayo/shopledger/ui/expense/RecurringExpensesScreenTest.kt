package io.github.yesidrobayo.shopledger.ui.expense

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDetails
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los gastos recurrentes en el teléfono: los vencidos llevan `Registrar` y `Omitir`, los
 * programados se editan al tocarlos, cada estado tiene su salida, y los gastos avisan cuántos
 * esperan confirmación. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class RecurringExpensesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<RecurringExpensesAction>()
    private val at = Instant.parse("2026-11-01T12:00:00Z")
    private val today = BusinessDate(LocalDate(2026, 11, 2))

    private fun text(id: Int) = resources.getString(id)

    private fun item(id: Long, description: String, next: LocalDate) = RecurringExpense(
        id = id,
        details = RecurringExpenseDetails(
            expenseCategoryId = 1,
            amount = Money(60_000),
            description = description,
            recurrence = Recurrence.MONTHLY,
            dayOfPeriod = next.day,
            nextDueDate = BusinessDate(next),
            moneyAccountId = null,
            reminderEnabled = false,
        ),
        archivedAt = null,
        createdAt = at,
        updatedAt = at,
    )

    private fun show(state: RecurringExpensesUiState) {
        compose.setContent {
            PreviewTheme { RecurringExpensesScreen(state, { actions += it }) }
        }
    }

    private fun listed(): RecurringExpensesUiState = RecurringExpensesUiState(
        content = RecurringContent.Active(
            due = listOf(item(1, "Alquiler inventado", LocalDate(2026, 11, 1))),
            scheduled = listOf(item(2, "Internet inventado", LocalDate(2026, 11, 5))),
        ),
        today = today,
        weekStartDay = DayOfWeek.MONDAY,
    )

    @Test
    fun aDueOneOffersToRecordOrSkip() {
        show(listed())

        compose.onNodeWithText(text(R.string.recurring_section_due)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recurring_register_action)).performClick()
        compose.onNodeWithText(text(R.string.recurring_skip_action)).performClick()
        compose.onNodeWithText("Internet inventado").performClick()

        assertEquals(
            listOf(
                RecurringExpensesAction.Register(1),
                RecurringExpensesAction.StartSkip(1),
                RecurringExpensesAction.Edit(2),
            ),
            actions,
        )
    }

    @Test
    fun anEmptyListInvitesTheFirstOne() {
        show(RecurringExpensesUiState(content = RecurringContent.Empty, today = today))

        compose.onNodeWithText(text(R.string.recurring_empty_title)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.recurring_new_action))[0].performClick()

        assertEquals(listOf<RecurringExpensesAction>(RecurringExpensesAction.New), actions)
    }

    @Test
    fun aFailedReadOffersToRetry() {
        show(RecurringExpensesUiState(content = RecurringContent.ReadError))

        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertEquals(listOf<RecurringExpensesAction>(RecurringExpensesAction.Retry), actions)
    }

    @Test
    fun theSheetSaysItIsOnlyRecordedWhenConfirmed() {
        val form = RecurringForm(day = 31, nextDate = BusinessDate(LocalDate(2026, 11, 30)))
        show(listed().copy(form = form))

        val help = resources.getString(R.string.recurring_help, text(R.string.app_name))
        scrollUntilDisplayed(help)
        scrollUntilDisplayed(text(R.string.recurring_month_end_help))
    }

    /**
     * Lleva [value] a la vista dentro de la hoja y espera a que se vea. La hoja entra con una
     * animación: con el equipo cargado, un desplazamiento hecho a mitad de camino deja el texto
     * todavía fuera de la pantalla.
     */
    private fun scrollUntilDisplayed(value: String) {
        compose.waitUntil(SHEET_TIMEOUT_MS) {
            compose.onNodeWithText(value).performScrollTo().isDisplayed()
        }
        compose.onNodeWithText(value).assertIsDisplayed()
    }

    @Test
    fun theExpensesSayHowManyWaitAndOpenThem() {
        val expenseActions = mutableListOf<ExpensesAction>()
        compose.setContent {
            PreviewTheme {
                ExpensesScreen(previewExpenses().copy(dueRecurring = 2), { expenseActions += it })
            }
        }

        val due = resources.getQuantityString(R.plurals.expense_recurring_due, 2, 2)
        compose.onNodeWithText(due).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_view)).performClick()

        assertEquals(listOf<ExpensesAction>(ExpensesAction.OpenRecurring), expenseActions)
    }

    private companion object {
        const val SHEET_TIMEOUT_MS = 5_000L
    }
}
