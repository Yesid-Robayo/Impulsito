package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El índice de precios con su fuente sobre la base real: cada mes se agrega, se edita con su
 * fuente y se quita, y se lee del más antiguo al más reciente. Valores y fuentes inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PriceIndexEntriesTest : CatalogDatabaseTest() {
    private val index by lazy { RoomPriceIndexRepository(database) }
    private val october = YearMonth(2026, 10)
    private val september = YearMonth(2026, 9)

    /** Corre [block] dentro de una transacción, como lo hace el caso de uso. */
    private suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withWriteTransaction { block() }

    @Test
    fun aMonthIsAddedEditedAndRemovedWithItsSource() = runBlocking {
        inTransaction {
            index.insertEntry(PriceIndexEntry(october, BasisPoints(190), "Lista propia"), AT)
            index.insertEntry(PriceIndexEntry(september, BasisPoints(-25), null), AT)
        }

        assertEquals(
            listOf(
                PriceIndexEntry(september, BasisPoints(-25), null),
                PriceIndexEntry(october, BasisPoints(190), "Lista propia"),
            ),
            index.observeEntries().first(),
        )

        inTransaction { index.updateEntry(PriceIndexEntry(october, BasisPoints(210), null), AT) }
        assertEquals(
            PriceIndexEntry(october, BasisPoints(210), null),
            index.observeEntries().first().last(),
        )

        assertTrue(inTransaction { index.delete("2026-10") })
        assertFalse(inTransaction { index.delete("2026-10") })
        assertEquals(listOf(september), index.observeEntries().first().map { it.month })
    }

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_793_600_000_000L)
    }
}
