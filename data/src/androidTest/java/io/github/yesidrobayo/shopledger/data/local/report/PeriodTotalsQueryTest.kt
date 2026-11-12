package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.data.local.movement
import io.github.yesidrobayo.shopledger.data.local.openingBalance
import io.github.yesidrobayo.shopledger.data.local.payment.cardPayment
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleReturn
import io.github.yesidrobayo.shopledger.data.repository.RoomPeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayTotals
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.ReportPeriods
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sumas de ganancia de un día, una semana, un mes o cada día de un período, sobre la base real:
 * solo documentos activos, cada uno en su propia fecha de negocio, la fecha guardada con la zona
 * del negocio de cuando se escribió, y semanas que empiezan en el día que elige el negocio. Cada
 * valor esperado está escrito a mano. Los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PeriodTotalsQueryTest : LedgerDatabaseTest() {
    private val sales get() = database.saleDao()
    private val returns get() = database.saleReturnDao()
    private val expenses get() = database.expenseDao()
    private val ledger get() = database.stockMovementDao()
    private val reports by lazy { RoomPeriodReportRepository(database) }

    private var categoryId = 0L
    private var cardId = 0L

    @Before
    fun createCategoryAndMethod() = runBlocking {
        categoryId = database.expenseCategoryDao().insert(expenseCategory("Arriendo inventado"))
        cardId = requireNotNull(database.paymentMethodDao().findBySystemKey("CARD")) {
            "Falta el método inicial de tarjeta"
        }.id
    }

    /**
     * Una venta activa de [net] sin impuesto ni descuento que costó [cost], en [date], con una
     * propina de 1.000 que ninguna suma de ganancia debe leer y el redondeo de efectivo [rounding].
     */
    private suspend fun saleOf(
        date: String,
        net: Long,
        cost: Long,
        rounding: Long = 0,
        occurredAt: Long = CREATED_AT,
    ): Long = sales.insert(
        sale(priceListId, businessDate = date, occurredAt = occurredAt).copy(
            netTotal = net,
            taxTotal = 0,
            total = net,
            totalCost = cost,
            orderAdjustment = 0,
            tipAmount = TIP,
            cashRoundingAmount = rounding,
            amountDue = net + TIP + rounding,
        ),
    )

    /** Un pago con tarjeta de [saleId] que pagó [fee] de comisión. */
    private suspend fun feeOf(saleId: Long, fee: Long) {
        database.salePaymentDao().insert(cardPayment(saleId, cardId).copy(feeAmount = fee))
    }

    /** Una devolución activa de [saleId] por [net] sin impuesto, que devolvió [cost], en [date]. */
    private suspend fun returnOf(saleId: Long, date: String, net: Long, cost: Long): Long =
        returns.insert(
            saleReturn(saleId).copy(
                netRefund = net,
                taxRefund = 0,
                totalRefund = net,
                costReturned = cost,
                businessDate = date,
            ),
        )

    /** Un gasto activo de [amount] en [date]. */
    private suspend fun expenseOf(date: String, amount: Long): Long =
        expenses.insert(expense(categoryId, businessDate = date).copy(amount = amount))

    /**
     * Un ajuste de stock en [date] que vale [value] con su signo: negativo si salió algo, positivo
     * si apareció. Los saldos de la fila no importan a estas sumas.
     */
    private suspend fun adjustmentOf(date: String, value: Long): Long {
        val reason = if (value < 0) "DAMAGED" else "OTHER"
        val quantity = if (value < 0) -1_000L else 1_000L
        return ledger.insert(
            movement(productId, "ADJUSTMENT", quantity, 1_000, value, 0, 1_000, 0).copy(
                reason = reason,
                note = "Inventado",
                businessDate = date,
            ),
        )
    }

    /** La anulación del ajuste [adjustmentId], que se escribe con la fecha [date] de anular. */
    private suspend fun voidAdjustment(adjustmentId: Long, date: String, value: Long) {
        ledger.insert(
            movement(productId, "ADJUSTMENT_VOID", 1_000, 1_000, -value, 0, 1_000, 0).copy(
                reversesMovementId = adjustmentId,
                businessDate = date,
            ),
        )
    }

    private fun day(text: String) = ReportPeriod.Day(date(text))

    private fun totals(
        net: Long = 0,
        cost: Long = 0,
        count: Long = 0,
        rounding: Long = 0,
        refund: Long = 0,
        costReturned: Long = 0,
        expenses: Long = 0,
        shrinkage: Long = 0,
        fees: Long = 0,
    ) = PeriodTotals(
        ProfitTotals(
            salesNetTotal = Money(net),
            returnsNetRefund = Money(refund),
            salesTotalCost = Money(cost),
            returnsCostReturned = Money(costReturned),
            recordedExpenses = Money(expenses),
            netShrinkage = Money(shrinkage),
            paymentFees = Money(fees),
            cashRoundingTotal = Money(rounding),
        ),
        salesCount = count,
    )

    @Test
    fun everySumCountsOnlyActiveDocumentsOfTheDayEachWithItsOwnDate() = runBlocking {
        val first = saleOf("2026-11-02", net = 40_000, cost = 25_000, rounding = -100)
        feeOf(first, 300)
        feeOf(first, 0)
        saleOf("2026-11-02", net = 12_500, cost = 8_000, rounding = 50)
        // Una venta anulada no suma, ni su redondeo ni las comisiones de sus pagos.
        val voided = saleOf("2026-11-02", net = 7_777, cost = 777, rounding = -40)
        feeOf(voided, 99)
        sales.markVoided(voided, CREATED_AT, "ENTRY_ERROR", null)
        // La comisión cuenta en la fecha de su venta: la de mañana no.
        feeOf(saleOf("2026-11-03", net = 8_888, cost = 888), 77)
        // Una devolución resta en su fecha, aunque su venta sea de ayer.
        val yesterday = saleOf("2026-11-01", net = 9_999, cost = 1_111)
        returnOf(yesterday, "2026-11-02", net = 2_500, cost = 1_000)
        returns.markVoided(
            returnOf(first, "2026-11-02", net = 300, cost = 100),
            CREATED_AT,
            "ENTRY_ERROR",
            null,
        )
        expenseOf("2026-11-02", 45_000)
        expenses.markVoided(expenseOf("2026-11-02", 10_000), CREATED_AT, "ENTRY_ERROR", null)
        expenseOf("2026-11-01", 5_000)
        // Merma: faltan 2.710 y aparecen 1.355. El ajuste anulado mañana deja de contar hoy, y su
        // anulación tampoco suma mañana. Un inventario inicial no es merma.
        adjustmentOf("2026-11-02", -2_710)
        adjustmentOf("2026-11-02", 1_355)
        val expired = adjustmentOf("2026-11-02", -4_065)
        voidAdjustment(expired, "2026-11-03", -4_065)
        ledger.insert(openingBalance(productId, businessDate = "2026-11-02"))
        adjustmentOf("2026-11-01", -999)

        assertEquals(
            totals(
                net = 52_500,
                cost = 33_000,
                count = 2,
                rounding = -50,
                refund = 2_500,
                costReturned = 1_000,
                expenses = 45_000,
                shrinkage = 1_355,
                fees = 300,
            ),
            reports.observeTotals(day("2026-11-02")).first(),
        )
        assertEquals(
            totals(net = 8_888, cost = 888, count = 1, fees = 77),
            reports.observeTotals(day("2026-11-03")).first(),
        )
    }

    @Test
    fun aPeriodWithoutDocumentsSumsZero() = runBlocking {
        val month = ReportPeriod.Month(YearMonth(2026, 11))
        assertEquals(PeriodTotals.ZERO, reports.observeTotals(month).first())
        assertEquals(emptyList<DayTotals>(), reports.observeDailyTotals(month).first())
    }

    @Test
    fun theMonthOfTheRuleExampleGivesItsProfitAndAVoidOnlyChangesThatMonth() = runBlocking {
        // El mes de ejemplo: ventas por 4.250.000 que costaron 2.720.000, gastos por 640.000 y
        // daños y pérdidas por 85.000.
        saleOf("2026-10-05", net = 4_130_000, cost = 2_650_000)
        val late = saleOf("2026-10-30", net = 120_000, cost = 70_000)
        expenseOf("2026-10-10", 600_000)
        expenseOf("2026-10-20", 40_000)
        adjustmentOf("2026-10-15", -85_000)
        val october = ReportPeriod.Month(YearMonth(2026, 10))
        val november = ReportPeriod.Month(YearMonth(2026, 11))

        assertEquals(
            ProfitSummary(
                netSales = Money(4_250_000),
                cogs = Money(2_720_000),
                grossProfit = Money(1_530_000),
                grossMargin = Margin.Defined(BasisPoints(3_600)),
                recordedExpenses = Money(640_000),
                netShrinkage = Money(85_000),
                paymentCosts = Money.ZERO,
                totalExpenses = Money(725_000),
                netProfit = Money(805_000),
            ),
            profitOf(october),
        )

        // La venta de 120.000 que costó 70.000 se anula el 3 de noviembre: sale de octubre y
        // noviembre no cambia.
        sales.markVoided(late, NOVEMBER_3, "ENTRY_ERROR", null)

        assertEquals(
            ProfitSummary(
                netSales = Money(4_130_000),
                cogs = Money(2_650_000),
                grossProfit = Money(1_480_000),
                grossMargin = Margin.Defined(BasisPoints(3_584)),
                recordedExpenses = Money(640_000),
                netShrinkage = Money(85_000),
                paymentCosts = Money.ZERO,
                totalExpenses = Money(725_000),
                netProfit = Money(755_000),
            ),
            profitOf(october),
        )
        assertEquals(PeriodTotals.ZERO, reports.observeTotals(november).first())
    }

    @Test
    fun returnsFeesAndCashRoundingOfTheRuleExampleEnterTheMonth() = runBlocking {
        // El mismo mes con devoluciones por 50.000 que devolvieron 30.000 de costo, comisiones por
        // 42.000 y redondeo de efectivo acumulado de −300.
        val big = saleOf("2026-12-02", net = 4_000_000, cost = 2_560_000, rounding = -200)
        feeOf(big, 40_000)
        val small = saleOf("2026-12-30", net = 250_000, cost = 160_000, rounding = -100)
        feeOf(small, 2_000)
        returnOf(big, "2026-12-10", net = 50_000, cost = 30_000)
        expenseOf("2026-12-01", 640_000)
        adjustmentOf("2026-12-31", -85_000)

        assertEquals(
            ProfitSummary(
                netSales = Money(4_200_000),
                cogs = Money(2_690_000),
                grossProfit = Money(1_510_000),
                grossMargin = Margin.Defined(BasisPoints(3_595)),
                recordedExpenses = Money(640_000),
                netShrinkage = Money(85_000),
                paymentCosts = Money(42_300),
                totalExpenses = Money(767_300),
                netProfit = Money(742_700),
            ),
            profitOf(ReportPeriod.Month(YearMonth(2026, 12))),
        )
    }

    @Test
    fun daysWeeksAndMonthsFollowTheDateStoredWithTheZoneOfTheirMoment() = runBlocking {
        // Tres instantes: 2026-11-01 03:00, 2026-11-02 04:30 y 2026-11-02 05:00, en UTC.
        val instants = listOf(1_793_502_000_000L, 1_793_593_800_000L, 1_793_595_600_000L)
        // Con el negocio cinco horas detrás de UTC caen el sábado 31 de octubre a las 22:00, el
        // domingo 1 a las 23:30 y el lunes 2 a las 00:00.
        val behind = calendar("Etc/GMT+5")
        for ((instant, net) in instants.zip(listOf(1_000L, 2_000L, 4_000L))) {
            saleStamped(behind, instant, net)
        }
        // La persona cambia la zona a nueve horas delante de UTC: lo ya escrito conserva su fecha,
        // y los mismos instantes caen el domingo 1 a las 12:00 y el lunes 2 a las 13:30 y 14:00.
        val ahead = calendar("Etc/GMT-9")
        for ((instant, net) in instants.zip(listOf(8_000L, 16_000L, 32_000L))) {
            saleStamped(ahead, instant, net)
        }
        val sunday = date("2026-11-01")

        assertEquals(1_000L, netOf(day("2026-10-31")))
        assertEquals(10_000L, netOf(day("2026-11-01")))
        assertEquals(52_000L, netOf(day("2026-11-02")))
        // La semana del domingo según el día en que empiezan las semanas del negocio.
        assertEquals(11_000L, netOf(ReportPeriods.week(sunday, DayOfWeek.MONDAY)))
        assertEquals(62_000L, netOf(ReportPeriods.week(sunday, DayOfWeek.SUNDAY)))
        assertEquals(63_000L, netOf(ReportPeriods.week(sunday, DayOfWeek.SATURDAY)))
        assertEquals(52_000L, netOf(ReportPeriods.week(date("2026-11-02"), DayOfWeek.MONDAY)))
        assertEquals(1_000L, netOf(ReportPeriods.month(date("2026-10-31"))))
        assertEquals(62_000L, netOf(ReportPeriods.month(sunday)))
        assertEquals(
            listOf("2026-11-01" to 10_000L, "2026-11-02" to 52_000L),
            reports.observeDailyTotals(ReportPeriods.week(sunday, DayOfWeek.SUNDAY)).first()
                .map { it.date.toString() to it.totals.profit.salesNetTotal.minor },
        )
    }

    @Test
    fun aWeekThatSplitsTwoMonthsOrTwoYearsCountsEachDayInItsOwnMonth() = runBlocking {
        saleOf("2026-11-30", net = 1_000, cost = 0)
        saleOf("2026-12-01", net = 2_000, cost = 0)
        saleOf("2026-12-06", net = 4_000, cost = 0)
        saleOf("2026-12-07", net = 8_000, cost = 0)
        saleOf("2026-12-28", net = 16_000, cost = 0)
        saleOf("2027-01-03", net = 32_000, cost = 0)
        saleOf("2027-01-04", net = 64_000, cost = 0)
        // Febrero de 2027 va del 1 al 28.
        saleOf("2027-01-31", net = 128_000, cost = 0)
        saleOf("2027-02-01", net = 256_000, cost = 0)
        saleOf("2027-02-28", net = 512_000, cost = 0)
        saleOf("2027-03-01", net = 1_024_000, cost = 0)

        // Con semanas desde el lunes: la del lunes 30 va del 30 de noviembre al 6 de diciembre, y
        // la del domingo 3 de enero, del 28 de diciembre al 3 de enero.
        assertEquals(7_000L, netOf(ReportPeriods.week(date("2026-11-30"), DayOfWeek.MONDAY)))
        assertEquals(48_000L, netOf(ReportPeriods.week(date("2027-01-03"), DayOfWeek.MONDAY)))
        assertEquals(1_000L, netOf(ReportPeriods.month(date("2026-11-30"))))
        assertEquals(30_000L, netOf(ReportPeriods.month(date("2026-12-01"))))
        assertEquals(224_000L, netOf(ReportPeriods.month(date("2027-01-03"))))
        assertEquals(768_000L, netOf(ReportPeriods.month(date("2027-02-14"))))
    }

    @Test
    fun eachDayOfThePeriodSumsItsOwnDocumentsAndDaysWithoutThemAreMissing() = runBlocking {
        val sale = saleOf("2026-11-03", net = 10_000, cost = 6_000, rounding = -100)
        feeOf(sale, 300)
        returnOf(sale, "2026-11-05", net = 2_000, cost = 1_200)
        expenseOf("2026-11-06", 7_000)
        adjustmentOf("2026-11-08", -500)
        // Fuera de la semana del lunes 2 al domingo 8.
        saleOf("2026-11-09", net = 99_999, cost = 0)
        val week = ReportPeriods.week(date("2026-11-04"), DayOfWeek.MONDAY)

        assertEquals(
            listOf(
                DayTotals(
                    date("2026-11-03"),
                    totals(net = 10_000, cost = 6_000, count = 1, rounding = -100, fees = 300),
                ),
                DayTotals(date("2026-11-05"), totals(refund = 2_000, costReturned = 1_200)),
                DayTotals(date("2026-11-06"), totals(expenses = 7_000)),
                DayTotals(date("2026-11-08"), totals(shrinkage = 500)),
            ),
            reports.observeDailyTotals(week).first(),
        )
        // Los días suman lo mismo que la semana entera.
        assertEquals(
            totals(
                net = 10_000,
                cost = 6_000,
                count = 1,
                rounding = -100,
                refund = 2_000,
                costReturned = 1_200,
                expenses = 7_000,
                shrinkage = 500,
                fees = 300,
            ),
            reports.observeTotals(week).first(),
        )
    }

    @Test
    fun aNewExpenseAnAdjustmentAndAVoidEmitTheNewSums() = runBlocking {
        val today = day("2026-11-02")
        val emissions = Emissions(this, reports.observeTotals(today))
        try {
            emissions.awaitValue(PeriodTotals.ZERO)
            val id = expenseOf("2026-11-02", 3_000)
            emissions.awaitValue(totals(expenses = 3_000))
            adjustmentOf("2026-11-02", -400)
            emissions.awaitValue(totals(expenses = 3_000, shrinkage = 400))
            expenses.markVoided(id, CREATED_AT, "ENTRY_ERROR", null)
            emissions.awaitValue(totals(shrinkage = 400))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun bothQueriesSearchEveryTableByItsIndex() = runBlocking {
        for (sql in listOf(PERIOD_TOTALS_SQL, DAILY_TOTALS_SQL)) {
            val plan = planOf(sql)
            for (index in REPORT_INDEXES) {
                assertTrue(
                    "Plan sin $index: $plan",
                    plan.any { it.contains("USING") && it.contains(index) },
                )
            }
            // Ninguna tabla se recorre entera: solo las tablas derivadas de una fila por tabla y
            // la unión de los días.
            assertFalse(
                "Una suma recorre su tabla entera: $plan",
                plan.any { step -> SCANNED_TABLE.containsMatchIn(step) },
            )
        }
    }

    private suspend fun planOf(sql: String): List<String> =
        database.useReaderConnection { connection ->
            connection.usePrepared("EXPLAIN QUERY PLAN $sql") { statement ->
                // Los dos parámetros son los extremos del rango: cada nombre es uno solo, aunque
                // la sentencia lo repita.
                statement.bindText(1, "2026-11-02")
                statement.bindText(2, "2026-11-08")
                // La cuarta columna del plan es el detalle de cada paso.
                buildList { while (statement.step()) add(statement.getText(3)) }
            }
        }

    private suspend fun saleStamped(calendar: BusinessCalendar, instant: Long, net: Long) {
        val stamp = calendar.stamp(Instant.fromEpochMilliseconds(instant))
        saleOf(stamp.businessDate.toString(), net = net, cost = 0, occurredAt = instant)
    }

    private suspend fun netOf(period: ReportPeriod): Long =
        reports.observeTotals(period).first().profit.salesNetTotal.minor

    private suspend fun profitOf(period: ReportPeriod): ProfitSummary {
        val profit = MarginCalculator.profit(reports.observeTotals(period).first().profit)
        return (profit as Checked.Value).value
    }

    private companion object {
        /** La propina de cada venta de estas pruebas, que ninguna suma de ganancia lee. */
        const val TIP = 1_000L

        /** 2026-11-03 a las 12:00 UTC: el día en que se anula la venta de octubre. */
        const val NOVEMBER_3 = 1_793_707_200_000L

        val REPORT_INDEXES = listOf(
            "index_sales_status_business_date",
            "index_sale_returns_status_business_date",
            "index_expenses_status_business_date",
            "index_stock_movements_type_business_date",
            "index_stock_movements_reverses_movement_id",
            "index_sale_payments_sale_id",
        )

        /** Un paso que recorre una tabla de verdad, con su nombre o el alias de la consulta. */
        val SCANNED_TABLE =
            Regex("^SCAN (sales|sale_returns|expenses|stock_movements|sale_payments|m|r|sa|sp)\\b")

        fun date(text: String): BusinessDate = BusinessDate(LocalDate.parse(text))

        fun calendar(zone: String): BusinessCalendar = BusinessCalendar(
            requireNotNull(BusinessTimeZone.of(zone)) { "Zona desconocida" },
            DayOfWeek.MONDAY,
        )
    }
}
