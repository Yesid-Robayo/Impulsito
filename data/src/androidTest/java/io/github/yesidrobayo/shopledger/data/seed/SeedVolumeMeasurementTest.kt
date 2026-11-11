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
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.repository.RoomSaleHistorySource
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import io.github.yesidrobayo.shopledger.domain.port.SystemClock
import io.github.yesidrobayo.shopledger.domain.rule.ReportPeriods
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.After
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
            measureHistory(database)
            measureMethodFilter(database)
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
        val ports = DataPorts(database, target, Dispatchers.IO, SystemClock, RestoreLock { false })
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
     * El historial de ventas por tramos de 50: el primero, uno a mitad de la historia y el último,
     * y el primero con filtro de método. Con un cursor, cada tramo cuesta lo mismo esté donde esté.
     */
    private suspend fun measureHistory(database: AppDatabase) {
        val history = RoomSaleHistorySource(database)
        val all = SaleHistoryFilter()
        // Un cursor a mitad de la historia y otro cerca del final, tomados de la propia base.
        val middle = cursorAt(database, HISTORY_MIDDLE)
        val deep = cursorAt(database, HISTORY_DEEP)
        val method = database.useReaderConnection { connection ->
            connection.usePrepared("SELECT payment_method_id FROM sale_payments LIMIT 1") {
                it.step()
                it.getLong(0)
            }
        }
        val cases = listOf(
            "primer tramo" to (all to null),
            "tramo a mitad" to (all to middle),
            "tramo hondo" to (all to deep),
            "primer tramo con método" to (SaleHistoryFilter(paymentMethodId = method) to null),
        )
        for ((name, case) in cases) {
            val (filter, after) = case
            val times =
                List(ROUNDS + 1) {
                    val mark = TimeSource.Monotonic.markNow()
                    val page = history.page(filter, after, HISTORY_PAGE)
                    history.dayTotals(filter, page.map { it.businessDate }.toSet())
                    assertTrue(page.isNotEmpty())
                    mark.elapsedNow().inWholeMicroseconds
                }.drop(1)
            log("historial, $name: ${summary(times)}")
        }
    }

    /**
     * El filtro de método con el método más usado, el menos usado y uno sin ventas, de dos formas:
     * recorriendo las ventas, como antes, y por el camino que elige el historial. Mide el primer
     * tramo y el siguiente, con sus totales por día, y comprueba que los dos caminos den lo mismo.
     */
    private suspend fun measureMethodFilter(database: AppDatabase) {
        val history = RoomSaleHistorySource(database)
        val dao = database.saleDao()
        val counts = database.useReaderConnection { connection ->
            connection.usePrepared(
                "SELECT payment_method_id, COUNT(*) FROM sale_payments " +
                    "GROUP BY payment_method_id ORDER BY 2 DESC, 1",
            ) { statement ->
                buildList {
                    while (statement.step()) {
                        add(statement.getLong(0) to statement.getLong(1))
                    }
                }
            }
        }
        val unused = database.useReaderConnection { connection ->
            connection.usePrepared("SELECT COALESCE(MAX(id), 0) + 1 FROM payment_methods") {
                it.step()
                it.getLong(0)
            }
        }
        val cases = listOf(
            "más usado" to counts.first(),
            "menos usado" to counts.last(),
            "sin ventas" to (unused to 0L),
        )
        for ((name, case) in cases) {
            val (methodId, payments) = case
            val filter = SaleHistoryFilter(paymentMethodId = methodId)
            val first = history.page(filter, null, HISTORY_PAGE)
            val walked = dao.historyPage(null, null, methodId, false, null, null, HISTORY_PAGE)
            assertTrue(first.map { it.id } == walked.map { it.id })
            val after = first.lastOrNull()?.cursor
            val ways = listOf<Pair<String, suspend (SaleHistoryCursor?) -> Int>>(
                "recorriendo" to { cursor ->
                    dao.historyPage(
                        null,
                        null,
                        methodId,
                        false,
                        cursor?.occurredAt?.toEpochMilliseconds(),
                        cursor?.id,
                        HISTORY_PAGE,
                    ).size
                },
                "elegido" to { cursor ->
                    val page = history.page(filter, cursor, HISTORY_PAGE)
                    history.dayTotals(filter, page.map { it.businessDate }.toSet())
                    page.size
                },
            )
            for ((way, read) in ways) {
                for ((part, cursor) in listOf("primer tramo" to null, "segundo tramo" to after)) {
                    if (part == "segundo tramo" && cursor == null) continue
                    val times =
                        List(ROUNDS + 1) {
                            val mark = TimeSource.Monotonic.markNow()
                            read(cursor)
                            mark.elapsedNow().inWholeMicroseconds
                        }.drop(1)
                    log("método $name, $payments pagos, $way, $part: ${summary(times)}")
                }
            }
        }
    }

    /** La venta número [offset] del historial, contada desde la más nueva, como cursor. */
    private suspend fun cursorAt(database: AppDatabase, offset: Int): SaleHistoryCursor =
        database.useReaderConnection { connection ->
            connection.usePrepared(
                "SELECT occurred_at, id FROM sales ORDER BY occurred_at DESC, id DESC " +
                    "LIMIT 1 OFFSET $offset",
            ) {
                assertTrue(it.step())
                SaleHistoryCursor(Instant.fromEpochMilliseconds(it.getLong(0)), it.getLong(1))
            }
        }

    /**
     * Una venta de diez líneas con todo lo que escribe en una transacción: cabecera, líneas, filas
     * del libro con su costo, pago y dinero, con `synchronous = FULL`. Lee los productos dentro de
     * la transacción, como el caso de uso.
     */
    private suspend fun measureSale(database: AppDatabase, config: SeedConfig) {
        val ports = DataPorts(database, target, Dispatchers.IO, SystemClock, RestoreLock { false })
        val sales = SeedSaleWriter.on(database, ports, config)
        val times =
            List(ROUNDS + 1) {
                val mark = TimeSource.Monotonic.markNow()
                sales.write()
                mark.elapsedNow().inWholeMicroseconds
            }.drop(1)
        log("venta de ${SeedSaleWriter.LINES} líneas: ${summary(times)}")
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

        /** Un tramo del historial, el mismo que pide la pantalla. */
        const val HISTORY_PAGE = 50

        /** Las ventas que se saltan para tomar un cursor a mitad de la historia y cerca del final. */
        const val HISTORY_MIDDLE = 50_000
        const val HISTORY_DEEP = 99_900
        const val P95 = 95

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
