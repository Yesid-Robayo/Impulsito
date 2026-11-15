package io.github.yesidrobayo.shopledger.data.local.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.repository.RoomPlanningReportRepository
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que suma la planeación sobre Room: la primera venta activa, desde la que el límite de
 * ingresos dice que suma, y los gastos fijos del punto de equilibrio. Montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PlanningReportQueryTest : LedgerDatabaseTest() {
    private val planning by lazy { RoomPlanningReportRepository(database) }

    private suspend fun saleOn(date: String): Long =
        database.saleDao().insert(sale(priceListId, businessDate = date))

    private fun date(text: String) = BusinessDate(LocalDate.parse(text))

    @Test
    fun withoutSalesThereIsNoFirstDate() = runBlocking {
        assertNull(planning.observeFirstSaleDate().first())
    }

    @Test
    fun theFirstActiveSaleIsTheFirstDate() = runBlocking {
        saleOn("2026-03-09")
        saleOn("2026-05-02")
        // Una venta anulada antes de todas ya no cuenta.
        database.saleDao().markVoided(saleOn("2026-01-15"), CREATED_AT, "ENTRY_ERROR", null)

        assertEquals(date("2026-03-09"), planning.observeFirstSaleDate().first())
    }

    @Test
    fun onlyActiveExpensesOfFixedCategoriesInThePeriodAreFixedExpenses() = runBlocking {
        val november = ReportPeriod.Month(YearMonth(2026, 11))
        assertEquals(Money.ZERO, planning.observeFixedExpenses(november).first())

        val rent = database.expenseCategoryDao().insert(expenseCategory("Local", isFixed = 1))
        val trips = database.expenseCategoryDao().insert(expenseCategory("Viajes"))
        database.expenseDao().insert(expense(rent, businessDate = "2026-11-05"))
        database.expenseDao().insert(expense(rent, businessDate = "2026-11-30"))
        // Fuera del mes, de una categoría que no es fija, o anulado: no cuenta.
        database.expenseDao().insert(expense(rent, businessDate = "2026-12-01"))
        database.expenseDao().insert(expense(trips, businessDate = "2026-11-10"))
        val voided = database.expenseDao().insert(expense(rent, businessDate = "2026-11-12"))
        database.expenseDao().markVoided(voided, CREATED_AT, "ENTRY_ERROR", null)

        // Cada gasto inventado vale 45000.
        assertEquals(Money(90_000), planning.observeFixedExpenses(november).first())
    }
}
