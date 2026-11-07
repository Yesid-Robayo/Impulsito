package io.github.yesidrobayo.shopledger.data.transaction

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El contrato del runner sobre Room real: confirma todo o nada, cuenta un cambio solo al confirmar
 * y dentro de la transacción, avisa después, no empieza con una restauración en curso, no se anida,
 * no se cancela a mitad y lee de una sola foto de la base.
 */
@RunWith(AndroidJUnit4::class)
internal class RoomTransactionRunnerTest : SaleTransactionTest() {
    @Test
    fun commitKeepsEverythingAndCountsOneChangeInsideTheTransaction() = runBlocking {
        val before = database.snapshot()

        val saleId = sales(threeLines)

        val after = database.snapshot()
        assertEquals(before.sales + 1, after.sales)
        assertEquals(before.saleLines + 3, after.saleLines)
        assertEquals(before.movements + 3, after.movements)
        assertEquals(3, database.saleLineDao().findBySale(requireNotNull(saleId)).size)
        database.assertNothingHalfWritten("la venta confirmada")
        assertEquals(listOf(FIXED_CLOCK.nowEpochMillis()), changes.instants)
        assertEquals(listOf(true), changes.insideTransaction)
        assertEquals(1, listener.calls)
        // Un cambio sin respaldar, que es el primero: su instante queda como el más viejo sin copia.
        assertEquals(before.changeSeq + 1, after.changeSeq)
        assertEquals(null, before.firstUnsyncedAt)
        assertEquals(FIXED_CLOCK.nowEpochMillis(), after.firstUnsyncedAt)
    }

    @Test
    fun eachCommitCountsOneAndKeepsTheOldestUnsyncedInstant() = runBlocking {
        val before = database.snapshot()
        val later = Clock { Instant.fromEpochMilliseconds(FIXED_CLOCK.nowEpochMillis() + HOUR_MS) }
        val laterRunner = runnerFor(database, changes, clock = later)

        sales(threeLines)
        TestSaleUseCase(laterRunner, database, priceListId)(threeLines)
        laterRunner.write { TxResult.Commit(database.saleDao().insert(sale(priceListId))) }

        val after = database.snapshot()
        assertEquals(before.changeSeq + 3, after.changeSeq)
        assertEquals(FIXED_CLOCK.nowEpochMillis(), after.firstUnsyncedAt)
        assertEquals(before.sales + 3, after.sales)
    }

    @Test
    fun missingBackupStateRowRefusesTheCommit() = runBlocking {
        val before = database.snapshot()
        // Sin la fila, la sentencia no cuenta nada: la venta quedaría sin marca y sin copia.
        val withoutRow = runnerFor(database, BackupStateChangeCounter(MissingRow))

        val failure =
            storageFailure { TestSaleUseCase(withoutRow, database, priceListId)(threeLines) }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(before, database.snapshot())
        database.assertNothingHalfWritten("la fila del respaldo ausente")
    }

    @Test
    fun rollbackReturnsItsValueAndWritesNothing() = runBlocking {
        val before = database.snapshot()

        val result = sales(threeLines, reject = true)

        assertNull(result)
        assertEquals(before, database.snapshot())
        assertEquals(0, changes.calls)
        assertEquals(0, listener.calls)
    }

    @Test
    fun failureInTheBlockLeavesNothingAndComesOutUnchanged() = runBlocking {
        val before = database.snapshot()
        val injected = InjectedFailure()

        try {
            runner.write<Unit> {
                database.saleDao().insert(sale(priceListId))
                throw injected
            }
            fail("La falla debía salir del runner")
        } catch (failure: InjectedFailure) {
            assertSame(injected, failure)
        }

        assertEquals(before, database.snapshot())
        assertEquals(0, changes.calls)
        assertEquals(0, listener.calls)
    }

    @Test
    fun restoreInProgressRejectsBeforeStarting() = runBlocking {
        var held = true
        val guarded = runnerFor(database, changes, restoreLock = RestoreLock { held })
        val before = database.snapshot()
        var started = false

        val failure =
            storageFailure {
                guarded.write<Long> {
                    started = true
                    TxResult.Commit(database.saleDao().insert(sale(priceListId)))
                }
            }

        assertEquals(StorageFailure.RESTORE_IN_PROGRESS, failure)
        assertFalse("El bloque no debía empezar", started)
        assertEquals(before, database.snapshot())
        assertEquals(0, changes.calls)

        // El cerrojo se pregunta en cada escritura: suelto, la siguiente entra.
        held = false
        guarded.write { TxResult.Commit(database.saleDao().insert(sale(priceListId))) }
        assertEquals(before.sales + 1, database.snapshot().sales)
    }

    @Test
    fun writeInsideAnotherTransactionIsRefused() = runBlocking {
        val before = database.snapshot()

        try {
            runner.write {
                database.saleDao().insert(sale(priceListId))
                runner.write { TxResult.Commit(database.saleDao().insert(sale(priceListId))) }
                TxResult.Commit(Unit)
            }
            fail("La escritura anidada debía rechazarse")
        } catch (expected: IllegalStateException) {
            // Rechazada, y la de afuera se deshizo entera.
        }
        try {
            runner.read { runner.write { TxResult.Commit(Unit) } }
            fail("Una escritura dentro de una lectura debía rechazarse")
        } catch (expected: IllegalStateException) {
            // Rechazada antes de abrir nada.
        }

        assertEquals(before, database.snapshot())
        assertEquals(0, changes.calls)
    }

