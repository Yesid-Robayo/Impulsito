package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.movement
import io.github.yesidrobayo.shopledger.data.local.openingBalance
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.CostReplayResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El libro de stock solo crece: cada columna en su lugar y entera, el orden del libro por `id`, una
 * sola compensación por fila, llaves que impiden filas huérfanas y borrar con historia, y columnas
 * que bastan para reproducir el costo del producto. Ninguna escritura edita ni borra una fila.
 */
@RunWith(AndroidJUnit4::class)
internal class StockMovementDaoTest : LedgerDatabaseTest() {
    private val ledger get() = database.stockMovementDao()
    private val products get() = database.productDao()

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        val saleLineId = insertSaleWithLine().id
        val purchaseLineId = insertPurchaseWithLine().id
        val lotProductId = products.insert(product("Queso").copy(tracksLots = 1))
        // Una fila por origen, y cada columna con un valor distinto dentro de su fila, para que dos
        // columnas cruzadas no pasen. Las columnas sin llave todavía admiten cualquier id. La
        // guarda pide que el lote exista, y la tabla de lotes todavía no existe: esta prueba, que
        // solo mira que cada columna vuelva de su lugar, la retira.
        execute("DROP TRIGGER guard_stock_movements_insert")
        val rows =
            listOf(
                movement(lotProductId, "PURCHASE", 12_000, 1_667, 20_000, 12_000, 1_668, 20_001)
                    .copy(lotId = 41, purchaseLineId = purchaseLineId, locationId = 2),
                movement(productId, "SALE", -2_000, 3_000, -6_001, -2_000, 3_002, 0)
                    .copy(saleLineId = saleLineId, saleLineComponentId = 51),
                movement(productId, "ADJUSTMENT", -500, 3_000, -1_500, -2_500, 3_001, 0).copy(
                    reason = "COUNT_CORRECTION",
                    countSessionId = 61,
                    note = "Conteo de cierre",
                ),
                movement(productId, "SALE_RETURN", 1_000, 2_900, 2_901, -1_500, 2_902, 2_903)
                    .copy(saleReturnLineId = 71),
                movement(productId, "PURCHASE_RETURN", -300, 2_800, -840, -1_800, 2_801, 2_802)
                    .copy(purchaseReturnLineId = 81),
                movement(productId, "TRANSFER_OUT", -200, 2_700, 0, -2_000, 2_701, 2_702)
                    .copy(transferLineId = 91, locationId = 3),
                movement(productId, "PRODUCTION_IN", 4_000, 2_600, 10_400, 2_000, 2_601, 2_602)
                    .copy(productionBatchId = 101),
            ).mapIndexed { index, row ->
                row.copy(occurredAt = CREATED_AT + index, createdAt = LATER + index)
            }

        val stored = rows.map { it.copy(id = ledger.insert(it)) }

