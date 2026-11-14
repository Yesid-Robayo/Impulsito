package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.movement
import io.github.yesidrobayo.shopledger.data.local.openingBalance
import io.github.yesidrobayo.shopledger.data.local.stock.StockBalanceEntity.Companion.NO_LOT
import io.github.yesidrobayo.shopledger.data.transaction.InjectedFailure
import io.github.yesidrobayo.shopledger.data.transaction.RecordingChangeCounter
import io.github.yesidrobayo.shopledger.data.transaction.runnerFor
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los saldos por producto, ubicación y lote: cada fila del libro suma su cantidad a su parte, en la
 * misma sentencia y en la misma transacción, sin leerla antes ni reemplazarla. Cada parte es suya,
 * el saldo puede quedar negativo, una suma que no cabe se rechaza, y el saldo de cada parte es la
 * suma de sus filas.
 */
@RunWith(AndroidJUnit4::class)
internal class StockBalanceDaoTest : LedgerDatabaseTest() {
    private val balances get() = database.stockBalanceDao()

    @Test
    fun aPartStartsWithItsFirstQuantityAndEachRowAddsToIt() = runBlocking {
        assertNull(balances.find(productId, MAIN_LOCATION_ID, NO_LOT))

        balances.add(productId, MAIN_LOCATION_ID, NO_LOT, 10_000)
        val created = balances.find(productId, MAIN_LOCATION_ID, NO_LOT)
        balances.add(productId, MAIN_LOCATION_ID, NO_LOT, -3_000)
        // Vender nunca se bloquea por falta de stock: el saldo puede quedar negativo.
        balances.add(productId, MAIN_LOCATION_ID, NO_LOT, -9_000)

        assertEquals(
            StockBalanceEntity(
                id = requireNotNull(created).id,
                productId = productId,
                locationId = MAIN_LOCATION_ID,
                lotKey = NO_LOT,
                onHandMilli = -2_000,
            ),
            balances.find(productId, MAIN_LOCATION_ID, NO_LOT),
        )
        // Una sola fila por parte: las sumas no crean otras.
        assertEquals(1, balances.findByProduct(productId).size)
        assertEquals(-2_000L, balances.sumByProduct(productId))
    }

    @Test
    fun eachProductLocationAndLotIsItsOwnPart() = runBlocking {
        val warehouse = database.locationDao().insert(location("Bodega"))
        val cheese = database.productDao().insert(product("Queso").copy(tracksLots = 1))
        val early = database.stockLotDao().insert(lot(cheese, "Q-01"))
        val late = database.stockLotDao().insert(lot(cheese, "Q-02"))
        val unassigned = database.stockLotDao().insert(unassignedLot(cheese))

        balances.add(productId, MAIN_LOCATION_ID, NO_LOT, 6_000)
        balances.add(productId, warehouse, NO_LOT, 4_000)
        balances.add(cheese, MAIN_LOCATION_ID, StockBalanceEntity.lotKeyOf(early), 3_000)
        balances.add(cheese, MAIN_LOCATION_ID, StockBalanceEntity.lotKeyOf(late), 5_000)
        balances.add(cheese, warehouse, StockBalanceEntity.lotKeyOf(late), 2_000)
        // El lote sin asignar absorbe lo vendido sin lote, y es el que queda negativo.
        balances.add(cheese, MAIN_LOCATION_ID, StockBalanceEntity.lotKeyOf(unassigned), -1_000)

        assertEquals(0L, StockBalanceEntity.lotKeyOf(null))
        assertEquals(early, StockBalanceEntity.lotKeyOf(early))
        assertEquals(
            listOf(6_000L, 4_000L),
            balances.findByProduct(productId).map { it.onHandMilli },
        )
        assertEquals(
            listOf(
                Triple(MAIN_LOCATION_ID, early, 3_000L),
                Triple(MAIN_LOCATION_ID, late, 5_000L),
                Triple(warehouse, late, 2_000L),
                Triple(MAIN_LOCATION_ID, unassigned, -1_000L),
            ),
            balances.findByProduct(cheese).map { Triple(it.locationId, it.lotKey, it.onHandMilli) },
        )
        assertEquals(
            listOf(productId to 4_000L, cheese to 2_000L),
            balances.findByLocation(warehouse).map { it.productId to it.onHandMilli },
        )
        assertEquals(10_000L, balances.sumByProduct(productId))
        assertEquals(9_000L, balances.sumByProduct(cheese))
        // Un producto sin filas no tiene saldos, y suma cero.
        val tea = database.productDao().insert(product("Té"))
        assertEquals(0L, balances.sumByProduct(tea))
        assertEquals(emptyList<StockBalanceEntity>(), balances.findByProduct(tea))
    }

