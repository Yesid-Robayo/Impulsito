package io.github.yesidrobayo.shopledger.data.local.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleLine
import io.github.yesidrobayo.shopledger.data.local.saleReturn
import io.github.yesidrobayo.shopledger.data.local.saleReturnLine
import io.github.yesidrobayo.shopledger.data.repository.RoomPeriodReportRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomReplacementReportRepository
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementProfit
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementTotals
import io.github.yesidrobayo.shopledger.domain.model.report.RestockedReturnLine
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.report.ReplacementProfitRules
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveReplacementProfitUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que la ganancia si se repone lee de la base real: la reposición congelada en las ventas
 * activas del día, las líneas sin reposición, y las líneas devueltas al stock en devoluciones
 * activas, cada una con lo que ya se había devuelto antes de su línea. Y la ganancia en ventas, que
 * la reposición no cambia. Cada valor esperado está escrito a mano. Montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ReplacementReportQueryTest : LedgerDatabaseTest() {
    private val sales get() = database.saleDao()
    private val lines get() = database.saleLineDao()
    private val returns get() = database.saleReturnDao()
    private val returnLines get() = database.saleReturnLineDao()
    private val replacement by lazy { RoomReplacementReportRepository(database) }
    private val profits by lazy {
        ObserveReplacementProfitUseCase(RoomPeriodReportRepository(database), replacement)
    }

    /** Una venta activa en [date] de [net] sin impuesto, que costó [cost] y repone [replacement]. */
    private suspend fun saleOf(date: String, net: Long, cost: Long, replacement: Long): Long =
        sales.insert(
            sale(priceListId, businessDate = date).copy(
                netTotal = net,
                taxTotal = 0,
                total = net,
                totalCost = cost,
                totalReplacementCost = replacement,
                orderAdjustment = 0,
                tipAmount = 0,
                cashRoundingAmount = 0,
                amountDue = net,
            ),
        )

    /** Una línea de [saleId] de [milli] milésimas, que repone [replacement] en total. */
    private suspend fun lineOf(saleId: Long, milli: Long, replacement: Long): Long = lines.insert(
        saleLine(saleId, productId, priceListId).copy(
            enteredQuantityMilli = milli,
            quantityMilli = milli,
            unitReplacementCost = if (replacement == 0L) 0 else replacement * 1_000 / milli,
            lineReplacementCost = replacement,
        ),
    )

    /** Una devolución en [date] de [milli] de la línea [lineId], al stock si [restock]. */
    private suspend fun returnOf(
        saleId: Long,
        lineId: Long,
        date: String,
        milli: Long,
        restock: Boolean = true,
    ): Long {
        val returnId = returns.insert(saleReturn(saleId).copy(businessDate = date))
        returnLines.insert(
            saleReturnLine(returnId, lineId).copy(
                quantityMilli = milli,
                restock = if (restock) 1 else 0,
            ),
        )
        return returnId
    }

    private fun day(text: String) = ReportPeriod.Day(BusinessDate(LocalDate.parse(text)))

    private fun restocked(before: Long, now: Long) = RestockedReturnLine(
        Money(10_000),
        QuantityMilli(3_000),
        QuantityMilli(before),
        QuantityMilli(now),
    )

    @Test
    fun onlyActiveSalesOfTheDayAddTheirReplacementAndCountLinesWithoutIt() = runBlocking {
        val today = saleOf(TODAY, net = 16_000, cost = 8_000, replacement = 12_000)
        lineOf(today, 8_000, 12_000)
        lineOf(today, 1_000, 0)
        lineOf(saleOf(TODAY, net = 3_000, cost = 1_000, replacement = 2_000), 1_000, 2_000)
        // Ni una venta anulada ni una de ayer cuentan, aunque tengan líneas sin reposición.
        val voided = saleOf(TODAY, net = 7_000, cost = 700, replacement = 7_777)
        lineOf(voided, 1_000, 0)
        sales.markVoided(voided, CREATED_AT, "ENTRY_ERROR", null)
        lineOf(saleOf(YESTERDAY, net = 9_000, cost = 900, replacement = 9_999), 1_000, 0)

        assertEquals(
            ReplacementTotals(Money(14_000), 1, emptyList()),
            replacement.observeTotals(day(TODAY)).first(),
        )
    }

    @Test
    fun restockedReturnsCarryWhatWasReturnedBeforeFromActiveReturnsOnly() = runBlocking {
        val saleId = saleOf(YESTERDAY, net = 30_000, cost = 6_000, replacement = 10_000)
        val lineId = lineOf(saleId, 3_000, 10_000)
        returnOf(saleId, lineId, TODAY, 1_000)
        // Una devolución anulada no cuenta como devuelta antes, ni aparece.
        returns.markVoided(
            returnOf(saleId, lineId, TODAY, 1_000),
            CREATED_AT,
            "ENTRY_ERROR",
            null,
        )
        // Lo que no vuelve al stock se perdió: hay que reponerlo, y no aparece.
        returnOf(saleId, lineId, TODAY, 1_000, restock = false)
        returnOf(saleId, lineId, TOMORROW, 1_000)

        assertEquals(
            listOf(restocked(0, 1_000)),
            replacement.observeTotals(day(TODAY)).first().restockedReturns,
        )
        assertEquals(
            listOf(restocked(2_000, 1_000)),
            replacement.observeTotals(day(TOMORROW)).first().restockedReturns,
        )
    }

    @Test
    fun voidingAReturnInTheMiddleKeepsTheReturnedReplacementExactInAnyOrder() = runBlocking {
        // Una línea de 3 unidades que repone 10000, devuelta de a 1 tres veces. En un día se anula
        // la del medio y en otro la primera: las dos activas reparten igual, y suman 6667, lo que
        // toca a 2 de 3 unidades.
        for ((date, voided) in listOf(TODAY to 1, TOMORROW to 0)) {
            val saleId = saleOf(YESTERDAY, net = 30_000, cost = 6_000, replacement = 10_000)
            val lineId = lineOf(saleId, 3_000, 10_000)
            val ids = List(3) { returnOf(saleId, lineId, date, 1_000) }
            returns.markVoided(ids[voided], CREATED_AT, "ENTRY_ERROR", null)

            val lines = replacement.observeTotals(day(date)).first().restockedReturns
            assertEquals(listOf(restocked(0, 1_000), restocked(1_000, 1_000)), lines)
            val shares = lines.map { ReplacementProfitRules.returnedShare(it).valueOrNull() }
            assertEquals(listOf(Money(3_333), Money(3_334)), shares)
        }
    }

    @Test
    fun theReplacementNeverChangesTheGrossProfit() = runBlocking {
        // El ejemplo de la regla: 8 unidades por 16000, costo promedio 8000 y reposición 12000.
        val saleId = saleOf(TODAY, net = 16_000, cost = 8_000, replacement = 12_000)
        lineOf(saleId, 8_000, 12_000)

        val report = profits(day(TODAY)).first()

        assertEquals(Money(8_000), report.grossProfit)
        assertEquals(
            ReplacementProfit(Money(16_000), Money(12_000), Money(4_000), 0),
            report.replacement,
        )
    }

    private companion object {
        const val YESTERDAY = "2026-11-01"
        const val TODAY = "2026-11-02"
        const val TOMORROW = "2026-11-03"
    }
}
