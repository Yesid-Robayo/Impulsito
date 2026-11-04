package io.github.yesidrobayo.shopledger.data.transaction

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.insertPriceListSql
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Un cierre forzado del proceso a mitad de una venta no deja nada: la venta existe completa o no
 * existe.
 *
 * Otro proceso, [WriterProcessService], abre la base por el camino de la app y escribe una venta
 * con el runner real. Al llegar al punto pedido, con la transacción abierta, avisa y se queda
 * esperando. Esta prueba lo mata con `SIGKILL`, sin que alcance a cerrar nada, igual que el sistema
 * mata una app, y después abre la base en su propio proceso, como el arranque siguiente.
 *
 * Con una caché chica, el otro proceso vuelca al `-wal` páginas aún sin confirmar: así se ve que lo
 * que llegó al disco sin confirmación tampoco aparece al abrir.
 *
 * El corte de energía no se prueba aquí: matar el proceso no apaga el disco.
 */
@RunWith(AndroidJUnit4::class)
internal class ProcessDeathTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "process-death-test.db")
    private val ready = File(context.cacheDir, "process-death-ready")
    private var priceListId = 0L
    private lateinit var stock: SeededStock
    private lateinit var before: DatabaseSnapshot

    @Before
    fun seed() = runBlocking {
        deleteDatabaseFiles(file)
        ready.delete()
        // Se cierra antes de empezar: mientras el otro proceso escribe, este no tiene la base.
        withDatabase { database ->
            database.useWriterConnection {
                it.executeSQL(insertPriceListSql("default", "FIXED", basePriceListId = null))
            }
            priceListId =
                database.useReaderConnection { it.longValue("SELECT id FROM price_lists") }
            stock = seedStock(database)
            before = database.snapshot()
        }
    }

    @After
    fun cleanUp() {
        ready.delete()
        deleteDatabaseFiles(file)
    }

    @Test
    fun killedAfterTheHeaderLeavesNothing() =
        assertKilledLeavesNothing(StepPoint(SaleStep.INSERT_HEADER).toString())

    @Test
    fun killedBetweenALineAndItsLedgerRowLeavesNothing() =
        assertKilledLeavesNothing(StepPoint(SaleStep.INSERT_LINE, line = 1).toString())

    @Test
    fun killedBeforeTheLastStockUpdateLeavesNothing() =
        assertKilledLeavesNothing(StepPoint(SaleStep.APPEND_MOVEMENT, line = 2).toString())

    @Test
    fun killedWhileCountingTheChangeLeavesNothing() =
        assertKilledLeavesNothing(WriterProcessService.STOP_AT_CHANGE_COUNT)

    @Test
    fun killedWithUncommittedPagesInTheWalLeavesNothing() = runBlocking {
        val lastLine = SPILL_LINES - 1
        val stop = StepPoint(SaleStep.UPDATE_STOCK_STATE, lastLine).toString()

        val (walBefore, walAtKill) =
            killAt(stop, List(SPILL_LINES) { stock.first }, cachePages = SMALL_CACHE_PAGES)

        // Solo tamaños: lo que el registro de verificación anota de esta prueba.
        Log.i(LOG_TAG, "wal_before=$walBefore wal_at_kill=$walAtKill lines=$SPILL_LINES")
        assertTrue(
            "Sin páginas volcadas al -wal: $walBefore bytes antes y $walAtKill al matar",
            walAtKill > walBefore,
        )
        withDatabase { database ->
            assertEquals(before, database.snapshot())
            database.assertNothingHalfWritten("el cierre con páginas en el -wal")
        }
    }

    @Test
    fun killedAfterTheCommitKeepsTheWholeSale() = runBlocking {
        killAt(WriterProcessService.STOP_AFTER_COMMIT, threeLines())

        withDatabase { database ->
            val after = database.snapshot()
            assertEquals(before.sales + 1, after.sales)
            assertEquals(before.saleLines + 3, after.saleLines)
            assertEquals(before.movements + 3, after.movements)
            assertEquals(before.changeSeq + 1, after.changeSeq)
            database.assertNothingHalfWritten("el cierre tras confirmar")
        }
    }

    private fun assertKilledLeavesNothing(stop: String) = runBlocking {
        killAt(stop, threeLines())

        withDatabase { database ->
            assertEquals("Tras matar en $stop", before, database.snapshot())
            database.assertNothingHalfWritten("el cierre en $stop")
        }
    }

    private fun threeLines() = listOf(stock.first, stock.second, stock.first)

    /**
     * Arranca el otro proceso, espera a que llegue a [stop] y lo mata. Devuelve el tamaño del `-wal`
     * antes de la venta y al matarlo.
     */
    private fun killAt(
        stop: String,
        productIds: List<Long>,
        cachePages: Int = 0,
    ): Pair<Long, Long> {
        val intent =
            Intent(context, WriterProcessService::class.java)
                .putExtra(WriterProcessService.EXTRA_DATABASE, file.path)
                .putExtra(WriterProcessService.EXTRA_READY, ready.path)
                .putExtra(WriterProcessService.EXTRA_STOP, stop)
                .putExtra(WriterProcessService.EXTRA_PRICE_LIST, priceListId)
                .putExtra(WriterProcessService.EXTRA_PRODUCTS, productIds.toLongArray())
                .putExtra(WriterProcessService.EXTRA_CACHE_PAGES, cachePages)
        val connection = IgnoredConnection()
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        val report = awaitReady()
        // Suelto antes de matar: así el sistema no vuelve a crear el servicio y repetir la venta.
        context.unbindService(connection)
        // Un fallo propio del otro proceso, antes del punto, no pasa por un cierre que no dejó
        // nada: deja su informe de error, o no llega al punto, y la prueba falla.
        val parts = report.split(' ')
        if (parts[0] != "ready") fail("El otro proceso falló antes de $stop: $report")
        val pid = parts[1].toInt()
        assertEquals("Se detuvo en otro punto", stop, parts[2])
        assertTrue("El otro proceso es este mismo", pid != Process.myPid())
        // Sigue vivo, esperando con la transacción abierta: la muerte que sigue es la provocada.
        val command = File("/proc/$pid/cmdline").readText()
        assertTrue("El proceso $pid no es el de escritura", command.contains(WRITER_PROCESS))

        Process.killProcess(pid)
        awaitDeath(pid)
        return parts[3].toLong() to parts[4].toLong()
    }

    private fun awaitReady(): String {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (!ready.exists()) {
            if (SystemClock.elapsedRealtime() > deadline) fail("El otro proceso no llegó al punto")
            SystemClock.sleep(POLL_MS)
        }
        return ready.readText()
    }

    private fun awaitDeath(pid: Int) {
        val deadline = SystemClock.elapsedRealtime() + DEATH_TIMEOUT_MS
        while (File("/proc/$pid").exists()) {
            if (SystemClock.elapsedRealtime() > deadline) fail("El otro proceso no murió")
            SystemClock.sleep(POLL_MS)
        }
    }

    private suspend fun <T> withDatabase(block: suspend (AppDatabase) -> T): T {
        val database = AppDatabase.open(context, file, Dispatchers.IO)
        try {
            return block(database)
        } finally {
            database.close()
        }
    }

    /** La prueba no usa la conexión: solo la necesita para que el servicio exista. */
    private class IgnoredConnection : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    private companion object {
        const val LOG_TAG = "ProcessDeathTest"
        const val SPILL_LINES = 300

        /** El nombre del proceso del servicio, como lo declara el manifiesto de pruebas. */
        const val WRITER_PROCESS = ":writer"
        const val SMALL_CACHE_PAGES = 8
        const val READY_TIMEOUT_MS = 30_000L
        const val DEATH_TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
