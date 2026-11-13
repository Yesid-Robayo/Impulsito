package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.rule.ReportPeriods
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las devoluciones en los reportes, escritas por sus casos de uso sobre la base real: cada una cuenta
 * en su propia fecha, y `net_sales` y `cogs` de un día, una semana y un mes restan lo devuelto como
 * dice la fórmula de ganancias. El ejemplo es el de la regla: venta en noviembre con lo neto en 30000
 * y costo 18000, y devolución en diciembre de 10000 con costo devuelto 6000. Cada valor esperado
 * está escrito a mano. El producto y los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ReturnReportsOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val stock by lazy { RoomStockLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val sales by lazy { RoomSaleRepository(database) }
    private val reports by lazy { RoomPeriodReportRepository(database) }
    private val salesReports by lazy { RoomSalesReportRepository(database) }
    private val breakdowns by lazy { RoomPeriodBreakdownRepository(database) }

    /** El reloj de los casos de uso: cada prueba lo mueve al día que necesita. */
    private var now: Instant = DECEMBER_NOON
    private var bagId = 0L

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = stock,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { now },
        )
    }

    private val giveBack by lazy {
        RegisterSaleReturnUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            returns = RoomSaleReturnRepository(database),
            units = RoomUnitRepository(database),
            ledger = stock,
            clock = Clock { now },
        )
    }

    /** Un producto a 10000 con diez unidades que valen 6000 cada una. */
    @Before
    fun createProduct() = runBlocking {
        bagId = commit {
            val id = products.insert(newProduct("Bolso inventado").copy(salePrice = Money(10_000)))
            stock.append(
                ledgerRow(
                    id,
                    StockMovementType.OPENING_BALANCE,
                    quantity = 10_000,
                    balanceAfter = 10_000,
                    unitCost = 6_000,
                    totalCost = 60_000,
                    averageCostAfter = 6_000,
                    stockValueAfter = 60_000,
                ),
            )
            id
        }
    }

    /** Tres bolsos vendidos el [date]: lo neto es 30000 y su costo 18000. */
    private suspend fun threeBags(date: BusinessDate): Long {
        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val request = SaleRequest(
            listOf(SaleLineRequest(bagId, QuantityMilli(3_000))),
            cash,
            date = date,
        )
        return (registerSale(request) as RegisterSaleResult.Registered).saleId
    }

    /** Devuelve hoy un bolso de [saleId]: 10000 netos y 6000 de costo que vuelve al stock. */
    private suspend fun oneBagBack(saleId: Long, restock: Boolean = true): BusinessDate {
        val lineId = sales.findLines(saleId).single().id
        val result = giveBack(
            SaleReturnRequest(
                saleId,
                listOf(ReturnLineRequest(lineId, QuantityMilli(1_000), restock)),
                ReturnSettlement.Refund(null),
            ),
        )
        val returnId = (result as RegisterSaleReturnResult.Registered).saleReturnId
        return checkNotNull(RoomSaleReturnRepository(database).findById(returnId))
            .content.businessDate
    }

    private suspend fun totals(period: ReportPeriod): PeriodTotals =
        reports.observeTotals(period).first()

    private fun PeriodTotals.netSales(): Money = profit.salesNetTotal - profit.returnsNetRefund

    private fun PeriodTotals.cogs(): Money = profit.salesTotalCost - profit.returnsCostReturned

    @Test
    fun theRuleExampleKeepsNovemberAndTakesTheReturnFromDecember() = runBlocking {
        val saleId = threeBags(NOVEMBER_20)
        val returnDate = oneBagBack(saleId)
        assertEquals(DECEMBER_3, returnDate)

        val november = totals(ReportPeriods.month(NOVEMBER_20))
        assertEquals(Money(30_000), november.netSales())
        assertEquals(Money(18_000), november.cogs())
        assertEquals(Money(12_000), november.netSales() - november.cogs())

        val december = totals(ReportPeriods.month(DECEMBER_3))
        assertEquals(Money(-10_000), december.netSales())
        assertEquals(Money(-6_000), december.cogs())
        assertEquals(Money(-4_000), december.netSales() - december.cogs())
        assertEquals(0L, december.salesCount)
    }

    @Test
    fun theDayAndTheWeekOfTheReturnCountItAndTheOtherDaysDoNot() = runBlocking {
        val saleId = threeBags(NOVEMBER_20)
        oneBagBack(saleId)
        val weekStart = settings.get().regional.weekStartDay

        val day = totals(ReportPeriods.day(DECEMBER_3))
        assertEquals(Money(-10_000), day.netSales())
        assertEquals(Money(-6_000), day.cogs())
        assertEquals(PeriodTotals.ZERO.profit, totals(ReportPeriods.day(DECEMBER_4)).profit)

        val week = totals(ReportPeriods.week(DECEMBER_3, weekStart))
        assertEquals(Money(-10_000), week.netSales())
        val daily = reports.observeDailyTotals(ReportPeriods.month(DECEMBER_3)).first()
        assertEquals(listOf(DECEMBER_3), daily.map { it.date })
        assertEquals(Money(-4_000), daily.single().totals.netSales() - daily.single().totals.cogs())
    }

    @Test
    fun aReturnOnTheSaleDayLeavesTheDayWithWhatStayedSold() = runBlocking {
        now = NOVEMBER_NOON
        val saleId = threeBags(NOVEMBER_20)
        oneBagBack(saleId)
        // Uno se perdió: su costo sigue en lo vendido.
        oneBagBack(saleId, restock = false)

        val day = totals(ReportPeriods.day(NOVEMBER_20))
        assertEquals(Money(10_000), day.netSales())
        assertEquals(Money(12_000), day.cogs())
        assertEquals(Money(-2_000), day.netSales() - day.cogs())
        val home = salesReports.observeTotals(ReportPeriods.day(NOVEMBER_20)).first()
        assertEquals(Money(30_000), home.salesNetTotal)
        assertEquals(Money(20_000), home.returnsNetRefund)
        assertEquals(Money(6_000), home.returnsCostReturned)
        val ranking = breakdowns.observeProductSales(ReportPeriods.month(NOVEMBER_20)).first()
        assertEquals(QuantityMilli(1_000), ranking.single().quantity)
    }

    private companion object {
        val NOVEMBER_20 = BusinessDate(LocalDate(2026, 11, 20))
        val DECEMBER_3 = BusinessDate(LocalDate(2026, 12, 3))
        val DECEMBER_4 = BusinessDate(LocalDate(2026, 12, 4))

        /** El mediodía del 3 de diciembre y del 20 de noviembre, lejos de cualquier zona. */
        val DECEMBER_NOON: Instant = Instant.parse("2026-12-03T12:00:00Z")
        val NOVEMBER_NOON: Instant = Instant.parse("2026-11-20T12:00:00Z")
    }
}
