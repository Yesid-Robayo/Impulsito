package io.github.yesidrobayo.shopledger.ui.expense

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseListDay
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseListRow
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.time.Instant
import kotlinx.datetime.YearMonth

// Vistas previas de los gastos, con datos inventados: una categoría de 40 caracteres, una
// descripción larga, un monto de trece cifras, un gasto anulado y la variante vacía.

private val AT = Instant.fromEpochMilliseconds(0)

internal fun previewCategory(id: Long, key: String?, name: String? = null) = ExpenseCategory(
    id = id,
    systemKey = key,
    details = ExpenseCategoryDetails(name, name?.lowercase(), isFixed = false),
    isSystem = key == "OTHER",
    archivedAt = null,
    createdAt = AT,
    updatedAt = AT,
)

internal fun previewExpenses(): ExpensesUiState {
    val s = PreviewSamples
    val rent = previewCategory(1, "RENT")
    val other = previewCategory(9, "OTHER")
    val long = previewCategory(10, null, "c".repeat(40))
    val today = s.TODAY
    val yesterday = today.plusDays(-1)
    val rows = listOf(
        ExpenseListRow(3, long, s.LONG_NAME, s.LONG_AMOUNT, today, voided = false),
        ExpenseListRow(2, other, "Bolsas de papel", Money(8_000), today, voided = true),
        ExpenseListRow(1, rent, null, Money(600_000), yesterday, voided = false),
    )
    return ExpensesUiState(
        month = YearMonth(today.date.year, today.date.month),
        today = today,
        weekStartDay = kotlinx.datetime.DayOfWeek.MONDAY,
        categories = listOf(rent, other, long),
        content = ExpensesContent.Days(
            listOf(
                ExpenseListDay(today, s.LONG_AMOUNT, rows.take(2)),
                ExpenseListDay(yesterday, Money(600_000), rows.drop(2)),
            ),
        ),
        total = s.LONG_AMOUNT,
    )
}

@ThemePreviews
@Composable
private fun ExpensesListPreview() {
    PreviewTheme { ExpensesScreen(previewExpenses(), {}) }
}

@ThemePreviews
@Composable
private fun ExpensesEmptyPreview() {
    PreviewTheme {
        ExpensesScreen(
            previewExpenses().copy(content = ExpensesContent.Empty, total = Money.ZERO),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun ExpensesFormPreview() {
    val state = previewExpenses()
    PreviewTheme {
        ExpensesScreen(
            state.copy(
                form = ExpenseForm(
                    amountText = "1234567890123",
                    categoryId = 9,
                    description = PreviewSamples.LONG_NAME,
                    errors = setOf(ExpenseFormError.AMOUNT_TOO_HIGH),
                    notice = ExpenseFormNotice.CurrencyUnconfirmed,
                ),
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun ExpensesVoidPreview() {
    val state = previewExpenses()
    val row = (state.content as ExpensesContent.Days).days.last().rows.single()
    PreviewTheme {
        ExpensesScreen(
            state.copy(detail = ExpenseDetailSheet(row, voiding = ExpenseVoidDraft())),
            {},
        )
    }
}
