package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.repository.RoomPeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La última fecha con datos, hasta donde las estadísticas dejan avanzar: la mayor fecha de una
 * venta o un gasto activos, aunque sea posterior a hoy, leída desde el final de sus índices. Los
 * montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class LastActiveDateQueryTest : LedgerDatabaseTest() {
    private val reports by lazy { RoomPeriodReportRepository(database) }

    private suspend fun saleOn(date: String): Long =
        database.saleDao().insert(sale(priceListId, businessDate = date))

    private suspend fun expenseOn(date: String): Long {
        val categoryId = database.expenseCategoryDao().insert(expenseCategory("Gasto $date"))
        return database.expenseDao().insert(expense(categoryId, businessDate = date))
    }

    @Test
    fun withoutSalesOrExpensesThereIsNoDate() = runBlocking {
        assertNull(reports.observeLastActiveDate().first())
    }

    @Test
    fun theLatestActiveSaleOrExpenseWinsEvenAfterToday() = runBlocking {
        saleOn("2026-10-30")
        expenseOn("2026-11-04")
        // Un reloj adelantado dejó una venta en diciembre, que se anuló: ya no cuenta.
        database.saleDao().markVoided(saleOn("2026-12-20"), CREATED_AT, "ENTRY_ERROR", null)

        assertEquals(date("2026-11-04"), reports.observeLastActiveDate().first())

        saleOn("2026-12-15")
        assertEquals(date("2026-12-15"), reports.observeLastActiveDate().first())
    }

    @Test
    fun onlyAnExpenseIsEnough() = runBlocking {
        expenseOn("2025-03-01")
        assertEquals(date("2025-03-01"), reports.observeLastActiveDate().first())
    }

    @Test
    fun aNewSaleEmitsTheNewDate() = runBlocking {
        val emissions = Emissions(this, reports.observeLastActiveDate())
        try {
            emissions.awaitValue(null)
            saleOn("2026-11-02")
            emissions.awaitValue(date("2026-11-02"))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun eachMaximumComesFromTheDateIndex() = runBlocking {
        val plan =
            database.useReaderConnection { connection ->
                connection.usePrepared("EXPLAIN QUERY PLAN $LAST_ACTIVE_DATE_SQL") { statement ->
                    // La cuarta columna del plan es el detalle de cada paso.
                    buildList { while (statement.step()) add(statement.getText(3)) }
                }
            }
        for (table in listOf("sales", "expenses")) {
            assertTrue(
                "Plan sin el índice de $table: $plan",
                plan.any { it.contains("index_${table}_status_business_date") },
            )
        }
        assertFalse(
            "Un máximo recorre su tabla entera: $plan",
            plan.any { it.startsWith("SCAN") && !it.contains("CONSTANT ROW") },
        )
    }

    private fun date(text: String) = BusinessDate(LocalDate.parse(text))
}
