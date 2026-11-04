package io.github.yesidrobayo.shopledger.data.transaction

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleLine
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cada error de SQLite llega a `:domain` como la falla que la persona puede entender, sin mensaje ni
 * causa, y siempre con la transacción deshecha. Los que se pueden provocar de verdad en el
 * dispositivo se provocan: una llave rota, un disco lleno, una confirmación que falla y otra
 * conexión que retiene la escritura. Los demás llegan como los lanzaría un DAO.
 */
@RunWith(AndroidJUnit4::class)
internal class StorageFailureTest : SaleTransactionTest() {
    @Test
    fun brokenConstraintIsAnInvariantViolation() = runBlocking {
        val before = database.snapshot()

        val failure =
            storageFailure {
                runner.write {
                    database.saleDao().insert(sale(priceListId))
                    // Una línea de una venta que no existe: la llave foránea la rechaza.
                    database.saleLineDao().insert(saleLine(MISSING_ID, stock.first, priceListId))
                    TxResult.Commit(Unit)
                }
            }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(before, database.snapshot())
        assertEquals(0, changes.calls)
    }

    @Test
    fun fullDiskIsDiskFullAndTheNextWriteWorks() = runBlocking {
        val before = database.snapshot()
        // La base no puede crecer ni una página: lo mismo que un disco sin espacio.
        database.useWriterConnection { connection ->
            val pages = connection.longValue("PRAGMA page_count")
            connection.executeSQL("PRAGMA max_page_count = $pages")
        }

        val failure = storageFailure { sales(List(BIG_SALE_LINES) { stock.first }) }

        assertEquals(StorageFailure.DISK_FULL, failure)
        assertEquals(before, database.snapshot())
        database.assertNothingHalfWritten("el disco lleno")
        assertEquals(0, changes.calls)

        // Con espacio otra vez, la misma venta entra entera.
        database.useWriterConnection { it.executeSQL("PRAGMA max_page_count = $MAX_PAGES") }
        sales(List(BIG_SALE_LINES) { stock.first })
        assertEquals(before.saleLines + BIG_SALE_LINES, database.snapshot().saleLines)
        database.assertNothingHalfWritten("la venta tras liberar espacio")
    }

    @Test
    fun failedCommitLeavesNothingAndTheNextWriteWorks() = runBlocking {
        val before = database.snapshot()

        // La llave se revisa al confirmar: la falla llega en el COMMIT, con todo ya escrito.
        val failure =
            storageFailure {
                sales(threeLines) { point ->
                    if (point == StepPoint(SaleStep.UPDATE_STOCK_STATE, line = 2)) {
                        database.useWriterConnection {
                            it.executeSQL("PRAGMA defer_foreign_keys = ON")
                        }
                        database.saleLineDao().insert(
                            saleLine(MISSING_ID, stock.first, priceListId),
                        )
                    }
                }
            }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(before, database.snapshot())
        // El cambio se contó dentro de la transacción que falló: la tabla real lo deshace con ella.
        assertEquals(listOf(true), changes.insideTransaction)
        assertEquals(0, listener.calls)

        // La conexión de escritura no quedó dentro de una transacción.
        sales(threeLines)
        assertEquals(before.sales + 1, database.snapshot().sales)
        database.assertNothingHalfWritten("la venta tras la confirmación fallida")
    }

    @Test
    fun busyDatabaseIsRetriedOnceAndThenReported() = runBlocking {
        val file = databaseFile()
        val before = database.snapshot()
        var attempts = 0
        val started = System.nanoTime()

        val failure =
            holdingTheWriteLock(file) {
                storageFailure {
                    runner.write {
                        attempts++
                        TxResult.Commit(Unit)
                    }
                }
            }

        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        assertEquals(StorageFailure.BUSY, failure)
        assertEquals("El bloque no debía empezar: la falla llega al abrir", 0, attempts)
        // Room espera antes de cada intento fallido: dos esperas son el reintento.
        assertTrue("Esperó $elapsedMs ms", elapsedMs >= 2 * ROOM_BUSY_WAIT_MS - TOLERANCE_MS)
        assertEquals(before, database.snapshot())
        assertEquals(0, changes.calls)
    }

    @Test
    fun busyOnceIsRetriedAndCommitsOnce() = runBlocking {
        var attempts = 0

        val saleId =
            runner.write {
                attempts++
                val id = database.saleDao().insert(sale(priceListId))
                if (attempts == 1) throw sqliteError(SQLITE_BUSY, "database is locked")
                TxResult.Commit(id)
            }

        assertEquals(2, attempts)
        assertEquals(1L, database.snapshot().sales)
        assertEquals(saleId, requireNotNull(database.saleDao().findById(saleId)).id)
        assertEquals(1, changes.calls)
        assertEquals(1, listener.calls)
    }

    @Test
    fun eachStorageCodeHasItsFailure() = runBlocking {
        val expected =
            mapOf(
                SQLITE_IOERR to StorageFailure.IO_ERROR,
                SQLITE_IOERR_WRITE to StorageFailure.IO_ERROR,
                SQLITE_CANTOPEN to StorageFailure.IO_ERROR,
                SQLITE_CORRUPT to StorageFailure.CORRUPT,
                SQLITE_NOTADB to StorageFailure.CORRUPT,
                SQLITE_FULL to StorageFailure.DISK_FULL,
                SQLITE_CONSTRAINT to StorageFailure.INVARIANT_VIOLATION,
                SQLITE_CONSTRAINT_TRIGGER to StorageFailure.INVARIANT_VIOLATION,
                SQLITE_BUSY to StorageFailure.BUSY,
            )
        val before = database.snapshot()

        val actual = expected.keys.associateWith { code -> failureFromBlock(runner, code) }

        assertEquals(expected, actual)
        assertEquals(before, database.snapshot())
        assertEquals(0, changes.calls)
    }

