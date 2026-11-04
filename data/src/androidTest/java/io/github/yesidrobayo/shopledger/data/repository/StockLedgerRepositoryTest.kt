package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.LOCATION_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockBalanceDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockBalanceEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockMovementDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockMovementEntity
import io.github.yesidrobayo.shopledger.data.local.stock.location
import io.github.yesidrobayo.shopledger.data.local.stock.lot
import io.github.yesidrobayo.shopledger.data.transaction.InjectedFailure
import io.github.yesidrobayo.shopledger.data.transaction.RecordingChangeCounter
import io.github.yesidrobayo.shopledger.data.transaction.runnerFor
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El libro de stock y sus dos copias: el saldo, el costo y el valor del producto, y el saldo de cada
 * parte por ubicación y lote. Lo que se prueba es que nunca queda una sin las otras, y que el saldo
 * del producto siempre es la suma de su libro y la de sus partes.
 */
@RunWith(AndroidJUnit4::class)
internal class StockLedgerRepositoryTest : RepositoryTest() {
    private lateinit var ledger: RoomStockLedgerRepository

    @Before
    fun createLedger() {
        ledger = RoomStockLedgerRepository(database)
    }

    @Test
    fun appendWritesTheRowTheProductAndItsPartTogether() = runBlocking {
        val row = openingRow(productId, units = 10)

        val id = commit { ledger.append(row) }

        assertEquals(row.recordedAs(id), ledger.findById(id))
        val product = requireNotNull(database.productDao().findById(productId))
        assertEquals(
            listOf(10_000L, 1_200L, 12_000L),
            listOf(product.onHandMilli, product.averageCost, product.stockValue),
        )
        assertEquals(10_000L, balanceOf(productId, LOCATION_ID, lotId = null))
        assertEquals(1L, changeSeq())
        assertLedgerBalanced()
    }

    @Test
    fun eachPartAddsItsOwnRowsByLocationAndLot() = runBlocking {
        val warehouse = database.locationDao().insert(location("Bodega"))
        val lotted =
            commit {
                RoomProductRepository(
                    database,
                ).insert(newProduct("Yogur", productDetails("Yogur", tracksLots = true)))
            }
        val lotA = database.stockLotDao().insert(lot(lotted, "A-01"))
        val lotB = database.stockLotDao().insert(lot(lotted, "B-02"))

        commit {
            ledger.append(openingRow(productId, units = 10))
            ledger.append(
                ledgerRow(
                    productId,
                    StockMovementType.OPENING_BALANCE,
                    5_000,
                    15_000,
                    locationId = warehouse,
                ),
            )
            ledger.append(openingRow(lotted, units = 3, lotId = lotA))
            ledger.append(
                ledgerRow(lotted, StockMovementType.OPENING_BALANCE, 2_000, 5_000, lotId = lotB),
            )
        }
        // Dos unidades dañadas en la bodega: la bodega baja, la ubicación inicial no.
        commit {
            ledger.append(
                ledgerRow(
                    productId,
                    StockMovementType.ADJUSTMENT,
                    -2_000,
                    13_000,
                    locationId = warehouse,
                    reason = StockAdjustmentReason.DAMAGED,
                ),
            )
        }

        assertEquals(10_000L, balanceOf(productId, LOCATION_ID, lotId = null))
        assertEquals(3_000L, balanceOf(productId, warehouse, lotId = null))
        assertEquals(3_000L, balanceOf(lotted, LOCATION_ID, lotA))
        assertEquals(2_000L, balanceOf(lotted, LOCATION_ID, lotB))
        assertEquals(13_000L, database.stockBalanceDao().sumByProduct(productId))
        assertLedgerBalanced()
    }

    @Test
    fun aCostCorrectionMovesTheCostButNoPart() = runBlocking {
        commit { ledger.append(openingRow(productId, units = 10)) }

        commit {
            ledger.append(
                ledgerRow(
                    productId,
                    StockMovementType.COST_CORRECTION,
                    quantity = 0,
                    balanceAfter = 10_000,
                    unitCost = 1_500,
                    totalCost = 0,
                    averageCostAfter = 1_500,
                    stockValueAfter = 15_000,
                ),
            )
        }

        val product = requireNotNull(database.productDao().findById(productId))
        assertEquals(
            listOf(10_000L, 1_500L, 15_000L),
            listOf(product.onHandMilli, product.averageCost, product.stockValue),
        )
        assertEquals(
            listOf(10_000L),
            database.stockBalanceDao().findByProduct(productId).map {
                it.onHandMilli
            },
        )
        assertLedgerBalanced()
    }

