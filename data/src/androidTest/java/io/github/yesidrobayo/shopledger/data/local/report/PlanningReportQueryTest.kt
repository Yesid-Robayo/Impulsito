package io.github.yesidrobayo.shopledger.data.local.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.repository.RoomPlanningReportRepository
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que suma la planeación sobre Room: la primera venta activa, desde la que el límite de
 * ingresos dice que suma. Montos inventados.
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
}