    @Test
    fun aBalanceNeedsItsProductAndLocationAndKeepsThem() = runBlocking {
        constraintFailure(FOREIGN_KEY) { balances.add(MISSING_ID, MAIN_LOCATION_ID, NO_LOT, 1_000) }
        constraintFailure(FOREIGN_KEY) { balances.add(productId, MISSING_ID, NO_LOT, 1_000) }
        val stall = database.locationDao().insert(location("Puesto", kind = "STALL"))
        balances.add(productId, stall, NO_LOT, 1_000)

        // Con saldo, ni la ubicación ni el producto se borran.
        constraintFailure(FOREIGN_KEY) { database.locationDao().delete(stall) }
        constraintFailure(FOREIGN_KEY) { database.productDao().delete(productId) }
        assertEquals(1_000L, balances.sumByProduct(productId))
    }

    @Test
    fun aSumThatDoesNotFitIsRejectedAndKeepsTheBalance() = runBlocking {
        balances.add(productId, MAIN_LOCATION_ID, NO_LOT, Long.MAX_VALUE)
        // SQLite vuelve real una suma que no cabe, y la guarda rechaza el real.
        guardFailure(GUARD) { balances.add(productId, MAIN_LOCATION_ID, NO_LOT, 1) }
        assertEquals(
            Long.MAX_VALUE,
            balances.find(productId, MAIN_LOCATION_ID, NO_LOT)?.onHandMilli,
        )

        val warehouse = database.locationDao().insert(location("Bodega"))
        balances.add(productId, warehouse, NO_LOT, Long.MIN_VALUE)
        guardFailure(GUARD) { balances.add(productId, warehouse, NO_LOT, -1) }
        assertEquals(Long.MIN_VALUE, balances.find(productId, warehouse, NO_LOT)?.onHandMilli)
    }

    @Test
    fun theLedgerRowAndItsBalanceCommitOrRollBackTogether() = runBlocking {
        val runner = runnerFor(database, RecordingChangeCounter(database))
        val ledger = database.stockMovementDao()

        // Dos filas de la misma parte en una sola escritura: el saldo suma las dos.
        runner.write {
            ledger.insert(openingBalance(productId))
            balances.add(productId, MAIN_LOCATION_ID, NO_LOT, 10_000)
            ledger.insert(
                movement(productId, "ADJUSTMENT", -2_000, 1_200, -2_400, 8_000, 1_200, 9_600)
                    .copy(reason = "DAMAGED"),
            )
            balances.add(productId, MAIN_LOCATION_ID, NO_LOT, -2_000)
            TxResult.Commit(Unit)
        }
        assertEquals(8_000L, balances.sumByProduct(productId))
        assertEquals(ledgerSum(), balances.sumByProduct(productId))

        // Un rechazo deshace la fila y su saldo.
        runner.write {
            ledger.insert(
                movement(productId, "ADJUSTMENT", -1_000, 1_200, -1_200, 7_000, 1_200, 8_400)
                    .copy(reason = "EXPIRED"),
            )
            balances.add(productId, MAIN_LOCATION_ID, NO_LOT, -1_000)
            TxResult.Rollback(Unit)
        }
        // Una falla después de sumar, también.
        try {
            runner.write<Unit> {
                ledger.insert(
                    movement(productId, "ADJUSTMENT", -1_000, 1_200, -1_200, 7_000, 1_200, 8_400)
                        .copy(reason = "LOST_OR_STOLEN"),
                )
                balances.add(productId, MAIN_LOCATION_ID, NO_LOT, -1_000)
                throw InjectedFailure()
            }
            fail("La falla debía salir del runner")
        } catch (expected: InjectedFailure) {
            // Lo esperado.
        }

        assertEquals(2L, count("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(8_000L, balances.find(productId, MAIN_LOCATION_ID, NO_LOT)?.onHandMilli)
        assertEquals(ledgerSum(), balances.sumByProduct(productId))
    }

    @Test
    fun theBalancesOfAProductEmitAgainAfterEachCommit() = runBlocking {
        val emissions =
            Emissions(
                this,
                balances.observeByProduct(productId).map { rows -> rows.map { it.onHandMilli } },
            )
        try {
            emissions.awaitValue(emptyList())
            balances.add(productId, MAIN_LOCATION_ID, NO_LOT, 5_000)
            emissions.awaitValue(listOf(5_000L))
            balances.add(productId, MAIN_LOCATION_ID, NO_LOT, -1_500)
            emissions.awaitValue(listOf(3_500L))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun theDaoOnlyAddsAndReads() {
        // Una función nueva que fije, reemplace o borre un saldo rompe esta prueba a propósito: el
        // saldo solo cambia con la fila del libro que le suma.
        assertEquals(
            setOf(
                "add",
                "find",
                "findByProduct",
                "observeByProduct",
                "findByLocation",
                "sumByProduct",
                // Las lecturas del stock por ubicación: solo leen.
                "observeProductByLocation",
                "observeStockedProductCounts",
                "hasNonZeroAt",
            ),
            functionsOf(StockBalanceDao::class.java),
        )
    }

    /** Lo que suman las filas del libro del producto: el saldo de su única parte. */
    private suspend fun ledgerSum(): Long =
        count("SELECT SUM(quantity_milli) FROM stock_movements WHERE product_id = $productId")

    private companion object {
        const val MISSING_ID = 999L
        const val GUARD = "guard:stock_balances"
    }
}