        for (row in stored) assertEquals(row, ledger.findById(row.id))
        assertNull(ledger.findById(MISSING_ID))
    }

    @Test
    fun ledgerIsReadInTheOrderItWasRecordedNotByItsClock() = runBlocking {
        val otherProductId = products.insert(product("Té"))
        // El reloj del teléfono retrocedió: la segunda fila dice ser anterior a la primera, y con
        // otra fecha de negocio. El orden del libro es el de registro.
        val opening =
            openingBalance(productId, occurredAt = MUCH_LATER, businessDate = "2026-03-01")
        val bonus =
            movement(productId, "ADJUSTMENT", 2_000, 1_200, 2_400, 12_000, 1_200, 14_400)
                .copy(reason = "OTHER", note = "Bonificación del proveedor")
        val openingId = ledger.insert(opening)
        val otherId = ledger.insert(openingBalance(otherProductId))
        val bonusId = ledger.insert(bonus)
        val expected = listOf(opening.copy(id = openingId), bonus.copy(id = bonusId))

        assertEquals(expected, ledger.findByProduct(productId))
        val emissions = Emissions(this, ledger.observeByProduct(productId))
        try {
            emissions.awaitValue(expected)

            // Una fila nueva llega al final aunque su fecha sea la más vieja, y la lista vuelve a
            // emitir.
            val damaged =
                movement(productId, "ADJUSTMENT", -1_000, 1_200, -1_200, 11_000, 1_200, 13_200)
                    .copy(reason = "DAMAGED", businessDate = "2025-12-31")
            val damagedId = ledger.insert(damaged)
            emissions.awaitValue(expected + damaged.copy(id = damagedId))
        } finally {
            emissions.stop()
        }
        assertEquals(listOf(otherId), ledger.findByProduct(otherProductId).map { it.id })
    }

    @Test
    fun storedColumnsReplayTheCostTheProductWasLeftWith() = runBlocking {
        // Una caja de 24 unidades por 10.000: el promedio redondeado es 417, pero el valor exacto
        // queda guardado. Después se vende parte, y luego más de lo que hay, sin bloqueo.
        val bought = checked { entry(CostState.initial(INITIAL_COST), qty(24_000), Money(10_000)) }
        append(entryRow(bought, qty(24_000), Money(10_000)))
        val firstCost = checked { exitCost(bought, qty(5_000)) }
        val afterFirst = WeightedAverageCost.exit(bought, qty(5_000), firstCost)
        val firstSaleId = append(exitRow(afterFirst, qty(5_000), firstCost))
        val secondCost = checked { exitCost(afterFirst, qty(30_000)) }
        val afterSecond = WeightedAverageCost.exit(afterFirst, qty(30_000), secondCost)
        append(exitRow(afterSecond, qty(30_000), secondCost))

        // Lo que cada fila guardó, leído de la base y sin recalcular nada, reproduce el costo con
        // que quedó el producto, y el saldo es la suma de las filas, aquí negativo.
        val sold = requireNotNull(products.findById(productId))
        assertEquals(-11_000L, sold.onHandMilli)
        assertEquals(afterSecond, stateOf(sold))
        assertEquals(CostReplayResult.Replayed(afterSecond), replay())
        assertEquals(ledger.findByProduct(productId).sumOf { it.quantityMilli }, sold.onHandMilli)

        // Anular la primera venta deja el estado del libro efectivo, sin ella ni su compensatorio,
        // con el saldo de todas las filas.
        val first = requireNotNull(ledger.findById(firstSaleId))
        val voidState = CostState(qty(-6_000), bought.averageCost, Money.ZERO)
        append(
            movement(
                productId,
                "SALE_VOID",
                quantityMilli = -first.quantityMilli,
                unitCost = first.unitCost,
                totalCost = -first.totalCost,
                balanceAfterMilli = voidState.onHand.milli,
                averageCostAfter = voidState.averageCost.minor,
                stockValueAfter = voidState.stockValue.minor,
            ).copy(reversesMovementId = firstSaleId, saleLineId = first.saleLineId),
        )

        val voided = requireNotNull(products.findById(productId))
        assertEquals(voidState, stateOf(voided))
        assertEquals(CostReplayResult.Replayed(voidState), replay())
        assertEquals(ledger.findByProduct(productId).sumOf { it.quantityMilli }, voided.onHandMilli)
    }

    @Test
    fun failedAppendLeavesNeitherTheRowNorTheProductStock() = runBlocking {
        val openingId = append(openingBalance(productId))
        val before = requireNotNull(products.findById(productId))

        // El producto ya tomó su estado nuevo cuando la guarda rechaza la fila, que compensa una
        // que no existe: todo se deshace.
        guardFailure(GUARD) {
            database.withWriteTransaction {
                products.updateStockState(productId, 9_000, 1_200, 10_800, LATER)
                ledger.insert(
                    movement(
                        productId,
                        "ADJUSTMENT_VOID",
                        -1_000,
                        1_200,
                        -1_200,
                        9_000,
                        1_200,
                        10_800,
                    )
                        .copy(reversesMovementId = MISSING_ID),
                )
            }
        }

        assertEquals(before, products.findById(productId))
        assertEquals(listOf(openingId), ledger.findByProduct(productId).map { it.id })
    }

    @Test
    fun aRowIsCompensatedOnlyOnce() = runBlocking {
        val openingId = ledger.insert(openingBalance(productId))
        assertFalse(ledger.isCompensated(openingId))
        val reversal =
            movement(productId, "ADJUSTMENT_VOID", -10_000, 1_200, -12_000, 0, 1_200, 0)
                .copy(reversesMovementId = openingId)
        val reversalId = ledger.insert(reversal)

        assertTrue(ledger.isCompensated(openingId))
        assertFalse(ledger.isCompensated(reversalId))
        // Una segunda compensación de la misma fila la rechaza la guarda, antes que el índice
        // único, que sigue debajo.
        guardFailure(GUARD) { ledger.insert(reversal) }

        // Las filas que no compensan conviven: el índice único admite varios nulos.
        ledger.insert(openingBalance(productId))
        assertEquals(3, ledger.findByProduct(productId).size)
    }

    @Test
    fun rowNeedsAnExistingProductAndExistingOrigins() = runBlocking {
        // El producto y la fila que se compensa los pide la guarda, antes que la llave. Las
        // líneas de origen las pide la llave.
        guardFailure(GUARD) { ledger.insert(openingBalance(MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) {
            ledger.insert(
                movement(productId, "SALE", -1_000, 1_200, -1_200, -1_000, 1_200, 0)
                    .copy(saleLineId = MISSING_ID),
            )
        }
        constraintFailure(FOREIGN_KEY) {
            ledger.insert(
                openingBalance(productId).copy(type = "PURCHASE", purchaseLineId = MISSING_ID),
            )
        }
        guardFailure(GUARD) {
            ledger.insert(
                movement(productId, "ADJUSTMENT_VOID", -1_000, 1_200, -1_200, -1_000, 1_200, 0)
                    .copy(reversesMovementId = MISSING_ID),
            )
        }

        assertEquals(0L, count("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test
    fun productWithLedgerRowsCannotBeDeleted() = runBlocking {
        ledger.insert(openingBalance(productId))
        val withoutHistory = products.insert(product("Té"))

        // Un producto con historia se archiva: borrarlo dejaría su libro sin dueño.
        constraintFailure(FOREIGN_KEY) { products.delete(productId) }

        assertEquals(1, products.delete(withoutHistory))
        assertEquals(1, ledger.findByProduct(productId).size)
    }

    @Test
    fun saleAndPurchaseReadOnlyTheRowsOfTheirOwnLines() = runBlocking {
        val saleLine = insertSaleWithLine()
        val otherSaleLine = insertSaleWithLine()
        val purchaseLine = insertPurchaseWithLine()
        val sold = movement(productId, "SALE", -2_000, 3_000, -6_000, -2_000, 3_000, 0)

        val boughtId =
            ledger.insert(
                movement(productId, "PURCHASE", 12_000, 1_667, 20_000, 12_000, 1_667, 20_000)
                    .copy(purchaseLineId = purchaseLine.id),
            )
        val saleRowId = ledger.insert(sold.copy(saleLineId = saleLine.id))
        ledger.insert(sold.copy(saleLineId = otherSaleLine.id))
        // Un componente de un combo lleva la línea del combo.
        val componentRowId =
            ledger.insert(sold.copy(saleLineId = saleLine.id, saleLineComponentId = 7))
        val voidRowId =
            ledger.insert(
                sold.copy(
                    type = "SALE_VOID",
                    quantityMilli = 2_000,
                    totalCost = 6_000,
                    saleLineId = saleLine.id,
                    reversesMovementId = saleRowId,
                ),
            )

        assertEquals(
            listOf(saleRowId, componentRowId, voidRowId),
            ledger.findBySale(saleLine.saleId).map { it.id },
        )
        assertEquals(listOf(boughtId), ledger.findByPurchase(purchaseLine.purchaseId).map { it.id })
        assertEquals(emptyList<StockMovementEntity>(), ledger.findBySale(MISSING_ID))
        assertEquals(emptyList<StockMovementEntity>(), ledger.findByPurchase(MISSING_ID))
    }

    @Test
    fun moneyAndQuantitiesAreExactIntegersAtTheirLimitsAndKeepTheirSign() = runBlocking {
        // Topes del costo por unidad y del valor del stock, una salida del tamaño máximo de una
        // línea, y un saldo muy negativo: vender nunca se bloquea por falta de stock.
        val atLimits =
            movement(
                productId,
                "SALE",
                quantityMilli = -Limit.LINE_QUANTITY_MILLI.max,
                unitCost = Limit.UNIT_MONEY.max,
                totalCost = -Limit.DOCUMENT_TOTAL.max,
                balanceAfterMilli = -Limit.ON_HAND_MILLI.max,
                averageCostAfter = Limit.UNIT_MONEY.max,
                stockValueAfter = Limit.STOCK_VALUE.max,
            ).copy(saleLineId = insertSaleWithLine().id)

        val id = ledger.insert(atLimits)

        assertEquals(atLimits.copy(id = id), ledger.findById(id))
        val integers =
            count(
                "SELECT COUNT(*) FROM stock_movements WHERE id = $id " +
                    "AND typeof(quantity_milli) = 'integer' AND typeof(unit_cost) = 'integer' " +
                    "AND typeof(total_cost) = 'integer' " +
                    "AND typeof(average_cost_after) = 'integer' " +
                    "AND typeof(stock_value_after) = 'integer' " +
                    "AND typeof(balance_after_milli) = 'integer' AND quantity_milli < 0 " +
                    "AND total_cost < 0 AND balance_after_milli < 0",
            )
        assertEquals(1L, integers)
    }

    @Test
    fun insertingWithAnExistingIdFailsAndKeepsTheOriginal() = runBlocking {
        val original = openingBalance(productId)
        val id = ledger.insert(original)

        // Un INSERT OR REPLACE borraría la fila y escribiría otra en su lugar. La guarda rechaza
        // el id repetido antes que la llave primaria, también con REPLACE.
        guardFailure(GUARD) {
            ledger.insert(openingBalance(productId).copy(id = id, quantityMilli = 1))
        }

        assertEquals(listOf(original.copy(id = id)), ledger.findByProduct(productId))
    }

    @Test
    fun theDaoOnlyAppendsAndReads() {
        // Una función nueva que edite o borre el libro rompe esta prueba a propósito: el saldo, el
        // costo y el valor de cada producto son la suma de sus filas.
        val functions = StockMovementDao::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()

        val expected =
            setOf(
                "observeByProduct",
                "findById",
                "findByProduct",
                "findBySale",
                "findByPurchase",
                "isCompensated",
                "insert",
            )
        assertEquals(expected, functions)
    }

    /** Una compra de [quantity] por [total] que deja el estado [after]. */
    private suspend fun entryRow(after: CostState, quantity: QuantityMilli, total: Money) =
        movement(
            productId,
            "PURCHASE",
            quantityMilli = quantity.milli,
            unitCost = after.averageCost.minor,
            totalCost = total.minor,
            balanceAfterMilli = after.onHand.milli,
            averageCostAfter = after.averageCost.minor,
            stockValueAfter = after.stockValue.minor,
        ).copy(purchaseLineId = insertPurchaseWithLine().id)

    /** Una venta de [quantity] que se lleva [cost] y deja el estado [after]. */
    private suspend fun exitRow(after: CostState, quantity: QuantityMilli, cost: Money) = movement(
        productId,
        "SALE",
        quantityMilli = -quantity.milli,
        unitCost = after.averageCost.minor,
        totalCost = -cost.minor,
        balanceAfterMilli = after.onHand.milli,
        averageCostAfter = after.averageCost.minor,
        stockValueAfter = after.stockValue.minor,
    ).copy(saleLineId = insertSaleWithLine().id)

    /** Reproduce el costo del producto con sus filas tal como salen de la base. */
    private suspend fun replay(): CostReplayResult = WeightedAverageCost.replayCostState(
        INITIAL_COST,
        ledger.findByProduct(productId).map { row ->
            CostMovement(
                id = row.id,
                type = StockMovementType.valueOf(row.type),
                quantity = QuantityMilli(row.quantityMilli),
                unitCost = Money(row.unitCost),
                totalCost = Money(row.totalCost),
                reversesMovementId = row.reversesMovementId,
            )
        },
    )

    private fun stateOf(product: ProductEntity): CostState = CostState(
        QuantityMilli(product.onHandMilli),
        Money(product.averageCost),
        Money(product.stockValue),
    )

    private fun qty(milli: Long) = QuantityMilli(milli)

    /** El valor de una cuenta del costo que con estos datos siempre cabe. */
    private fun <T> checked(step: WeightedAverageCost.() -> Checked<T>): T =
        when (val result = WeightedAverageCost.step()) {
            is Checked.Value -> result.value
            Checked.Overflow, is Checked.AboveLimit -> error("La cuenta debía caber: $result")
        }

    private companion object {
        const val MISSING_ID = 999L
        const val GUARD = "guard:stock_movements"

        /** El costo inicial del producto de las pruebas. */
        val INITIAL_COST = Money(1_200)
    }
}
