package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anular una venta: compensa cada fila de venta una sola vez, recalcula el costo reproduciendo el
 * libro, y cualquier rechazo o falla no deja nada. Los valores esperados están escritos a mano.
 */
class VoidSaleUseCaseTest {
    private val world = SaleWorld().apply { addMethod(method()) }

    private suspend fun sell(vararg lines: Pair<Long, Long>, date: BusinessDate? = null): Long {
        val request = SaleRequest(
            lines.map { (productId, milli) -> SaleLineRequest(productId, QuantityMilli(milli)) },
            SaleWorld.CASH,
            date = date,
        )
        return (world.registerSale()(request) as RegisterSaleResult.Registered).saleId
    }

    private suspend fun void(
        saleId: Long,
        reason: VoidReason = VoidReason.ENTRY_ERROR,
        note: String? = null,
    ): VoidSaleResult = world.voidSale()(saleId, reason, note)

    /**
     * Una entrada de [milli] milésimas por [total], escrita en el libro con el costo que calcula la
     * regla del costo promedio, como lo haría su caso de uso: un inventario inicial o una compra.
     */
    private suspend fun enter(
        productId: Long,
        milli: Long,
        total: Long,
        type: StockMovementType = StockMovementType.PURCHASE,
        target: SaleWorld = world,
    ) {
        target.transactions.write {
            val product = checkNotNull(target.products.findById(productId))
            val before = CostState(product.onHand, product.averageCost, product.stockValue)
            val after = WeightedAverageCost.entry(before, QuantityMilli(milli), Money(total))
                .valueOrNull()
            checkNotNull(after)
            target.ledger.append(
                NewStockMovement(
                    movement = StockMovementDraft(
                        StockKey(productId, SaleWorld.MAIN_LOCATION, null),
                        type,
                        QuantityMilli(milli),
                        reversesMovementId = null,
                    ),
                    reason = null,
                    unitCost = after.averageCost,
                    totalCost = Money(total),
                    balanceAfter = after.onHand,
                    averageCostAfter = after.averageCost,
                    stockValueAfter = after.stockValue,
                    source = if (type == StockMovementType.PURCHASE) {
                        StockMovementSource(purchaseLineId = 1)
                    } else {
                        StockMovementSource.NONE
                    },
                    note = null,
                    occurredAt = target.clock.now(),
                    businessDate = TODAY,
                    createdAt = target.clock.now(),
                ),
            )
            TxResult.Commit(Unit)
        }
    }

    /** Un producto con [milli] milésimas de inventario inicial que valen [total]. */
    private suspend fun stocked(id: Long, milli: Long, total: Long, salePrice: Long = 500) {
        world.addProduct(product(id, salePrice = salePrice))
        enter(id, milli, total, StockMovementType.OPENING_BALANCE)
    }

    private fun movements(): List<StockMovementRecord> =
        world.tables.movements.values.sortedBy { it.id }

    private companion object {
        /** Hoy en la zona del negocio, con el reloj de las pruebas. */
        val TODAY = BusinessDate(LocalDate(2026, 11, 2))
    }

    @Test
    fun theVoidExampleReturnsTheUnitsAtTheirOriginalCost() = runTest {
        // La tabla de la anulación de una venta: compra 10 a 1000, venta de 4, compra 6 a 1400.
        world.addProduct(product(1, salePrice = 2_000))
        enter(1, 10_000, total = 10_000)
        val saleId = sell(1L to 4_000L)
        enter(1, 6_000, total = 8_400)
        assertEquals(Money(14_400), world.tables.products.getValue(1).stockValue)

        assertEquals(VoidSaleResult.Voided, void(saleId))

        val after = world.tables.products.getValue(1)
        assertEquals(QuantityMilli(16_000), after.onHand)
        assertEquals(Money(18_400), after.stockValue)
        assertEquals(Money(1_150), after.averageCost)
        val compensation = movements().last()
        assertEquals(StockMovementType.SALE_VOID, compensation.movement.type)
        assertEquals(QuantityMilli(4_000), compensation.movement.quantity)
        assertEquals(movements()[1].id, compensation.movement.reversesMovementId)
        assertEquals(Money(1_000), compensation.unitCost)
        assertEquals(Money(4_000), compensation.totalCost)
        assertEquals(QuantityMilli(16_000), compensation.balanceAfter)
        assertEquals(Money(1_150), compensation.averageCostAfter)
        assertEquals(Money(18_400), compensation.stockValueAfter)
        assertEquals(movements()[1].source, compensation.source)
        val sale = world.tables.sales.getValue(saleId)
        assertEquals(DocumentStatus.VOIDED, sale.status)
        assertEquals(VoidReason.ENTRY_ERROR, sale.voiding?.reason)
        // La venta, sus líneas y su pago siguen ahí, sin cambios.
        assertEquals(1, world.tables.lines.size)
        assertEquals(1, world.tables.payments.size)
    }

