package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cómo pasan los valores a sus columnas y de vuelta: los instantes se truncan al milisegundo, y un
 * valor guardado que las guardas no admiten sale como regla rota, sin adivinar.
 */
@RunWith(AndroidJUnit4::class)
internal class StoredValuesTest : RepositoryTest() {
    @Test
    fun instantsAreTruncatedToTheMillisecondTowardThePast() {
        val second = 1_767_225_600L
        assertEquals(second * 1_000, Instant.fromEpochSeconds(second, 0).toStoredMillis())
        assertEquals(
            second * 1_000 + 123,
            Instant.fromEpochSeconds(second, 123_999_999).toStoredMillis(),
        )
        assertEquals(
            second * 1_000 + 999,
            Instant.fromEpochSeconds(second, 999_999_999).toStoredMillis(),
        )
        // Antes de 1970 también hacia el pasado: un nanosegundo antes del origen es el milisegundo -1.
        assertEquals(-1L, Instant.fromEpochSeconds(-1, 999_999_999).toStoredMillis())
        assertEquals(-1_000L, Instant.fromEpochSeconds(-1, 0).toStoredMillis())
        // Lo que vuelve de la base es el mismo instante, sin la fracción.
        val stored = Instant.fromEpochSeconds(second, 456_789_000).toStoredMillis()
        assertEquals(Instant.fromEpochMilliseconds(second * 1_000 + 456), stored.toInstant())
        assertEquals(stored, stored.toInstant().toStoredMillis())
    }

    @Test
    fun anInstantOutsideLongIsRejectedInsteadOfSaturated() {
        try {
            Instant.fromEpochSeconds(Long.MAX_VALUE / 1_000 + 1).toStoredMillis()
            fail("Debía rechazar un instante que no cabe en milisegundos")
        } catch (expected: ArithmeticException) {
            // Esperada.
        }
    }

    @Test
    fun aStoredValueTheGuardsWouldRejectIsABrokenRow() = runBlocking {
        val broken: List<() -> Unit> =
            listOf(
                { storedEnum<DocumentStatus>("PENDIENTE") },
                // El nombre exacto: otra capitalización no es el mismo valor.
                { storedEnum<DocumentStatus>("active") },
                { 2L.toFlag() },
                { (Int.MAX_VALUE + 1L).toStoredInt() },
                { storedDate("2026-02-30") },
                { storedDate("2026-1-05") },
                { storedVoiding("ACTIVE", voidedAt = 1L, reason = null, note = null) },
                { storedVoiding("ACTIVE", voidedAt = null, reason = null, note = "Sin anular") },
                { storedVoiding("VOIDED", voidedAt = null, reason = "UNDO", note = null) },
                { storedVoiding("VOIDED", voidedAt = 1L, reason = null, note = null) },
                { requireOneRow(0) },
                { requireOneRow(2) },
            )
        for ((index, read) in broken.withIndex()) {
            assertEquals(
                "Caso $index",
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf {
                    read()
                },
            )
        }
        assertEquals(DocumentStatus.VOIDED, storedEnum<DocumentStatus>("VOIDED"))
        assertTrue(1L.toFlag())
        assertFalse(0L.toFlag())
        assertEquals(Int.MIN_VALUE, Int.MIN_VALUE.toLong().toStoredInt())
        assertNull(storedVoiding("ACTIVE", voidedAt = null, reason = null, note = null))
        assertEquals(
            Voiding(Instant.fromEpochMilliseconds(5), VoidReason.OTHER, null),
            storedVoiding("VOIDED", voidedAt = 5L, reason = "OTHER", note = null),
        )
    }

    @Test
    fun anErrorThatIsNotOfStorageLeavesTheWritesAsItIs() = runBlocking {
        val writes = RepositoryWrites(database)
        val missingTable: suspend () -> Unit = {
            database.useWriterConnection { it.executeSQL("UPDATE missing_table SET x = 1") }
        }

        // Una tabla que no existe es un error de programación: sigue su camino tal cual, sin
        // disfrazarse de regla rota ni de disco lleno.
        for (attempt in listOf<suspend () -> Unit>({
            writes.standalone { missingTable() }
        }, {
            runner.write {
                writes.inTransaction { missingTable() }
                TxResult.Commit(Unit)
            }
        })) {
            try {
                attempt()
                fail("Debía fallar")
            } catch (expected: SQLiteException) {
                assertTrue(expected.message.orEmpty().contains("no such table"))
            }
        }
    }
}
