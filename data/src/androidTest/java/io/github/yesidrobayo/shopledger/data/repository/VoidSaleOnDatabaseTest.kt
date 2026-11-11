package io.github.yesidrobayo.shopledger.data.repository

import android.util.Log
import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.LOCATION_ID
import io.github.yesidrobayo.shopledger.data.local.stock.StockMovementEntity
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Anular una venta sobre la base real, con sus guardas: el compensatorio, la anulación y el costo
 * reproducido pasan las guardas y dejan en cero cada consulta de invariantes. Además mide cuánto
 * tarda anular una venta de un producto con 50.000 filas en su libro.
 *
 * Los productos y montos son inventados. Solo registra conteos y duraciones.
 */
@RunWith(AndroidJUnit4::class)
internal class VoidSaleOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val sales by lazy { RoomSaleRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
        )
    }

    private val voidSale by lazy {
        VoidSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            ledger = ledger,
            clock = Clock { LATER_AT },
        )
    }

    private suspend fun sell(productId: Long, milli: Long): Long {
        val method = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val request = SaleRequest(listOf(SaleLineRequest(productId, QuantityMilli(milli))), method)
        return (registerSale(request) as RegisterSaleResult.Registered).saleId
    }

    @Test
    fun aVoidPassesEveryGuardAndCompensatesOnce() = runBlocking {
        val id = commit {
            val id = products.insert(newProduct("Jabón inventado"))
            ledger.append(openingRow(id, units = 10))
            id
        }
        val saleId = sell(id, 4_000)
        val before = changeSeq()

        assertEquals(
            VoidSaleResult.Voided,
            voidSale(saleId, VoidReason.ENTRY_ERROR, "Nota inventada"),
        )
        assertEquals(VoidSaleResult.AlreadyVoided, voidSale(saleId, VoidReason.ENTRY_ERROR, null))

        assertEquals(before + 1, changeSeq())
        assertEquals(DocumentStatus.VOIDED, sales.findById(saleId)?.status)
        val rows = ledger.findBySale(saleId)
        assertEquals(
            listOf(StockMovementType.SALE, StockMovementType.SALE_VOID),
            rows.map { it.movement.type },
        )
        assertEquals(rows[0].id, rows[1].movement.reversesMovementId)
        val after = requireNotNull(products.findById(id))
        assertEquals(QuantityMilli(10_000), after.onHand)
        assertEquals(Money(1_200), after.averageCost)
        assertEquals(Money(12_000), after.stockValue)
        assertInvariantsHold()
    }

    @Test
    fun aVoidOnFiftyThousandRowsIsMeasured() = runBlocking {
        val id = commit { products.insert(newProduct("Producto inventado")) }
        seedAdjustments(id, ROWS)
        val saleId = sell(id, 1_000)
        assertTrue(ledger.findByProduct(id).size > ROWS)

        val mark = TimeSource.Monotonic.markNow()
        val result = voidSale(saleId, VoidReason.ENTRY_ERROR, null)
        val elapsed = mark.elapsedNow().inWholeMilliseconds

        assertEquals(VoidSaleResult.Voided, result)
        Log.i(TAG, "anulación con ${ROWS + 2} filas del producto: $elapsed ms")
        assertInvariantsHold()
        // El tope del requisito se mide en el teléfono de referencia; aquí se anota la medida, y
        // solo se exige que no esté lejos de él.
        assertTrue("Anulación en $elapsed ms", elapsed <= EMULATOR_CEILING_MS)
    }

    /**
     * [rows] ajustes de conteo alternados, una unidad que entra y otra que sale al costo inicial,
     * en una sola transacción: el saldo vuelve a cero cada dos filas, así que el producto no
     * cambia.
     */
    private suspend fun seedAdjustments(productId: Long, rows: Int) {
        val dao = database.stockMovementDao()
        database.withWriteTransaction {
            for (index in 0 until rows) {
                val entering = index % 2 == 0
                dao.insert(
                    StockMovementEntity(
                        productId = productId,
                        locationId = LOCATION_ID,
                        lotId = null,
                        type = StockMovementType.ADJUSTMENT.name,
                        reason = "COUNT_CORRECTION",
                        quantityMilli = if (entering) 1_000 else -1_000,
                        unitCost = 1_200,
                        totalCost = if (entering) 1_200 else -1_200,
                        averageCostAfter = 1_200,
                        stockValueAfter = if (entering) 1_200 else 0,
                        balanceAfterMilli = if (entering) 1_000 else 0,
                        reversesMovementId = null,
                        saleLineId = null,
                        saleLineComponentId = null,
                        purchaseLineId = null,
                        saleReturnLineId = null,
                        purchaseReturnLineId = null,
                        transferLineId = null,
                        productionBatchId = null,
                        countSessionId = null,
                        note = null,
                        occurredAt = AT.toEpochMilliseconds(),
                        createdAt = AT.toEpochMilliseconds(),
                        businessDate = DATE.toString(),
                    ),
                )
            }
        }
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }

    private companion object {
        const val TAG = "VoidSaleTiming"
        const val ROWS = 50_000

        /** El tope del requisito para anular, con margen para un emulador. */
        const val EMULATOR_CEILING_MS = 3_000L
    }
}