    @Test
    fun aRowWhoseBalanceDoesNotAddUpIsRejectedAndWritesNothing() = runBlocking {
        commit { ledger.append(openingRow(productId, units = 10)) }
        val before = state()

        // Una unidad menos debería dejar 9: el caso de uso calculó con un saldo viejo.
        val failure =
            storageFailureOf {
                commit {
                    ledger.append(
                        ledgerRow(
                            productId,
                            StockMovementType.ADJUSTMENT,
                            -1_000,
                            8_000,
                            reason = StockAdjustmentReason.LOST_OR_STOLEN,
                        ),
                    )
                }
            }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(before, state())
    }

    @Test
    fun aRowOfAMissingProductOrThatDoesNotFitIsRejected() = runBlocking {
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf { commit { ledger.append(openingRow(MISSING_ID)) } },
        )
        // Un saldo de cabecera en el borde de Long: sumar una unidad más no cabe.
        execute("UPDATE products SET on_hand_milli = ${Long.MAX_VALUE} WHERE id = $productId")
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf {
                commit {
                    ledger.append(
                        ledgerRow(
                            productId,
                            StockMovementType.OPENING_BALANCE,
                            1_000,
                            Long.MIN_VALUE,
                        ),
                    )
                }
            },
        )
        assertEquals(0L, count("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test
    fun aRowTheGuardRejectsComesOutTyped() = runBlocking {
        // Una venta sin su línea de venta: la guarda del libro la rechaza.
        val failure =
            storageFailureOf {
                commit {
                    ledger.append(ledgerRow(productId, StockMovementType.SALE, -1_000, -1_000))
                }
            }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(EMPTY, state())
    }

    @Test
    fun appendOutsideTheTransactionWritesNothing() = runBlocking {
        try {
            ledger.append(openingRow(productId))
            fail("Fuera de la transacción no debía escribir")
        } catch (expected: IllegalStateException) {
            // Esperada: cada sentencia se confirmaría sola.
        }

        assertEquals(EMPTY, state())
    }

    @Test
    fun aFailureAfterAnyStepLeavesNoPartWithoutTheOthers() = runBlocking {
        // Una fila ya confirmada, que ninguna falla posterior debe tocar.
        commit { ledger.append(openingRow(productId, units = 10)) }
        val confirmed = state()
        for (step in AppendStep.entries) {
            val switch = FailureSwitch(step)
            val failing = failingLedger(switch)

            try {
                commit {
                    // Dos filas en la misma escritura: la primera termina, la segunda falla a medias.
                    failing.append(
                        ledgerRow(
                            productId,
                            StockMovementType.ADJUSTMENT,
                            -1_000,
                            9_000,
                            reason = StockAdjustmentReason.OWN_USE,
                        ),
                    )
                    switch.armed = true
                    failing.append(
                        ledgerRow(
                            productId,
                            StockMovementType.ADJUSTMENT,
                            -3_000,
                            6_000,
                            reason = StockAdjustmentReason.EXPIRED,
                        ),
                    )
                }
                fail("Debía fallar tras $step")
            } catch (expected: InjectedFailure) {
                // Esperada.
            }

            assertEquals("Tras $step", confirmed, state())
            assertLedgerBalanced()
        }
    }

    @Test
    fun aFailureWhileCountingTheChangeUndoesTheRows() = runBlocking {
        val counter = RecordingChangeCounter(database)
        counter.failure = InjectedFailure()
        val failingRunner = runnerFor(database, counter)

        try {
            failingRunner.write { TxResult.Commit(ledger.append(openingRow(productId))) }
            fail("Debía fallar al contar el cambio")
        } catch (expected: InjectedFailure) {
            // Esperada.
        }

        assertEquals(EMPTY, state())
    }

    @Test
    fun readsComeInLedgerOrderAndFindTheirDocuments() = runBlocking {
        val sales = RoomSaleRepository(database)
        val purchases = RoomPurchaseRepository(database)
        val purchaseId = commit { purchases.insert(newPurchase(supplierId = null)) }
        val purchaseLine = commit { purchases.insertLine(purchaseId, newPurchaseLine(productId)) }
        val saleId = commit { sales.insert(newSale(priceListId)) }
        val saleLineId = commit { sales.insertLine(saleId, newSaleLine(productId, priceListId)) }

        val bought =
            commit {
                ledger.append(
                    ledgerRow(
                        productId,
                        StockMovementType.PURCHASE,
                        12_000,
                        12_000,
                        unitCost = 1_667,
                        totalCost = 20_000,
                        averageCostAfter = 1_667,
                        stockValueAfter = 20_000,
                        source = StockMovementSource(purchaseLineId = purchaseLine),
                    ),
                )
            }
        val sold =
            commit {
                ledger.append(
                    ledgerRow(
                        productId,
                        StockMovementType.SALE,
                        -2_000,
                        10_000,
                        unitCost = 1_667,
                        totalCost = -3_333,
                        averageCostAfter = 1_667,
                        stockValueAfter = 16_667,
                        source = StockMovementSource(saleLineId = saleLineId),
                    ),
                )
            }
        assertFalse(ledger.isCompensated(sold))
        val undone =
            commit {
                ledger.append(
                    ledgerRow(
                        productId,
                        StockMovementType.SALE_VOID,
                        2_000,
                        12_000,
                        unitCost = 1_667,
                        totalCost = 3_333,
                        averageCostAfter = 1_667,
                        stockValueAfter = 20_000,
                        reversesMovementId = sold,
                        source = StockMovementSource(saleLineId = saleLineId),
                    ),
                )
            }

        assertEquals(listOf(bought, sold, undone), ledger.findByProduct(productId).map { it.id })
        assertEquals(listOf(sold, undone), ledger.findBySale(saleId).map { it.id })
        assertEquals(listOf(bought), ledger.findByPurchase(purchaseId).map { it.id })
        assertTrue(ledger.isCompensated(sold))
        assertFalse(ledger.isCompensated(bought))
        assertNull(ledger.findById(MISSING_ID))
        assertLedgerBalanced()
    }

    @Test
    fun observeByProductEmitsEachConfirmedRowAndNothingUndone() = runBlocking {
        ledger.observeByProduct(productId).test {
            assertEquals(emptyList<Long>(), awaitItem().map { it.id })

            val first = commit { ledger.append(openingRow(productId, units = 10)) }
            assertEquals(listOf(first), awaitItem().map { it.id })

            // Deshecha: nunca llega a la lista, ni siquiera por un momento.
            runner.write {
                ledger.append(
                    ledgerRow(
                        productId,
                        StockMovementType.ADJUSTMENT,
                        -1_000,
                        9_000,
                        reason = StockAdjustmentReason.GIFT_OR_SAMPLE,
                    ),
                )
                TxResult.Rollback(Unit)
            }
            val second =
                commit {
                    ledger.append(
                        ledgerRow(
                            productId,
                            StockMovementType.ADJUSTMENT,
                            -2_000,
                            8_000,
                            reason = StockAdjustmentReason.DAMAGED,
                        ),
                    )
                }
            val rows = awaitItem()
            assertEquals(listOf(first, second), rows.map { it.id })
            assertEquals(listOf(10_000L, 8_000L), rows.map { it.balanceAfter.milli })
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun balanceOf(productId: Long, locationId: Long, lotId: Long?): Long? =
        database.stockBalanceDao().find(
            productId,
            locationId,
            StockBalanceEntity.lotKeyOf(lotId),
        )?.onHandMilli

    /** Lo que una escritura a medias cambiaría: el libro, el producto, sus partes y el contador. */
    private data class LedgerState(
        val movements: Long,
        val onHand: Long,
        val averageCost: Long,
        val stockValue: Long,
        val parts: List<Long>,
        val changeSeq: Long,
    )

    private suspend fun state(): LedgerState {
        val product = requireNotNull(database.productDao().findById(productId))
        return LedgerState(
            movements = count("SELECT COUNT(*) FROM stock_movements"),
            onHand = product.onHandMilli,
            averageCost = product.averageCost,
            stockValue = product.stockValue,
            parts = database.stockBalanceDao().findByProduct(productId).map { it.onHandMilli },
            changeSeq = changeSeq(),
        )
    }

    /**
     * Los invariantes del libro: el saldo de cada producto es la suma de su libro y la de sus partes,
     * cada parte es la suma de sus filas, y cada fila deja el saldo anterior más su cantidad.
     */
    private suspend fun assertLedgerBalanced() {
        val broken =
            INVARIANTS.mapValues { (_, sql) -> count(sql) }.filterValues { it != 0L }
        assertEquals("Invariantes rotos", emptyMap<String, Long>(), broken)
    }

    /** Los pasos de [RoomStockLedgerRepository.append] tras los que una prueba lo hace fallar. */
    private enum class AppendStep { MOVEMENT, PRODUCT, PART }

    /** Falla después de [step], solo cuando está armado: así la primera fila de la escritura pasa. */
    private class FailureSwitch(val step: AppendStep) {
        var armed = false

        fun after(reached: AppendStep) {
            if (armed && reached == step) throw InjectedFailure()
        }
    }

    /** El repositorio real, con sus DAO envueltos para que fallen tras el paso de [switch]. */
    private fun failingLedger(switch: FailureSwitch): RoomStockLedgerRepository {
        val movements =
            object : StockMovementDao by database.stockMovementDao() {
                override suspend fun insert(movement: StockMovementEntity): Long =
                    database.stockMovementDao().insert(movement).also {
                        switch.after(AppendStep.MOVEMENT)
                    }
            }
        val products =
            object : ProductDao by database.productDao() {
                override suspend fun updateStockState(
                    id: Long,
                    onHandMilli: Long,
                    averageCost: Long,
                    stockValue: Long,
                    updatedAt: Long,
                ): Int = database.productDao()
                    .updateStockState(id, onHandMilli, averageCost, stockValue, updatedAt)
                    .also { switch.after(AppendStep.PRODUCT) }
            }
        val balances =
            object : StockBalanceDao by database.stockBalanceDao() {
                override suspend fun add(
                    productId: Long,
                    locationId: Long,
                    lotKey: Long,
                    deltaMilli: Long,
                ) {
                    database.stockBalanceDao().add(productId, locationId, lotKey, deltaMilli)
                    switch.after(AppendStep.PART)
                }
            }
        return RoomStockLedgerRepository(movements, products, balances, RepositoryWrites(database))
    }

    private companion object {
        const val MISSING_ID = 999L

        /** Sin filas, sin stock y sin cambios: lo que deja una escritura deshecha sobre una base nueva. */
        val EMPTY = LedgerState(0, 0, 1_200, 0, emptyList(), 0)

        val INVARIANTS =
            mapOf(
                "saldo igual a la suma del libro" to
                    "SELECT COUNT(*) FROM products p WHERE p.on_hand_milli <> COALESCE(" +
                    "(SELECT SUM(m.quantity_milli) FROM stock_movements m " +
                    "WHERE m.product_id = p.id), 0)",
                "cada fila deja el saldo anterior más su cantidad" to
                    "SELECT COUNT(*) FROM (SELECT balance_after_milli AS after, " +
                    "quantity_milli AS q, " +
                    "LAG(balance_after_milli, 1, 0) " +
                    "OVER (PARTITION BY product_id ORDER BY id) AS before " +
                    "FROM stock_movements) WHERE after <> before + q",
                "saldo igual a la suma de sus partes" to
                    "SELECT COUNT(*) FROM products p WHERE p.on_hand_milli <> COALESCE(" +
                    "(SELECT SUM(b.on_hand_milli) FROM stock_balances b " +
                    "WHERE b.product_id = p.id), 0)",
                "cada parte igual a la suma de sus filas" to
                    "SELECT COUNT(*) FROM (SELECT product_id, location_id, " +
                    "COALESCE(lot_id, 0) AS lot_key, " +
                    "SUM(quantity_milli) AS total FROM stock_movements GROUP BY 1, 2, 3) m " +
                    "WHERE m.total <> COALESCE((SELECT b.on_hand_milli FROM stock_balances b " +
                    "WHERE b.product_id = m.product_id AND b.location_id = m.location_id " +
                    "AND b.lot_key = m.lot_key), 0)",
                "ninguna parte sin sus filas" to
                    "SELECT COUNT(*) FROM stock_balances b WHERE b.on_hand_milli <> COALESCE(" +
                    "(SELECT SUM(m.quantity_milli) FROM stock_movements m " +
                    "WHERE m.product_id = b.product_id AND m.location_id = b.location_id " +
                    "AND COALESCE(m.lot_id, 0) = b.lot_key), 0)",
            )
    }
}
