package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.LOCATION_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.OpeningBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RegisterOpeningBalanceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.AdjustStockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Un producto que se controla por lotes sobre la base real: cada fila con cantidad lleva lote, el
 * lote sin asignar se crea una sola vez y es el único que puede quedar negativo, y las consultas
 * de invariantes quedan en cero, también las dos del lote. Productos y lotes inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class StockLotsOnDatabaseTest : RepositoryTest() {
    private lateinit var ports: DataPorts
    private lateinit var adjust: AdjustStockUseCase
    private lateinit var opening: RegisterOpeningBalanceUseCase

    @Before
    fun connectUseCases() {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
        val calendar = BusinessCalendarSource(ports.settings)
        val currencyLock = CurrencyLock(ports.settings)
        adjust = AdjustStockUseCase(
            runner, ports.products, ports.units, ports.systemRows, ports.stockLedger,
            ports.lowStockAlerts, calendar, currencyLock, FIXED_CLOCK, ports.stockLots,
        )
        opening = RegisterOpeningBalanceUseCase(
            runner, ports.products, ports.units, ports.systemRows, ports.stockLedger,
            ports.lowStockAlerts, calendar, currencyLock, FIXED_CLOCK,
        )
    }

    private suspend fun lotProduct(name: String = "Queso inventado"): Long = commit {
        ports.products.insert(newProduct(name, productDetails(name, tracksLots = true)))
    }

    private suspend fun lot(productId: Long, code: String, expiry: BusinessDate? = null): Long =
        commit {
            ports.stockLots.insert(
                NewStockLot(
                    productId = productId,
                    code = code,
                    expiryDate = expiry,
                    purchaseLineId = null,
                    createdAt = AT,
                ),
            )
        }

    private fun takeOut(productId: Long, units: Long, lotId: Long? = null) =
        StockAdjustmentRequest.Change(
            productId,
            StockAdjustmentReason.EXPIRED,
            QuantityMilli.ofUnits(units),
            lotId = lotId,
        )

    @Test
    fun everyRowOfALotProductCarriesALotAndOnlyTheUnassignedGoesNegative() = runBlocking {
        val id = lotProduct()
        val expiry = requireNotNull(BusinessDate.parse("2026-11-20"))
        val lotA = lot(id, "A-118", expiry)
        commit { ports.stockLedger.append(openingRow(id, units = 5, lotId = lotA)) }

        assertTrue(adjust(takeOut(id, 2, lotId = lotA)) is StockAdjustmentResult.Adjusted)
        assertTrue(adjust(takeOut(id, 9)) is StockAdjustmentResult.Adjusted)
        val counted = adjust(StockAdjustmentRequest.Count(id, QuantityMilli.ofUnits(10)))
        assertTrue(counted is StockAdjustmentResult.Adjusted)
        assertEquals(
            StockAdjustmentResult.AboveLotStock(QuantityMilli.ofUnits(3)),
            adjust(takeOut(id, 4, lotId = lotA)),
        )

        val unassigned = requireNotNull(database.stockLotDao().findUnassigned(id)).id
        val rows = ports.stockLedger.findByProduct(id)
        val lotIds = rows.map { it.movement.key.lotId }
        assertEquals(listOf(lotA, lotA, unassigned, unassigned), lotIds)
        // 5 de A, salen 2 de A y 9 sin lote; el conteo de 10 sobre -6 suma 16 al sin asignar.
        assertEquals(
            listOf(
                LotStock(lotA, "A-118", expiry, false, QuantityMilli(3_000)),
                LotStock(unassigned, null, null, true, QuantityMilli(7_000)),
            ),
            ports.stockLots.findStock(id, LOCATION_ID),
        )
        assertEquals(
            QuantityMilli(7_000),
            ports.stockLedger.onHandAt(StockKey(id, LOCATION_ID, unassigned)),
        )
        assertEquals(QuantityMilli(10_000), requireNotNull(ports.products.findById(id)).onHand)
        assertInvariantsHold()
    }

    @Test
    fun theUnassignedLotIsCreatedOnceAndMayStayNegative() = runBlocking {
        val id = lotProduct()

        val registered = opening(id, QuantityMilli.ofUnits(2), Money(1_200))
        assertTrue(registered is OpeningBalanceResult.Registered)
        assertTrue(adjust(takeOut(id, 5)) is StockAdjustmentResult.Adjusted)

        val lots = ports.stockLots.findStock(id, LOCATION_ID)
        assertEquals(1, lots.size)
        assertTrue(lots.single().isUnassigned)
        assertEquals(QuantityMilli(-3_000), lots.single().onHand)
        assertEquals(
            listOf(StockMovementType.OPENING_BALANCE, StockMovementType.ADJUSTMENT),
            ports.stockLedger.findByProduct(id).map { it.movement.type },
        )
        assertInvariantsHold()
    }

    @Test
    fun aCodedLotBelowZeroIsCaughtByTheInvariant() = runBlocking {
        val id = lotProduct()
        val lotA = lot(id, "A-1")
        // Fuera del caso de uso, como lo dejaría una copia dañada: un lote con código en -1.
        commit {
            ports.stockLedger.append(
                ledgerRow(
                    id,
                    StockMovementType.ADJUSTMENT,
                    -1_000,
                    -1_000,
                    lotId = lotA,
                    reason = StockAdjustmentReason.DAMAGED,
                ),
            )
        }

        val query = InvariantQueries.MODEL.single {
            it.label == "I-12, solo el lote sin asignar queda negativo"
        }
        val rows = query.sql.trimEnd().removeSuffix(";")
        assertEquals(1L, count("SELECT COUNT(*) FROM ($rows)"))
    }

    @Test
    fun aRepeatedCodeInTheSameProductIsABrokenRule() = runBlocking {
        val id = lotProduct()
        val other = lotProduct("Leche inventada")
        lot(id, "SN-0001")
        // El mismo código en otro producto sí vale: es único dentro del producto.
        lot(other, "SN-0001")

        assertEquals(StorageFailure.INVARIANT_VIOLATION, storageFailureOf { lot(id, "SN-0001") })
        assertEquals("SN-0001", ports.stockLots.findByCode(other, "SN-0001")?.code)
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
