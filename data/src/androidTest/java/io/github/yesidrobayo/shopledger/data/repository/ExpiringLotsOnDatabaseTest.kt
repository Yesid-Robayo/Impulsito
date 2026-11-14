package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los lotes que mira el aviso de vencimiento, sobre la base real: con código, vencimiento y stock
 * sumado en todas las ubicaciones, de productos sin archivar, y la marca que evita repetir el
 * aviso. Productos y lotes inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ExpiringLotsOnDatabaseTest : RepositoryTest() {
    private lateinit var ports: DataPorts

    @Before
    fun connectPorts() {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
    }

    private suspend fun lotProduct(name: String): Long = commit {
        ports.products.insert(newProduct(name, productDetails(name, tracksLots = true)))
    }

    private suspend fun lot(productId: Long, code: String, expiry: String?): Long = commit {
        ports.stockLots.insert(
            NewStockLot(
                productId = productId,
                code = code,
                expiryDate = expiry?.let { requireNotNull(BusinessDate.parse(it)) },
                purchaseLineId = null,
                createdAt = AT,
            ),
        )
    }

    /**
     * Entra [units] al lote [lotId] con un conteo, dejando el producto en [balanceAfter] unidades:
     * el libro exige el saldo que queda tras cada fila.
     */
    private suspend fun receive(productId: Long, lotId: Long, units: Long, balanceAfter: Long) =
        commit {
            ports.stockLedger.append(
                ledgerRow(
                    productId,
                    StockMovementType.ADJUSTMENT,
                    units * 1_000,
                    balanceAfter * 1_000,
                    lotId = lotId,
                    reason = StockAdjustmentReason.COUNT_CORRECTION,
                ),
            )
        }

    @Test
    fun onlyDatedLotsWithStockOfActiveProductsAreRead() = runBlocking {
        val cheese = lotProduct("Queso inventado")
        val later = lot(cheese, "Q-2", "2026-12-01")
        val sooner = lot(cheese, "Q-1", "2026-11-20")
        val empty = lot(cheese, "Q-3", "2026-11-10")
        val undated = lot(cheese, "Q-4", null)
        receive(cheese, later, units = 4, balanceAfter = 4)
        receive(cheese, sooner, units = 3, balanceAfter = 7)
        receive(cheese, undated, units = 2, balanceAfter = 9)
        val milk = lotProduct("Leche inventada")
        val archivedLot = lot(milk, "M-1", "2026-11-15")
        receive(milk, archivedLot, units = 1, balanceAfter = 1)
        commit {
            ports.products.setArchivedAt(milk, AT, AT)
        }

        val dated = ports.stockLots.observeDated().first()

        // Del que vence antes al que vence después; el vacío, el sin fecha y el archivado no salen.
        assertEquals(listOf(sooner, later), dated.map { it.lotId })
        assertTrue(empty !in dated.map { it.lotId })
        val first = dated.first()
        assertEquals("Q-1", first.code)
        assertEquals("Queso inventado", first.productName)
        assertEquals(QuantityMilli.ofUnits(3), first.onHand)
        assertNull(first.noticeDays)
        assertNull(first.expiryNotifiedAt)
    }

    @Test
    fun markingTheNoticeKeepsItForEachLot() = runBlocking {
        val cheese = lotProduct("Queso inventado")
        val lotA = lot(cheese, "Q-1", "2026-11-20")
        val lotB = lot(cheese, "Q-2", "2026-11-21")
        receive(cheese, lotA, units = 3, balanceAfter = 3)
        receive(cheese, lotB, units = 3, balanceAfter = 6)

        ports.stockLots.markExpiryNotified(listOf(lotA), AT)

        val dated = ports.stockLots.observeDated().first().associateBy { it.lotId }
        assertEquals(AT, dated.getValue(lotA).expiryNotifiedAt)
        assertNull(dated.getValue(lotB).expiryNotifiedAt)
    }

    @Test
    fun holdingsLeaveOutEmptyPartsAndCorrectingTheDateNoticesAgain() = runBlocking {
        val cheese = lotProduct("Queso inventado")
        val kept = lot(cheese, "Q-1", "2026-11-20")
        val emptied = lot(cheese, "Q-2", "2026-11-21")
        receive(cheese, kept, units = 3, balanceAfter = 3)
        receive(cheese, emptied, units = 2, balanceAfter = 5)
        receive(cheese, emptied, units = -2, balanceAfter = 3)

        val holdings = ports.stockLots.observeHoldings().first()
        assertEquals(listOf(kept), holdings.map { it.lotId })
        assertEquals(QuantityMilli.ofUnits(3), holdings.single().onHand)

        ports.stockLots.markExpiryNotified(listOf(kept), AT)
        val sameDay = requireNotNull(BusinessDate.parse("2026-11-20"))
        commit { ports.stockLots.correct(kept, "Q-1b", sameDay) }
        val renamed = requireNotNull(ports.stockLots.findById(kept))
        assertEquals("Q-1b", renamed.code)
        assertEquals(AT, renamed.expiryNotifiedAt)

        commit { ports.stockLots.correct(kept, "Q-1b", null) }
        val undated = requireNotNull(ports.stockLots.findById(kept))
        assertNull(undated.expiryDate)
        assertNull(undated.expiryNotifiedAt)
    }

    @Test
    fun theSettingsNoticeDaysAreSaved() = runBlocking {
        commit { ports.settings.updateExpiryNoticeDays(12, AT) }

        assertEquals(12, ports.settings.get().notices.expiryNoticeDays)
    }
}