    @Test
    fun readAndWriteErrorsWithLittleSpaceAreDiskFull() = runBlocking {
        val lowSpace = runnerFor(database, changes, storageSpace = StorageSpace { true })

        assertEquals(StorageFailure.DISK_FULL, failureFromBlock(lowSpace, SQLITE_IOERR))
        assertEquals(StorageFailure.DISK_FULL, failureFromBlock(lowSpace, SQLITE_CANTOPEN))
        // Con poco espacio, lo que no es de lectura o escritura sigue siendo lo que es.
        assertEquals(StorageFailure.CORRUPT, failureFromBlock(lowSpace, SQLITE_CORRUPT))
        assertEquals(0, database.snapshot().sales)
    }

    @Test
    fun readTranslatesStorageErrorsToo() = runBlocking {
        val failure = storageFailure {
            runner.read { throw sqliteError(SQLITE_CORRUPT, "database disk image is malformed") }
        }

        assertEquals(StorageFailure.CORRUPT, failure)
    }

    @Test
    fun programmingErrorsComeOutUnchanged() = runBlocking {
        // Un error de SQL, y uno sin código: ni uno ni otro es del almacenamiento.
        val sqlError = sqliteError(SQLITE_ERROR, "no such table: nothing")
        val withoutCode = SQLiteException("sin código")

        for (original in listOf(sqlError, withoutCode)) {
            assertSame(original, thrownBy { runner.write<Unit> { throw original } })
            assertSame(original, thrownBy { runner.read { throw original } })
        }
        assertEquals(0, database.snapshot().sales)
        assertEquals(0, changes.calls)
    }

    @Test
    fun resultCodeIsReadOnlyFromTheStartOfTheMessage() {
        assertEquals(SQLITE_FULL, SqliteFailures.resultCode(sqliteError(SQLITE_FULL, "full")))
        assertEquals(null, SqliteFailures.resultCode(SQLiteException("x Error code: 13")))
        assertEquals(null, SqliteFailures.resultCode(SQLiteException(null)))
        assertEquals(null, SqliteFailures.storageFailureOf(SQLITE_ERROR))
    }

    @Test
    fun storageSpaceUsesTheLowStorageMargin() = runBlocking {
        val file = databaseFile()
        val margin = maxOf(MIN_FREE_BYTES, file.length() * 3)
        val expected = requireNotNull(file.parentFile).usableSpace < margin

        assertEquals(expected, DatabaseStorageSpace(file, Dispatchers.IO).isLow())
        // Sin carpeta no hay espacio que leer: cuenta como poco.
        val nowhere = File(context().cacheDir, "missing/folder/base.db")
        assertTrue(DatabaseStorageSpace(nowhere, Dispatchers.IO).isLow())
    }

    /** Lo que sale del runner cuando un DAO del bloque lanza el error [code] de SQLite. */
    private suspend fun failureFromBlock(runner: RoomTransactionRunner, code: Int): StorageFailure =
        storageFailure {
            runner.write<Unit> {
                database.saleDao().insert(sale(priceListId))
                throw sqliteError(code, "error inventado")
            }
        }

    /** Otra conexión al mismo archivo toma la escritura mientras corre [block], y la suelta. */
    private suspend fun <T> holdingTheWriteLock(file: File, block: suspend () -> T): T =
        BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READWRITE).use { other ->
            other.execSQL("BEGIN IMMEDIATE")
            try {
                block()
            } finally {
                other.execSQL("ROLLBACK")
            }
        }

    private suspend fun thrownBy(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (expected: SQLiteException) {
            return expected
        }
        fail("Debía lanzar el error original")
        error("Inalcanzable")
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    /** El archivo de la base de esta clase, el mismo que abre la base de las pruebas del catálogo. */
    private fun databaseFile(): File = testDatabaseFile(context(), "${javaClass.simpleName}.db")

    private companion object {
        const val MISSING_ID = 999_999L
        const val BIG_SALE_LINES = 400
        const val MAX_PAGES = 1_073_741_823L
        const val MIN_FREE_BYTES = 200L * 1024L * 1024L

        // La espera de Room ante una base ocupada, y un margen para la medición.
        const val ROOM_BUSY_WAIT_MS = 3_000L
        const val TOLERANCE_MS = 500L
        const val NANOS_PER_MILLI = 1_000_000L

        // Códigos de https://www.sqlite.org/rescode.html.
        const val SQLITE_ERROR = 1
        const val SQLITE_BUSY = 5
        const val SQLITE_IOERR = 10
        const val SQLITE_CORRUPT = 11
        const val SQLITE_FULL = 13
        const val SQLITE_CANTOPEN = 14
        const val SQLITE_CONSTRAINT = 19
        const val SQLITE_NOTADB = 26
        const val SQLITE_IOERR_WRITE = 778
        const val SQLITE_CONSTRAINT_TRIGGER = 1811
    }
}

/** Un error con la forma de los que lanzan el SQLite empaquetado y Room. */
internal fun sqliteError(code: Int, message: String): SQLiteException =
    SQLiteException("Error code: $code, message: $message")
