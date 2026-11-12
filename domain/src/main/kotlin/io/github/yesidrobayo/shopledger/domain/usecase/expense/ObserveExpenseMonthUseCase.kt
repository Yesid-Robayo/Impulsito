package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.expense.Expense
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull
import io.github.yesidrobayo.shopledger.domain.port.ExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.YearMonth

/**
 * Un gasto de la lista del mes, con su categoría ya resuelta. [voided] lo muestra tachado y fuera de
 * los totales: se conserva para la historia.
 */
data class ExpenseListRow(
    val id: Long,
    val category: ExpenseCategory,
    val description: String?,
    val amount: Money,
    val date: BusinessDate,
    val voided: Boolean,
)

/** Un día de la lista, con lo gastado ese día en gastos activos. */
data class ExpenseListDay(val date: BusinessDate, val total: Money?, val rows: List<ExpenseListRow>)

/**
 * Los gastos de un mes, con el filtro de categoría aplicado. [total] es lo gastado en gastos activos
 * del filtro, o `null` si la suma no cabe. [categories] son todas, vigentes y archivadas, para el
 * filtro: un gasto viejo puede estar en una archivada. [hasAny] dice si el mes tiene algún gasto,
 * aunque el filtro no deje ver ninguno.
 */
data class ExpenseMonth(
    val month: YearMonth,
    val categoryId: Long?,
    val days: List<ExpenseListDay>,
    val total: Money?,
    val categories: List<ExpenseCategory>,
    val hasAny: Boolean,
)

/**
 * Lee los gastos de un mes por día, del más nuevo al más antiguo, con el total del mes. Un mes trae
 * pocos gastos aun en un negocio de años, así que el filtro por categoría se aplica aquí, sobre la
 * lectura del mes por su índice de fecha. No escribe nada.
 */
class ObserveExpenseMonthUseCase(
    private val expenses: ExpenseRepository,
    private val categories: ExpenseCategoryRepository,
) {
    operator fun invoke(month: YearMonth, categoryId: Long?): Flow<ExpenseMonth> = combine(
        expenses.observeIn(ReportPeriod.Month(month)),
        categories.observe(ArchiveFilter.ACTIVE),
        categories.observe(ArchiveFilter.ARCHIVED),
    ) { list, active, archived ->
        val all = active + archived
        val byId = all.associateBy { it.id }
        val shown = list.filter { categoryId == null || it.content.expenseCategoryId == categoryId }
        val rows = shown.mapNotNull { expense ->
            byId[expense.content.expenseCategoryId]?.let { row(expense, it) }
        }
        ExpenseMonth(
            month = month,
            categoryId = categoryId,
            days = rows.groupBy { it.date }.map { (date, dayRows) ->
                ExpenseListDay(date, activeTotal(dayRows), dayRows)
            },
            total = activeTotal(rows),
            categories = all.sortedBy { it.id },
            hasAny = list.isNotEmpty(),
        )
    }.distinctUntilChanged()

    private fun row(expense: Expense, category: ExpenseCategory) = ExpenseListRow(
        id = expense.id,
        category = category,
        description = expense.content.description,
        amount = expense.content.amount,
        date = expense.content.businessDate,
        voided = expense.status == DocumentStatus.VOIDED,
    )

    private fun activeTotal(rows: List<ExpenseListRow>): Money? =
        rows.filter { !it.voided }.map { it.amount }.sumOrNull()
}
