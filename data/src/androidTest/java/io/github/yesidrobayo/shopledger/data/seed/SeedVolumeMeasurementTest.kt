package io.github.yesidrobayo.shopledger.data.seed

import android.util.Log
import androidx.room3.useReaderConnection
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.money.MoneyMovementEntity
import io.github.yesidrobayo.shopledger.data.local.payment.SalePaymentEntity
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.port.SystemClock
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.ReportPeriods
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LineStockPlan
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LineStockSubject
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Medición base del volumen de referencia en un emulador: cuánto tarda generarlo, cuánto pesa la
 * base, y cuánto tardan las consultas de búsqueda, de un mes de ventas y una venta de diez líneas
 * sobre él. Un emulador no es el teléfono de referencia: los tiempos son preliminares y solo
 * sirven para comparar, así que se anotan sin exigir un tope. Lo que sí se exige es que los
 * invariantes se cumplan.
 *
 * Tarda minutos, así que solo corre si se pide con el argumento `seedVolume=true` del runner de
 * instrumentación. Solo registra cifras, tiempos y tamaños: ningún dato del negocio inventado.
 */
@RunWith(AndroidJUnit4::class)
internal class SeedVolumeMeasurementTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val target = testDatabaseFile(context, "seed-volume.db")
    private val compact = testDatabaseFile(context, "seed-volume-compact.db")

    @After
    fun deleteGeneratedDatabases() {
        deleteDatabaseFiles(target)
        deleteDatabaseFiles(compact)
    }

    @Test
    fun volumeProfileMeasurement() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true")
        deleteDatabaseFiles(target)
        deleteDatabaseFiles(compact)
        val config = requireNotNull(seedConfigOf(SPANISH_ROW))

        val generation = TimeSource.Monotonic.markNow()
        val report =
            SeedDataGenerator(SeedDataGenerator.DEFAULT_SEED, SeedProfile.VOLUME, config)
                .generate(context, target)
        val generationSeconds = generation.elapsedNow().inWholeSeconds
        assertInvariantsHold("volumen", report)
        log("generación: $generationSeconds s")
        for ((table, rows) in report.rowCounts) if (rows > 0L) log("filas $table: $rows")
        log("filas del producto pesado: ${report.heavyProductMovements}")
        log("archivo: ${target.length()} bytes")

        measureFile()

        val database = AppDatabase.open(context, target, Dispatchers.IO)
        try {
            val checks = TimeSource.Monotonic.markNow()
            val again = SeedInvariants.check(database, config.zone)
            log("invariantes: ${checks.elapsedNow().inWholeMilliseconds} ms")
            assertTrue(again.values.all { it == 0L })
            measureSearch(database)
            measureMonth(database)
            measureSale(database, config)
        } finally {
            database.close()
        }
    }

    /** La base compactada y su copia con gzip, como la que sube el respaldo. */
    private fun measureFile() {
        val vacuum = TimeSource.Monotonic.markNow()
        inspectWithoutRoom(target) { it.execSQL("VACUUM INTO '${compact.path}'") }
        log("VACUUM INTO: ${vacuum.elapsedNow().inWholeMilliseconds} ms, ${compact.length()} bytes")
        val zip = TimeSource.Monotonic.markNow()
        val counter = CountingStream()
        GZIPOutputStream(counter).use { gzip -> compact.inputStream().use { it.copyTo(gzip) } }
        log("gzip nivel 6: ${zip.elapsedNow().inWholeMilliseconds} ms, ${counter.bytes} bytes")
    }

    /** La búsqueda de productos, una consulta por cada letra que se escribe. */
    private suspend fun measureSearch(database: AppDatabase) {
        for (term in listOf("c", "ca", "caf", "cafe", "cafe t")) {
            val times =
                database.useReaderConnection { connection ->
                    connection.usePrepared(SEARCH_SQL) { statement ->
                        List(ROUNDS + 1) {
                            val mark = TimeSource.Monotonic.markNow()
                            statement.reset()
                            statement.bindText(1, term)
                            var found = 0
                            while (statement.step()) found++
                            assertTrue(found > 0)
                            mark.elapsedNow().inWholeMicroseconds
                        }.drop(1)
                    }
                }
            log("búsqueda de ${term.length} caracteres: ${summary(times)}")
        }
    }

    /** Las cifras de un mes, y la lista de sus ventas, con el mes más reciente de la historia. */
    private suspend fun measureMonth(database: AppDatabase) {
        val from = "2026-10-01"
        val to = "2026-10-31"
        for ((name, sql) in listOf("ventas por día" to SALES_BY_DAY, "ganancia" to PROFIT)) {
            val times =
                database.useReaderConnection { connection ->
                    List(ROUNDS + 1) {
                        val mark = TimeSource.Monotonic.markNow()
                        connection.usePrepared(sql) { statement ->
                            statement.bindText(1, from)
                            statement.bindText(2, to)
                            while (statement.step()) Unit
                        }
                        mark.elapsedNow().inWholeMicroseconds
                    }.drop(1)
                }
            log("mes, $name: ${summary(times)}")
        }
        val ports = DataPorts(database, target, Dispatchers.IO, SystemClock)
        val month = ReportPeriods.month(BusinessDate(LocalDate(2026, 10, 1)))
        val times =
            List(ROUNDS + 1) {
                val mark = TimeSource.Monotonic.markNow()
                val sales = ports.sales.observeIn(month).first()
                assertTrue(sales.isNotEmpty())
                mark.elapsedNow().inWholeMicroseconds
            }.drop(1)
        log("mes, lista de ventas: ${summary(times)}")
    }

    /**
     * Una venta de diez líneas con todo lo que escribe en una transacción: cabecera, líneas, filas
     * del libro con su costo, pago y dinero, con `synchronous = FULL`. Lee los productos dentro de
     * la transacción, como el caso de uso.
     */
    private suspend fun measureSale(database: AppDatabase, config: SeedConfig) {
        val ports = DataPorts(database, target, Dispatchers.IO, SystemClock)
        val ids =
            database.useReaderConnection { connection ->
                connection.texts(TEN_PRODUCTS).map { it.toLong() }
            }
        assertEquals(LINES, ids.size)
        val cash = requireNotNull(database.paymentMethodDao().findBySystemKey("CASH"))
        val account = requireNotNull(cash.moneyAccountId)
        val times =
            List(ROUNDS + 1) {
                val mark = TimeSource.Monotonic.markNow()
                ports.transactions.write {
                    writeSale(database, ports, ids, cash.id, account, config)
                    TxResult.Commit(Unit)
                }
                mark.elapsedNow().inWholeMicroseconds
            }.drop(1)
        log("venta de $LINES líneas: ${summary(times)}")
    }

    private suspend fun writeSale(
        database: AppDatabase,
        ports: DataPorts,
        ids: List<Long>,
        methodId: Long,
        account: Long,
        config: SeedConfig,
    ) {
        val now = SystemClock.now()
        val date = BusinessDate.of(now, config.zone)
        val quantity = QuantityMilli(UNIT)
        val products = ports.products.findByIds(ids).sortedBy { it.id }
        val lines =
            products.map { product ->
                val state = CostState(product.onHand, product.averageCost, product.stockValue)
                val plan =
                    checked(StockModeRules.planLine(LineStockSubject.Tracked(state, quantity)))
                val exit = requireNotNull(plan as? LineStockPlan.Direct).exit
                Triple(product, exit, checked(product.salePrice.timesQuantity(quantity)))
            }
        val total = lines.fold(Money.ZERO) { sum, line -> sum + line.third }
        val cost = lines.fold(Money.ZERO) { sum, line -> sum + line.second.cost }
        val saleId =
            ports.sales.insert(
                NewSale(
                    netTotal = total,
                    taxTotal = Money.ZERO,
                    total = total,
                    totalCost = cost,
                    totalReplacementCost = Money.ZERO,
                    orderAdjustment = Money.ZERO,
                    tipAmount = Money.ZERO,
                    cashRoundingAmount = Money.ZERO,
                    amountDue = total,
                    customerId = null,
                    priceListId = DEFAULT_PRICE_LIST,
                    locationId = MAIN_LOCATION,
                    cashSessionId = null,
                    quoteId = null,
                    orderId = null,
                    note = null,
                    occurredAt = now,
                    businessDate = date,
                    createdAt = now,
                ),
            )
        for ((product, exit, amount) in lines) {
            val lineId =
                ports.sales.insertLine(
                    saleId,
                    NewSaleLine(
                        productId = product.id,
                        unitId = product.details.baseUnitId,
                        unitFactor = QuantityMilli(UNIT),
                        enteredQuantity = quantity,
                        quantity = quantity,
                        unitPrice = product.salePrice,
                        unitCost = exit.unitCost,
                        lineDiscount = Money.ZERO,
                        orderAdjustmentShare = Money.ZERO,
                        netAmount = amount,
                        taxRate = BasisPoints.ZERO,
                        taxNameSnapshot = null,
                        taxAmount = Money.ZERO,
                        lineTotal = amount,
                        lineCost = exit.cost,
                        unitReplacementCost = Money.ZERO,
                        lineReplacementCost = Money.ZERO,
                        productNameSnapshot = product.details.name,
                        stockEffect = LineStockEffect.DIRECT,
                        parentLineId = null,
                        priceListId = DEFAULT_PRICE_LIST,
                    ),
                )
            ports.stockLedger.append(
                NewStockMovement(
                    movement = exit.saleMovement(StockKey(product.id, MAIN_LOCATION, lotId = null)),
                    reason = null,
                    unitCost = exit.unitCost,
                    totalCost = -exit.cost,
                    balanceAfter = exit.stateAfter.onHand,
                    averageCostAfter = exit.stateAfter.averageCost,
                    stockValueAfter = exit.stateAfter.stockValue,
                    source = StockMovementSource(saleLineId = lineId),
                    note = null,
                    occurredAt = now,
                    businessDate = date,
                    createdAt = now,
                ),
            )
        }
        val paymentId =
            database.salePaymentDao().insert(
                SalePaymentEntity(
                    saleId = saleId,
                    paymentMethodId = methodId,
                    methodNameSnapshot = "Efectivo",
                    methodKindSnapshot = "CASH",
                    amount = total.minor,
                    feeAmount = 0L,
                    settlementDate = date.toString(),
                    moneyAccountId = account,
                    tenderedAmount = null,
                    changeAmount = null,
                    tenderCurrencyCode = null,
                    tenderCurrencyDecimals = null,
                    tenderAmount = null,
                    fxRateMicro = null,
                    createdAt = now.toEpochMilliseconds(),
                ),
            )
        val balance = database.moneyMovementDao().lastBalanceAfter(account) ?: 0L
        database.moneyMovementDao().insert(
            MoneyMovementEntity(
                moneyAccountId = account,
                type = "SALE_RECEIPT",
                amount = total.minor,
                balanceAfter = balance + total.minor,
                availableDate = date.toString(),
                transferGroupId = null,
                fxRateMicro = null,
                salePaymentId = paymentId,
                customerLedgerEntryId = null,
                supplierLedgerEntryId = null,
                expenseId = null,
                purchaseId = null,
                saleReturnId = null,
                purchaseReturnId = null,
                cashSessionId = null,
                reversesMovementId = null,
                note = null,
                occurredAt = now.toEpochMilliseconds(),
                businessDate = date.toString(),
                createdAt = now.toEpochMilliseconds(),
            ),
        )
        database.moneyAccountDao().setBalance(
            account,
            balance + total.minor,
            now.toEpochMilliseconds(),
        )
    }

    /** Mediana, percentil 95 y máximo, en milisegundos con un decimal. */
    private fun summary(micros: List<Long>): String {
        val sorted = micros.sorted()
        val p95 = sorted[(sorted.size * P95 + 99) / 100 - 1]
        return "mediana ${millis(sorted[sorted.size / 2])} ms, p95 ${millis(p95)} ms, " +
            "máximo ${millis(sorted.last())} ms"
    }

    private fun millis(micros: Long): String = "${micros / 1_000}.${micros % 1_000 / 100}"

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    /** Cuenta los bytes que recibe y los descarta: el tamaño de la copia sin escribirla. */
    private class CountingStream : OutputStream() {
        var bytes = 0L

        override fun write(b: Int) {
            bytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            bytes += len
        }
    }

    private companion object {
        const val TAG = "SeedVolume"
        const val ARGUMENT = "seedVolume"
        const val ROUNDS = 20
        const val P95 = 95
        const val LINES = 10
        const val UNIT = 1_000L
        const val MAIN_LOCATION = 1L
        const val DEFAULT_PRICE_LIST = 1L

        const val TEN_PRODUCTS =
            "SELECT CAST(id AS TEXT) FROM products WHERE stock_mode = 'TRACKED' " +
                "AND archived_at IS NULL AND base_unit_id = " +
                "(SELECT id FROM units WHERE system_key = 'UNIT') ORDER BY id LIMIT 10"

        /** La búsqueda por texto contenido del modelo de datos, con el término ya normalizado. */
        const val SEARCH_SQL =
            "SELECT id, normalized_name, base_unit_id, sale_price, average_cost, on_hand_milli, " +
                "min_stock_milli FROM products WHERE archived_at IS NULL " +
                "AND normalized_name LIKE '%' || ? || '%' ESCAPE '\\' " +
                "ORDER BY normalized_name LIMIT 50"

        /** Ventas por día del modelo de datos. */
        const val SALES_BY_DAY =
            "SELECT business_date, COUNT(*) AS sales_count, SUM(net_total) AS net_total, " +
                "SUM(total) AS sales_total, SUM(total_cost) AS cogs FROM sales " +
                "WHERE status = 'ACTIVE' AND business_date BETWEEN ?1 AND ?2 " +
                "GROUP BY business_date ORDER BY business_date"

        /** Ganancia bruta y neta del período, del modelo de datos. */
        const val PROFIT =
            "SELECT net_sales, cogs, net_sales - cogs AS gross_profit, recorded_expenses, " +
                "net_shrinkage, payment_costs, " +
                "recorded_expenses + net_shrinkage + payment_costs AS total_expenses, " +
                "net_sales - cogs - recorded_expenses - net_shrinkage - payment_costs " +
                "AS net_profit " +
                "FROM (SELECT " +
                "(SELECT COALESCE(SUM(net_total), 0) FROM sales " +
                "WHERE status = 'ACTIVE' AND business_date BETWEEN ?1 AND ?2) " +
                "- (SELECT COALESCE(SUM(net_refund), 0) FROM sale_returns " +
                "WHERE status = 'ACTIVE' AND business_date BETWEEN ?1 AND ?2) AS net_sales, " +
                "(SELECT COALESCE(SUM(total_cost), 0) FROM sales " +
                "WHERE status = 'ACTIVE' AND business_date BETWEEN ?1 AND ?2) " +
                "- (SELECT COALESCE(SUM(cost_returned), 0) FROM sale_returns " +
                "WHERE status = 'ACTIVE' AND business_date BETWEEN ?1 AND ?2) AS cogs, " +
                "(SELECT COALESCE(SUM(amount), 0) FROM expenses " +
                "WHERE status = 'ACTIVE' AND business_date BETWEEN ?1 AND ?2) " +
                "AS recorded_expenses, " +
                "(SELECT COALESCE(-SUM(m.total_cost), 0) FROM stock_movements m " +
                "WHERE m.type = 'ADJUSTMENT' AND m.business_date BETWEEN ?1 AND ?2 " +
                "AND NOT EXISTS (SELECT 1 FROM stock_movements r " +
                "WHERE r.reverses_movement_id = m.id)) AS net_shrinkage, " +
                "(SELECT COALESCE(SUM(p.fee_amount), 0) FROM sale_payments p " +
                "JOIN sales s ON s.id = p.sale_id " +
                "WHERE s.status = 'ACTIVE' AND s.business_date BETWEEN ?1 AND ?2) " +
                "- (SELECT COALESCE(SUM(cash_rounding_amount), 0) FROM sales " +
                "WHERE status = 'ACTIVE' AND business_date BETWEEN ?1 AND ?2) AS payment_costs)"
    }
}