    @Test
    fun cancellingTheCallerDoesNotCutAStartedWrite() = runBlocking {
        val before = database.snapshot()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val caller =
            launch(Dispatchers.IO) {
                runner.write {
                    database.saleDao().insert(sale(priceListId))
                    entered.complete(Unit)
                    proceed.await()
                    TxResult.Commit(Unit)
                }
            }
        withTimeout(TIMEOUT_MS) { entered.await() }

        // Como salir de la pantalla con la venta ya confirmada por la persona.
        caller.cancel()
        proceed.complete(Unit)
        caller.join()

        assertEquals(before.sales + 1, database.snapshot().sales)
        assertEquals(1, changes.calls)
    }

    @Test
    fun failingListenerDoesNotUndoTheCommit() = runBlocking {
        listener.failure = InjectedFailure()
        val before = database.snapshot()

        val saleId = sales(threeLines)

        assertTrue(requireNotNull(saleId) > 0)
        assertEquals(before.sales + 1, database.snapshot().sales)
        assertEquals(1, listener.calls)
        assertEquals(1, changes.calls)
    }

    @Test
    fun readSeesOneSnapshotWhileAnotherWriteCommits() = runBlocking {
        val firstRead = CompletableDeferred<Unit>()
        val committed = CompletableDeferred<Unit>()
        val reads =
            async(Dispatchers.IO) {
                runner.read {
                    val first = countSales()
                    firstRead.complete(Unit)
                    withTimeout(TIMEOUT_MS) { committed.await() }
                    first to countSales()
                }
            }
        withTimeout(TIMEOUT_MS) { firstRead.await() }

        runner.write { TxResult.Commit(database.saleDao().insert(sale(priceListId))) }
        committed.complete(Unit)

        val (first, second) = reads.await()
        assertEquals("La lectura cambió de foto a mitad", first, second)
        assertEquals(first + 1, countSales())
        assertEquals(0L, first)
    }

    @Test
    fun writeRunsOnTheWriterWithSynchronousFull() = runBlocking {
        val synchronous =
            runner.write {
                TxResult.Commit(database.useWriterConnection { it.longValue("PRAGMA synchronous") })
            }

        assertEquals(SYNCHRONOUS_FULL, synchronous)
    }

    private suspend fun countSales(): Long =
        database.useReaderConnection { it.longValue("SELECT COUNT(*) FROM sales") }

    /** Un estado del respaldo sin su fila: la actualización no cambia ninguna. */
    private object MissingRow : BackupStateDao {
        override fun observe(): Flow<BackupStateEntity?> = flowOf(null)

        override suspend fun get(): BackupStateEntity? = null

        override suspend fun markChanged(now: Long): Int = 0

        // Lo del motor de respaldo no se usa aquí: sin fila, tampoco cambiaría nada.
        override suspend fun isEmpty(): Boolean = true

        override suspend fun recordCount(): Long = 0

        override suspend fun catalogCount(): Long = 0

        override suspend fun markAttempt(now: Long): Int = 0

        override suspend fun saveIntent(
            generation: Long,
            seq: Long,
            sha256: String,
            sizeBytes: Long,
        ): Int = 0

        override suspend fun clearIntent(): Int = 0

        override suspend fun markUploadFinished(now: Long): Int = 0

        override suspend fun recordFailure(code: String): Int = 0

        override suspend fun recordTransportFailure(
            kind: String,
            limit: Long,
            persistentCode: String,
        ): Int = 0

        override suspend fun leaveConnected(linkState: String, destinationId: String): Int = 0

        override suspend fun markConflict(
            kind: String,
            generation: Long?,
            deviceId: String?,
            fileRef: String?,
            destinationId: String,
        ): Int = 0

        override suspend fun keepThisPhone(
            generation: Long,
            deviceId: String,
            fileRef: String,
            now: Long,
            destinationId: String,
        ): Int = 0

        override suspend fun reopenAfterConflict(now: Long, destinationId: String): Int = 0

        override suspend fun confirm(
            generation: Long,
            deviceId: String,
            fileRef: String,
            seq: Long,
            now: Long,
            copiedAt: Long,
            failureCode: String?,
            destinationId: String,
        ): Int = 0

        override suspend fun confirmUnrestorable(
            generation: Long,
            deviceId: String,
            fileRef: String,
            failureCode: String,
            destinationId: String,
        ): Int = 0

        override suspend fun recordInvariantFailures(count: Long): Int = 0
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val TIMEOUT_MS = 10_000L
        const val SYNCHRONOUS_FULL = 2L
    }
}

/** La falla de almacenamiento que lanza [block], o falla la prueba si no lanza ninguna. */
internal suspend fun storageFailure(block: suspend () -> Unit): StorageFailure {
    try {
        block()
    } catch (expected: StorageFailureException) {
        // Sin texto ni causa: nada de la base llega a un registro.
        assertNull(expected.message)
        assertNull(expected.cause)
        return expected.failure
    }
    fail("Debía fallar con una falla de almacenamiento")
    error("Inalcanzable")
}
