package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.data.local.movement
import io.github.yesidrobayo.shopledger.data.local.payment.cardPayment
import io.github.yesidrobayo.shopledger.data.local.payment.cashPayment
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleLine
import io.github.yesidrobayo.shopledger.data.local.saleReturn
import io.github.yesidrobayo.shopledger.data.local.saleReturnLine
import io.github.yesidrobayo.shopledger.data.repository.RoomPeriodBreakdownRepository
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los desgloses de un período sobre la base real: lo vendido por producto con lo devuelto restado y
 * las variantes en su padre, lo cobrado por método, lo gastado por categoría, los conteos de los
 * avisos y los planes de consulta. Los montos y nombres son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PeriodBreakdownQueryTest : LedgerDatabaseTest() {
    private val breakdowns by lazy { RoomPeriodBreakdownRepository(database) }
    private val week = ReportPeriod.Range(date("2026-11-02"), date("2026-11-08"))

    /** 2026-11-09 00:00 en una zona cinco horas detrás de UTC: el fin de la semana de prueba. */
    private val weekEnd = Instant.parse("2026-11-09T05:00:00Z")

    private var cashId = 0L
    private var cardId = 0L

    @Before
    fun findMethods() = runBlocking {
        cashId = requireNotNull(database.paymentMethodDao().findBySystemKey("CASH")).id
        cardId = requireNotNull(database.paymentMethodDao().findBySystemKey("CARD")).id
    }

    /** Una venta en [date] con una línea de [product] por [net], que costó [cost]. */
    private suspend fun saleWith(
        date: String,
        product: Long,
        net: Long,
        cost: Long,
        quantity: Long = 2_000,
        createdAt: Long = CREATED_AT,
    ): Pair<Long, Long> {
        val saleId =
            database.saleDao().insert(
                sale(priceListId, businessDate = date).copy(
                    netTotal = net,
                    taxTotal = 0,
                    total = net,
                    totalCost = cost,
                    orderAdjustment = 0,
                    tipAmount = 0,
                    cashRoundingAmount = 0,
                    amountDue = net,
                    createdAt = createdAt,
                ),
            )
        val lineId =
            database.saleLineDao().insert(
                saleLine(saleId, product, priceListId).copy(
                    quantityMilli = quantity,
                    enteredQuantityMilli = quantity,
                    orderAdjustmentShare = 0,
                    netAmount = net,
                    taxAmount = 0,
                    lineTotal = net,
                    lineCost = cost,
                ),
            )
        return saleId to lineId
    }

    @Test
    fun productsSumTheirLinesMinusWhatWasReturnedAndVariantsCountInTheirParent() = runBlocking {
        val shirt = database.productDao().insert(product("Camiseta inventada"))
        val blue = database.productDao().insert(product("Camiseta azul", parentProductId = shirt))
        val red = database.productDao().insert(product("Camiseta roja", parentProductId = shirt))
        val (coffeeSale, coffeeLine) = saleWith("2026-11-03", productId, net = 10_000, cost = 6_000)
        saleWith("2026-11-04", blue, net = 7_000, cost = 4_000, quantity = 1_000)
        saleWith("2026-11-05", red, net = 8_000, cost = 5_000, quantity = 1_000)
        // Una unidad de café vuelve al stock el lunes siguiente: igual se resta de lo vendido.
        val returnId =
            database.saleReturnDao().insert(
                saleReturn(coffeeSale).copy(businessDate = "2026-11-10"),
            )
        database.saleReturnLineDao().insert(saleReturnLine(returnId, coffeeLine))
        // Lo anulado y lo de otra semana no cuentan.
        val (voided, _) = saleWith("2026-11-06", productId, net = 99_999, cost = 1)
        database.saleDao().markVoided(voided, CREATED_AT, "ENTRY_ERROR", null)
        saleWith("2026-11-09", productId, net = 55_555, cost = 1)

        val rows = breakdowns.observeProductSales(week).first().sortedBy { it.productId }

        assertEquals(
            listOf(
                ProductSales(
                    productId,
                    "Café",
                    1L,
                    QuantityMilli(1_000),
                    Money(5_000),
                    Money(3_000),
                ),
                ProductSales(
                    shirt,
                    "Camiseta inventada",
                    1L,
                    QuantityMilli(2_000),
                    Money(15_000),
                    Money(9_000),
                ),
            ),
            rows,
        )
    }

    @Test
    fun paymentMethodsAddUpToWhatTheSalesCharged() = runBlocking {
        // Una venta de 12.800 con tarjeta y efectivo, y otra de 3.001 solo con tarjeta.
        val first = database.saleDao().insert(sale(priceListId, businessDate = "2026-11-03"))
        database.salePaymentDao().insert(cardPayment(first, cardId))
        database.salePaymentDao().insert(cashPayment(first, cashId).copy(amount = 9_799))
        val second =
            database.saleDao().insert(
                sale(priceListId, businessDate = "2026-11-04").copy(
                    total = 2_101,
                    netTotal = 2_101,
                    taxTotal = 0,
                    tipAmount = 900,
                    cashRoundingAmount = 0,
                    amountDue = 3_001,
                ),
            )
        database.salePaymentDao().insert(cardPayment(second, cardId))

        val methods = breakdowns.observePaymentMethods(week).first()

        assertEquals(
            listOf(
                PaymentMethodTotal("Efectivo", PaymentMethodKind.CASH, 1, Money(9_799), Money.ZERO),
                PaymentMethodTotal("Tarjeta", PaymentMethodKind.CARD, 2, Money(6_002), Money(166)),
            ),
            methods,
        )
        // 12.800 + 3.001: lo que cobraron las dos ventas.
        assertEquals(15_801L, methods.sumOf { it.amount.minor })
    }

    @Test
    fun expensesAreGroupedByCategoryFromTheLargest() = runBlocking {
        val rent = database.expenseCategoryDao().insert(expenseCategory("Local inventado"))
        val bags = database.expenseCategoryDao().insert(expenseCategory("Bolsas inventadas"))
        database.expenseDao().insert(
            expense(rent, businessDate = "2026-11-02").copy(amount = 600_000),
        )
        database.expenseDao().insert(
            expense(bags, businessDate = "2026-11-03").copy(amount = 25_000),
        )
        database.expenseDao().insert(
            expense(bags, businessDate = "2026-11-08").copy(amount = 15_000),
        )
        val voided = database.expenseDao().insert(expense(rent, businessDate = "2026-11-04"))
        database.expenseDao().markVoided(voided, CREATED_AT, "ENTRY_ERROR", null)

        assertEquals(
            listOf(
                ExpenseCategoryTotal(rent, "Local inventado", null, Money(600_000)),
                ExpenseCategoryTotal(bags, "Bolsas inventadas", null, Money(40_000)),
            ),
            breakdowns.observeExpenseCategories(week).first(),
        )
    }

    @Test
    fun noticesCountWhatChangedAfterThePeriodEnded() = runBlocking {
        val after = weekEnd.toEpochMilliseconds() + 60_000
        // Una venta sin costo y otra registrada con fecha atrasada después del fin.
        saleWith("2026-11-03", productId, net = 5_000, cost = 0)
        saleWith("2026-11-04", productId, net = 5_000, cost = 3_000, createdAt = after)
        // Una venta anulada dentro de la semana y otra anulada después de que terminó.
        val (early, _) = saleWith("2026-11-05", productId, net = 1_000, cost = 500)
        database.saleDao().markVoided(early, CREATED_AT, "ENTRY_ERROR", null)
        val (late, _) = saleWith("2026-11-06", productId, net = 1_000, cost = 500)
        database.saleDao().markVoided(late, after, "ENTRY_ERROR", null)
        // Un ajuste de la semana anulado después del fin.
        val adjustment =
            database.stockMovementDao().insert(
                movement(productId, "ADJUSTMENT", -1_000, 1_000, -1_000, 0, 1_000, 0).copy(
                    reason = "DAMAGED",
                    businessDate = "2026-11-07",
                ),
            )
        database.stockMovementDao().insert(
            movement(productId, "ADJUSTMENT_VOID", 1_000, 1_000, 1_000, 0, 1_000, 0, after).copy(
                reversesMovementId = adjustment,
                businessDate = "2026-11-10",
            ),
        )
        // Un producto con stock negativo hoy.
        val negative = database.productDao().insert(product("Té inventado"))
        database.productDao().updateStockState(
            negative,
            onHandMilli = -2_000,
            averageCost = 1_000,
            stockValue = 0,
            updatedAt = CREATED_AT,
        )

        assertEquals(
            PeriodNoticeCounts(
                salesWithoutCost = 1,
                negativeStock = 1,
                voidedAfterPeriod = 2,
                lateRecords = 1,
                voidedCount = 2,
            ),
            breakdowns.observeNoticeCounts(week, weekEnd).first(),
        )
    }

    @Test
    fun anEmptyPeriodHasNoRowsAndNoNotices() = runBlocking {
        assertEquals(emptyList<ProductSales>(), breakdowns.observeProductSales(week).first())
        assertEquals(
            emptyList<PaymentMethodTotal>(),
            breakdowns.observePaymentMethods(week).first(),
        )
        assertEquals(
            emptyList<ExpenseCategoryTotal>(),
            breakdowns.observeExpenseCategories(week).first(),
        )
        assertEquals(PeriodNoticeCounts.NONE, breakdowns.observeNoticeCounts(week, weekEnd).first())
    }

    @Test
    fun eachBreakdownStartsFromTheDateIndex() = runBlocking {
        val queries =
            mapOf(
                PRODUCT_SALES_SQL to "index_sales_status_business_date",
                PAYMENT_METHODS_SQL to "index_sales_status_business_date",
                EXPENSE_CATEGORIES_SQL to "index_expenses_status_business_date",
                NOTICE_COUNTS_SQL to "index_sales_status_business_date",
            )
        for ((sql, index) in queries) {
            val plan =
                database.useReaderConnection { connection ->
                    connection.usePrepared("EXPLAIN QUERY PLAN $sql") { statement ->
                        statement.bindText(1, "2026-11-02")
                        statement.bindText(2, "2026-11-08")
                        if (sql ==
                            NOTICE_COUNTS_SQL
                        ) {
                            statement.bindLong(3, weekEnd.toEpochMilliseconds())
                        }
                        buildList { while (statement.step()) add(statement.getText(3)) }
                    }
                }
            assertTrue("Plan sin $index: $plan", plan.any { it.contains(index) })
            assertFalse(
                "Una consulta recorre ventas o gastos enteros: $plan",
                plan.any { SCANNED.containsMatchIn(it) },
            )
        }
    }

    private companion object {
        /** Un recorrido completo de ventas, gastos o sus líneas, por su nombre o su alias. */
        val SCANNED = Regex("^SCAN (sales|expenses|sale_lines|sale_payments|s|e|sl|sp)\\b")

        fun date(text: String) = BusinessDate(LocalDate.parse(text))
    }
}
