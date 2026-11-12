package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.CostReplayResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anular una compra: compensa cada fila de compra una sola vez, recalcula el costo reproduciendo el
 * libro, nunca toca las ventas ya hechas, y cualquier rechazo o falla no deja nada. Los escenarios
 * A, B y C son los de la regla de anular una compra, con sus valores escritos a mano.
 */
class VoidPurchaseUseCaseTest {
    private val world = SaleWorld()

    private suspend fun void(
        purchaseId: Long,
        reason: VoidReason = VoidReason.ENTRY_ERROR,
        note: String? = null,
    ): VoidPurchaseResult = world.voidPurchase()(purchaseId, reason, note)

    @Test
    fun `scenario A, an entry error caught in time, goes back to the exact cost`() = runTest {
        world.addProduct(product(1))
        world.open(1, 10_000, total = 10_000)
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 50_000))
        // 60000 × 1000 / 11000 = 5454,5: sube a 5455.
        assertEquals(cost(11_000, 5_455, 60_000), world.costOf(1))

        assertEquals(VoidPurchaseResult.Voided, void(purchaseId))

        // Una resta daría 1001; la reproducción, 1000 exacto.
        assertEquals(cost(10_000, 1_000, 10_000), world.costOf(1))
        val compensation = world.movements().last()
        assertEquals(StockMovementType.PURCHASE_VOID, compensation.movement.type)
        assertEquals(QuantityMilli(-1_000), compensation.movement.quantity)
        assertEquals(Money(50_000), compensation.unitCost)
        assertEquals(Money(-50_000), compensation.totalCost)
        assertEquals(Money(1_000), compensation.averageCostAfter)
        assertEquals(Money(10_000), compensation.stockValueAfter)
        assertEquals(QuantityMilli(10_000), compensation.balanceAfter)
        val original = world.movements().single { it.movement.type == StockMovementType.PURCHASE }
        assertEquals(original.id, compensation.movement.reversesMovementId)
        assertEquals(original.source, compensation.source)
        assertEquals(original.movement.key, compensation.movement.key)
    }

    @Test
    fun `scenario B, part already sold, keeps the sale cost and leaves no value`() = runTest {
        world.addProduct(product(1))
        world.open(1, 10_000, total = 10_000)
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 50_000))
        val saleId = world.sell(1, 9_000)
        val saleLine = world.tables.lines.values.single { it.saleId == saleId }
        assertEquals(Money(5_455), saleLine.content.unitCost)
        assertEquals(Money(49_091), saleLine.content.lineCost)
        assertEquals(cost(2_000, 5_455, 10_909), world.costOf(1))

        assertEquals(VoidPurchaseResult.Voided, void(purchaseId))

        // Reproduce el inventario inicial y la venta: la venta se llevó más costo del que quedó.
        assertEquals(cost(1_000, 1_000, 0), world.costOf(1))
        assertEquals(saleLine, world.tables.lines.values.single { it.saleId == saleId })
    }

    @Test
    fun `scenario C, stock left negative, falls back to the initial cost`() = runTest {
        for ((id, initial) in listOf(1L to 0L, 2L to 900L)) {
            world.addProduct(product(id, initialCost = initial))
            val purchaseId = world.buy(atUnitCost(id, 10_000, cost = 1_000))
            world.sell(id, 10_000)

            assertEquals(VoidPurchaseResult.Voided, void(purchaseId))

            assertEquals(cost(-10_000, initial, 0), world.costOf(id))
        }
    }

    @Test
    fun `every line row is compensated and the purchase is voided with its reason`() = runTest {
        world.addProduct(product(1))
        world.addProduct(product(2))
        val purchaseId = world.buy(
            atUnitCost(1, 2_000, cost = 100),
            atUnitCost(2, 3_000, cost = 200),
            atUnitCost(1, 1_000, cost = 400),
        )

        assertEquals(
            VoidPurchaseResult.Voided,
            void(purchaseId, VoidReason.SUPPLIER_RETURN, "Invented note"),
        )

        val purchase = world.tables.purchases.getValue(purchaseId)
        assertEquals(DocumentStatus.VOIDED, purchase.status)
        assertEquals(Voiding(NOW, VoidReason.SUPPLIER_RETURN, "Invented note"), purchase.voiding)
        val types = world.movements().map { it.movement.type }
        assertEquals(3, types.count { it == StockMovementType.PURCHASE_VOID })
        for (id in listOf(1L, 2L)) assertEquals(cost(0, 0, 0), world.costOf(id))
        assertEquals(1, world.tables.purchaseLines.values.count { it.content.productId == 2L })
    }

    @Test
    fun `the compensation is dated on the void and the purchase keeps its date`() = runTest {
        world.addProduct(product(1))
        val past = BusinessDate(LocalDate(2026, 10, 30))
        val purchaseId = world.buy(atUnitCost(1, 2_000, cost = 100), date = past)

        void(purchaseId, note = "   ")

        val compensation = world.movements().last()
        assertEquals(TODAY, compensation.businessDate)
        assertEquals(NOW, compensation.occurredAt)
        val purchase = world.tables.purchases.getValue(purchaseId)
        assertEquals(past, purchase.content.businessDate)
        // Una nota en blanco no se guarda.
        assertEquals(null, purchase.voiding?.note)
    }

    @Test
    fun `the cost after voiding equals replaying the effective ledger`() = runTest {
        world.addProduct(product(1, initialCost = 300))
        world.open(1, 5_000, total = 4_000)
        val first = world.buy(atUnitCost(1, 3_000, cost = 1_700))
        world.sell(1, 6_000)
        val second = world.buy(forTotal(1, 7_000, total = 9_100))
        world.buy(atUnitCost(1, 2_000, cost = 1_100))

        void(second)
        void(first)

        val book = world.movements().filter { it.movement.key.productId == 1L }.map {
            CostMovement(
                it.id,
                it.movement.type,
                it.movement.quantity,
                it.unitCost,
                it.totalCost,
                it.movement.reversesMovementId,
            )
        }
        val replayed = WeightedAverageCost.replayCostState(Money(300), book)
        assertEquals(CostReplayResult.Replayed(world.costOf(1)), replayed)
        val onHand = book.fold(QuantityMilli.ZERO) { sum, row -> sum + row.quantity }
        assertEquals(onHand, world.costOf(1).onHand)
    }

    @Test
    fun `a purchase is voided only once and only if it exists`() = runTest {
        world.addProduct(product(1))
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 100))

        assertEquals(VoidPurchaseResult.PurchaseNotFound, void(99))
        assertEquals(VoidPurchaseResult.Voided, void(purchaseId))
        val after = world.tables

        assertEquals(VoidPurchaseResult.AlreadyVoided, void(purchaseId))
        assertEquals(after, world.tables)
    }

    @Test
    fun `a reason that does not fit a purchase or a long note is rejected`() = runTest {
        world.addProduct(product(1))
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 100))

        assertEquals(
            VoidPurchaseResult.ReasonNotAllowed,
            void(purchaseId, VoidReason.CUSTOMER_RETURN),
        )
        assertEquals(VoidPurchaseResult.NoteTooLong, void(purchaseId, note = "n".repeat(201)))
        assertEquals(DocumentStatus.ACTIVE, world.tables.purchases.getValue(purchaseId).status)
        assertEquals(VoidPurchaseResult.Voided, void(purchaseId, note = "n".repeat(200)))
    }

    @Test
    fun `an unknown zone rejects without writing`() = runTest {
        val lost = SaleWorld(zoneId = "Invented/Zone")
        lost.addProduct(product(1))
        val purchaseId = world.run {
            addProduct(product(1))
            buy(atUnitCost(1, 1_000, cost = 100))
        }
        lost.arrange { world.tables }

        val result = lost.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidPurchaseResult.UnknownTimeZone("Invented/Zone"), result)
        assertEquals(DocumentStatus.ACTIVE, lost.tables.purchases.getValue(purchaseId).status)
    }

    @Test
    fun `an archived product does not block the void`() = runTest {
        world.addProduct(product(1))
        val purchaseId = world.buy(atUnitCost(1, 2_000, cost = 100))
        world.arrange {
            val archived = it.products.getValue(1).copy(archivedAt = SaleWorld.EPOCH)
            it.copy(products = it.products + (1L to archived))
        }

        assertEquals(VoidPurchaseResult.Voided, void(purchaseId))
        assertEquals(QuantityMilli.ZERO, world.costOf(1).onHand)
    }

    @Test
    fun `a row already compensated is not compensated again`() = runTest {
        world.addProduct(product(1))
        val purchaseId = world.buy(
            atUnitCost(1, 1_000, cost = 100),
            atUnitCost(1, 2_000, cost = 100),
        )
        val first = world.movements().first()
        // Un libro dañado: la compra sigue activa, pero su primera fila ya salió del stock.
        world.transactions.write {
            world.ledger.append(
                NewStockMovement(
                    movement = StockMovementDraft(
                        first.movement.key,
                        StockMovementType.PURCHASE_VOID,
                        -first.movement.quantity,
                        reversesMovementId = first.id,
                    ),
                    reason = null,
                    unitCost = first.unitCost,
                    totalCost = -first.totalCost,
                    balanceAfter = QuantityMilli(2_000),
                    averageCostAfter = Money(100),
                    stockValueAfter = Money(200),
                    source = first.source,
                    note = null,
                    occurredAt = NOW,
                    businessDate = TODAY,
                    createdAt = NOW,
                ),
            )
            TxResult.Commit(Unit)
        }

        assertEquals(VoidPurchaseResult.Voided, void(purchaseId))

        assertEquals(1, world.movements().count { it.movement.reversesMovementId == first.id })
        assertEquals(QuantityMilli.ZERO, world.costOf(1).onHand)
    }

    @Test
    fun `a ledger that cannot be replayed rolls the void back`() = runTest {
        world.addProduct(product(1))
        world.open(1, 1_000, total = 100)
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 100))
        // Una fila imposible: un inventario inicial con cantidad negativa, que la app nunca escribe.
        world.arrange { tables ->
            val row = tables.movements.values.minBy { it.id }
            val broken = row.copy(movement = row.movement.copy(quantity = QuantityMilli(-1_000)))
            tables.copy(movements = tables.movements + (row.id to broken))
        }
        val before = world.tables

        assertEquals(VoidPurchaseResult.LedgerDamaged, void(purchaseId))
        assertEquals(before, world.tables)
    }

    @Test
    fun `a failure at any write leaves the purchase active`() = runTest {
        world.addProduct(product(1))
        world.addProduct(product(2))
        val purchaseId = world.buy(
            atUnitCost(1, 1_000, cost = 100),
            atUnitCost(2, 1_000, cost = 50),
        )
        val before = world.tables
        // La compra anulada y, por cada fila, su id, la fila y el producto.
        val writes = 7
        for (failAt in 1..writes) {
            world.failAtWrite = failAt
            val failed = runCatching { void(purchaseId) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedSaleFailure)
            assertEquals(before, world.tables)
        }
        world.failAtWrite = null
        assertEquals(VoidPurchaseResult.Voided, void(purchaseId))
    }

    @Test
    fun `a storage failure comes back as a result`() = runTest {
        world.addProduct(product(1))
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 100))
        world.failAtWrite = 1
        world.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(VoidPurchaseResult.StorageFailed(StorageFailure.DISK_FULL), void(purchaseId))
        assertEquals(DocumentStatus.ACTIVE, world.tables.purchases.getValue(purchaseId).status)
    }
}