    @Test
    fun aSaleIsCompensatedOnlyOnce() = runTest {
        stocked(1, 5_000, total = 500)
        val saleId = sell(1L to 2_000L)
        assertEquals(VoidSaleResult.Voided, void(saleId))
        val afterFirst = world.tables

        assertEquals(VoidSaleResult.AlreadyVoided, void(saleId))

        assertEquals(afterFirst, world.tables)
        assertEquals(1, movements().count { it.movement.type == StockMovementType.SALE_VOID })
    }

    @Test
    fun eachSaleRowGetsItsOwnCompensation() = runTest {
        stocked(1, 3_000, total = 1_000)
        stocked(2, 1_000, total = 700, salePrice = 900)
        val saleId = sell(1L to 1_000L, 2L to 2_000L, 1L to 2_000L)

        assertEquals(VoidSaleResult.Voided, void(saleId))

        val sales = movements().filter { it.movement.type == StockMovementType.SALE }
        val voids = movements().filter { it.movement.type == StockMovementType.SALE_VOID }
        assertEquals(
            sales.map {
                it.id
            }.toSet(),
            voids.map { it.movement.reversesMovementId }.toSet(),
        )
        assertEquals(
            sales.map {
                -it.movement.quantity
            },
            voids.sortedBy { it.movement.reversesMovementId }.map { it.movement.quantity },
        )
        assertEquals(
            sales.map {
                -it.totalCost
            },
            voids.sortedBy { it.movement.reversesMovementId }.map { it.totalCost },
        )
        // Todo vuelve a como estaba antes de la venta.
        val soap = world.tables.products.getValue(1)
        assertEquals(QuantityMilli(3_000), soap.onHand)
        assertEquals(Money(1_000), soap.stockValue)
        val other = world.tables.products.getValue(2)
        assertEquals(QuantityMilli(1_000), other.onHand)
        assertEquals(Money(700), other.averageCost)
        assertEquals(Money(700), other.stockValue)
        // La cadena de saldos de cada fila sigue cuadrando.
        assertEquals(
            listOf(1_000L, 3_000L),
            voids.filter {
                it.movement.key.productId == 1L
            }.map { it.balanceAfter.milli },
        )
    }

    @Test
    fun voidsInAnyOrderLeaveTheSameCost() = runTest {
        suspend fun scenario(order: List<Int>): Triple<QuantityMilli, Money, Money> {
            val local = SaleWorld().apply { addMethod(method()) }
            local.addProduct(product(1, salePrice = 2_000, initialCost = 0))
            val sold = mutableListOf<Long>()
            for (step in 0 until 3) {
                enter(1, 3_000, total = 3_000L + step * 1_000L, target = local)
                val request =
                    SaleRequest(listOf(SaleLineRequest(1, QuantityMilli(2_000))), SaleWorld.CASH)
                sold += (local.registerSale()(request) as RegisterSaleResult.Registered).saleId
            }
            for (index in order) {
                assertEquals(
                    VoidSaleResult.Voided,
                    local.voidSale()(sold[index], VoidReason.OTHER, null),
                )
            }
            val after = local.tables.products.getValue(1)
            return Triple(after.onHand, after.averageCost, after.stockValue)
        }

        val forward = scenario(listOf(0, 1, 2))
        assertEquals(forward, scenario(listOf(2, 1, 0)))
        assertEquals(forward, scenario(listOf(1, 0, 2)))
        // Sin ventas quedan las tres compras: 9 unidades que costaron 12000.
        assertEquals(Triple(QuantityMilli(9_000), Money(1_333), Money(12_000)), forward)
    }

    @Test
    fun aServiceLineVoidsWithoutMovements() = runTest {
        world.addProduct(
            product(
                1,
                salePrice = 5_000,
                stockMode = ProductStockMode.UNTRACKED,
                fixedUnitCost = 1_000,
            ),
        )
        val saleId = sell(1L to 1_000L)

        assertEquals(
            VoidSaleResult.Voided,
            void(saleId, VoidReason.CUSTOMER_RETURN, "Invented note"),
        )

        assertTrue(movements().isEmpty())
        val voiding = world.tables.sales.getValue(saleId).voiding
        assertEquals(VoidReason.CUSTOMER_RETURN, voiding?.reason)
        assertEquals("Invented note", voiding?.note)
        assertEquals(Instant.fromEpochMilliseconds(SaleWorld.NOW_MILLIS), voiding?.voidedAt)
    }

    @Test
    fun anArchivedProductStillGetsItsStockBack() = runTest {
        stocked(1, 2_000, total = 200)
        val saleId = sell(1L to 1_000L)
        world.arrange { tables ->
            val archived = tables.products.getValue(1).copy(archivedAt = SaleWorld.EPOCH)
            tables.copy(products = tables.products + (1L to archived))
        }

        assertEquals(VoidSaleResult.Voided, void(saleId))

        assertEquals(QuantityMilli(2_000), world.tables.products.getValue(1).onHand)
    }

    @Test
    fun theCompensationIsDatedOnTheVoidAndTheSaleKeepsItsDate() = runTest {
        stocked(1, 2_000, total = 200)
        val past = BusinessDate(LocalDate(2026, 10, 30))
        val saleId = sell(1L to 1_000L, date = past)

        void(saleId, note = "   ")

        val compensation = movements().last()
        assertEquals(TODAY, compensation.businessDate)
        assertEquals(Instant.fromEpochMilliseconds(SaleWorld.NOW_MILLIS), compensation.occurredAt)
        val sale = world.tables.sales.getValue(saleId)
        assertEquals(past, sale.content.businessDate)
        // Una nota en blanco no se guarda.
        assertEquals(null, sale.voiding?.note)
    }

