package io.github.yesidrobayo.shopledger.data.transaction

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.sale.SaleDraftEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleDraftLineEntity
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Una confirmación sin conteo, como la de una venta en espera: lo escrito queda, pero el contador de
 * cambios sin respaldar y el instante del primero no se mueven, y nada se programa después. Va por
 * el mismo camino que una confirmación normal: un fallo deshace todo, no empieza con una
 * restauración en curso, no se anida y reintenta una vez si la base está ocupada.
 */
@RunWith(AndroidJUnit4::class)
internal class UncountedCommitTest : SaleTransactionTest() {
    @Test
    fun savingAndDeletingADraftKeepsTheChangeCounterAsItWas() = runBlocking {
        val clean = database.snapshot()

        val draftId = saveDraft(runner)

        // Sin ningún cambio antes, el instante del primero sigue vacío.
        assertEquals(clean, database.snapshot())
        assertEquals(1L, countOf("sale_drafts"))
        assertEquals(2, database.saleDraftDao().findLines(draftId).size)
        assertEquals(0, changes.calls)
        assertEquals(0, listener.calls)

        // Una venta sí cuenta. Después, borrar la espera, una hora más tarde, no mueve nada.
        sales(threeLines)
        val afterSale = database.snapshot()
        val later = runnerFor(database, changes, commitListener = listener, clock = ONE_HOUR_LATER)

        val deleted = later.write {
            TxResult.CommitUncounted(database.saleDraftDao().delete(draftId))
        }

        assertEquals(1, deleted)
        assertEquals(afterSale, database.snapshot())
        assertEquals(FIXED_CLOCK.nowEpochMillis(), afterSale.firstUnsyncedAt)
        assertNull(database.saleDraftDao().findById(draftId))
        assertEquals(0L, countOf("sale_draft_lines"))
        assertEquals(1, changes.calls)
        assertEquals(1, listener.calls)
    }

    @Test
    fun failureInsideAnUncountedWriteLeavesNothingAndComesOutUnchanged() = runBlocking {
        val before = database.snapshot()
        val injected = InjectedFailure()

        try {
            runner.write<Unit> {
                val id = database.saleDraftDao().insert(draft())
                database.saleDraftDao().insertLine(line(id))
                throw injected
            }
            fail("La falla debía salir del runner")
        } catch (failure: InjectedFailure) {
            assertSame(injected, failure)
        }

        assertEquals(before, database.snapshot())
        assertEquals(listOf(0L, 0L), listOf(countOf("sale_drafts"), countOf("sale_draft_lines")))
        assertEquals(0, changes.calls)
        assertEquals(0, listener.calls)
    }

    @Test
    fun brokenConstraintInAnUncountedWriteIsAnInvariantViolation() = runBlocking {
        val before = database.snapshot()

        val failure =
            storageFailure {
                runner.write<Long> {
                    val id = database.saleDraftDao().insert(draft())
                    // Una línea de un producto que no existe: la llave foránea la rechaza.
                    database.saleDraftDao().insertLine(line(id).copy(productId = MISSING_ID))
                    TxResult.CommitUncounted(id)
                }
            }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(before, database.snapshot())
        assertEquals(0L, countOf("sale_drafts"))
        assertEquals(0, changes.calls)
    }

    @Test
    fun restoreInProgressRejectsAnUncountedWriteBeforeStarting() = runBlocking {
        val guarded = runnerFor(database, changes, restoreLock = RestoreLock { true })
        var started = false

        val failure =
            storageFailure {
                guarded.write<Long> {
                    started = true
                    TxResult.CommitUncounted(database.saleDraftDao().insert(draft()))
                }
            }

        assertEquals(StorageFailure.RESTORE_IN_PROGRESS, failure)
        assertFalse("El bloque no debía empezar", started)
        assertEquals(0L, countOf("sale_drafts"))
    }

    @Test
    fun uncountedWriteInsideAnotherTransactionIsRefused() = runBlocking {
        val before = database.snapshot()

        try {
            runner.write {
                database.saleDraftDao().insert(draft())
                runner.write { TxResult.CommitUncounted(database.saleDraftDao().insert(draft())) }
                TxResult.CommitUncounted(Unit)
            }
            fail("La escritura anidada debía rechazarse")
        } catch (expected: IllegalStateException) {
            // Rechazada, y la de afuera se deshizo entera.
        }

        assertEquals(before, database.snapshot())
        assertEquals(0L, countOf("sale_drafts"))
    }

    @Test
    fun busyOnceIsRetriedAndCommitsOnceWithoutCounting() = runBlocking {
        val before = database.snapshot()
        var attempts = 0

        val draftId =
            runner.write {
                attempts++
                val id = database.saleDraftDao().insert(draft())
                if (attempts == 1) throw sqliteError(SQLITE_BUSY, "database is locked")
                TxResult.CommitUncounted(id)
            }

        assertEquals(2, attempts)
        // Una sola espera: el intento fallido se deshizo entero antes del segundo.
        assertEquals(listOf(draftId), database.saleDraftDao().observeAll().first().map { it.id })
        assertEquals(before, database.snapshot())
        assertEquals(0, changes.calls)
        assertEquals(0, listener.calls)
    }

    /** Guarda una espera con dos líneas, como la pantalla de venta al dejarla en espera. */
    private suspend fun saveDraft(runner: RoomTransactionRunner): Long = runner.write {
        val id = database.saleDraftDao().insert(draft())
        database.saleDraftDao().insertLine(line(id))
        database.saleDraftDao().insertLine(line(id).copy(enteredQuantityMilli = 500))
        TxResult.CommitUncounted(id)
    }

    private suspend fun countOf(table: String): Long =
        database.useReaderConnection { it.longValue("SELECT COUNT(*) FROM $table") }

    private fun draft() = SaleDraftEntity(
        label = "Mesa 2",
        customerId = null,
        priceListId = null,
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )

    private fun line(draftId: Long) = SaleDraftLineEntity(
        draftId = draftId,
        productId = stock.first,
        unitId = UNIT_ID,
        enteredQuantityMilli = 2_000,
        unitPrice = 2_500,
        lineDiscount = 0,
        parentLineId = null,
    )

    private companion object {
        const val MISSING_ID = 999_999L
        const val SQLITE_BUSY = 5
        val ONE_HOUR_LATER = Clock { Instant.fromEpochMilliseconds(CREATED_AT + 3_600_000L) }
    }
}