    @Test
    fun aRowAlreadyCompensatedIsNotCompensatedAgain() = runTest {
        stocked(1, 5_000, total = 500)
        val saleId = sell(1L to 1_000L, 1L to 2_000L)
        val first = movements().first { it.movement.type == StockMovementType.SALE }
        // Un libro dañado: la venta sigue activa, pero su primera fila ya volvió al stock.
        world.transactions.write {
            val product = checkNotNull(world.products.findById(1))
            world.ledger.append(
                NewStockMovement(
                    movement = StockMovementDraft(
                        first.movement.key,
                        StockMovementType.SALE_VOID,
                        -first.movement.quantity,
                        first.id,
                    ),
                    reason = null,
                    unitCost = first.unitCost,
                    totalCost = -first.totalCost,
                    balanceAfter = product.onHand - first.movement.quantity,
                    averageCostAfter = product.averageCost,
                    stockValueAfter = product.stockValue - first.totalCost,
                    source = first.source,
                    note = null,
                    occurredAt = world.clock.now(),
                    businessDate = TODAY,
                    createdAt = world.clock.now(),
                ),
            )
            TxResult.Commit(Unit)
        }

        assertEquals(VoidSaleResult.Voided, void(saleId))

        assertEquals(2, movements().count { it.movement.type == StockMovementType.SALE_VOID })
        assertEquals(QuantityMilli(5_000), world.tables.products.getValue(1).onHand)
    }

    @Test
    fun aDamagedLedgerRejectsTheVoidAndLeavesNothing() = runTest {
        stocked(1, 5_000, total = 500)
        val saleId = sell(1L to 1_000L)
        // Una compra que resta stock no puede salir de la app: el libro no se reproduce.
        world.arrange { tables ->
            val sale = tables.movements.values.single { it.movement.type == StockMovementType.SALE }
            val broken = sale.copy(
                movement = sale.movement.copy(id = 99, type = StockMovementType.PURCHASE),
                totalCost = Money(-100),
                source = StockMovementSource(purchaseLineId = 1),
            )
            tables.copy(movements = tables.movements + (99L to broken))
        }
        val before = world.tables

        assertEquals(VoidSaleResult.LedgerDamaged, void(saleId))

        assertEquals(before, world.tables)
    }

    @Test
    fun eachRejectionLeavesTheSaleActive() = runTest {
        stocked(1, 5_000, total = 500)
        val saleId = sell(1L to 1_000L)
        val before = world.tables

        assertEquals(VoidSaleResult.SaleNotFound, void(99))
        assertEquals(VoidSaleResult.ReasonNotAllowed, void(saleId, VoidReason.SUPPLIER_RETURN))
        assertEquals(VoidSaleResult.NoteTooLong, void(saleId, note = "n".repeat(201)))
        world.arrange { it.copy(salesWithActiveReturns = setOf(saleId)) }
        assertEquals(VoidSaleResult.HasActiveReturns, void(saleId))
        world.arrange { it.copy(salesWithActiveReturns = emptySet()) }

        assertEquals(before, world.tables)
        assertEquals(VoidSaleResult.Voided, void(saleId, note = "n".repeat(200)))
    }

    @Test
    fun anUnknownZoneRejectsTheVoid() = runTest {
        stocked(1, 5_000, total = 500)
        val saleId = sell(1L to 1_000L)
        world.settings.settings.value = world.settings.settings.value.let {
            it.copy(regional = it.regional.copy(businessTimeZoneId = "Invented/Zone"))
        }

        assertEquals(VoidSaleResult.UnknownTimeZone("Invented/Zone"), void(saleId))
        assertEquals(DocumentStatus.ACTIVE, world.tables.sales.getValue(saleId).status)
    }

    @Test
    fun aFailureAtAnyWriteLeavesTheSaleActive() = runTest {
        stocked(1, 5_000, total = 500)
        stocked(2, 5_000, total = 500)
        val saleId = sell(1L to 1_000L, 2L to 1_000L)
        val before = world.tables
        // La venta anulada y, por cada fila, su id, la fila y el producto.
        val writes = 7
        for (failAt in 1..writes) {
            world.failAtWrite = failAt
            val failed = runCatching { void(saleId) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedSaleFailure)
            assertEquals(before, world.tables)
        }
        world.failAtWrite = null
        assertEquals(VoidSaleResult.Voided, void(saleId))
    }

    @Test
    fun aStorageFailureComesBackAsAResult() = runTest {
        stocked(1, 5_000, total = 500)
        val saleId = sell(1L to 1_000L)
        world.failAtWrite = 1
        world.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(VoidSaleResult.StorageFailed(StorageFailure.DISK_FULL), void(saleId))
        assertEquals(DocumentStatus.ACTIVE, world.tables.sales.getValue(saleId).status)
    }
}
