package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockShrinkage
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockFixture.Companion.PRODUCT_ID
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anular un ajuste o un inventario inicial: un compensatorio, nunca una edición ni una merma. */
class VoidStockAdjustmentUseCaseTest {
    private val fixture = StockFixture()

    private fun expired(units: Long) = StockAdjustmentRequest.Change(
        PRODUCT_ID,
        StockAdjustmentReason.EXPIRED,
        QuantityMilli(units),
    )

    @Test
    fun `an opening balance of 100 that should be 10 is voided without shrinkage`() = runTest {
        val opening = requireNotNull(fixture.seedStock(units = 100_000L, unitCost = 1_000L))
        val today = ReportPeriod.Day(fixture.movementsOf(PRODUCT_ID).first().businessDate)

        val result = fixture.void(opening)

        val compensation = fixture.movementsOf(PRODUCT_ID).last()
        assertEquals(VoidAdjustmentResult.Voided(compensation.id), result)
        assertEquals(StockMovementType.ADJUSTMENT_VOID, compensation.movement.type)
        assertEquals(QuantityMilli(-100_000L), compensation.movement.quantity)
        assertEquals(Money(-100_000L), compensation.totalCost)
        assertEquals(Money(1_000L), compensation.unitCost)
        assertEquals(opening, compensation.movement.reversesMovementId)
        assertNull(compensation.reason)
        val product = fixture.product(PRODUCT_ID)
        assertEquals(QuantityMilli.ZERO, product.onHand)
        // El libro efectivo queda vacío: el costo vuelve al inicial y el stock no vale nada.
        assertEquals(Money(1_000L), product.averageCost)
        assertEquals(Money.ZERO, product.stockValue)
        assertEquals(Money.ZERO, StockShrinkage.net(fixture.movementsOf(PRODUCT_ID), today))
    }

    @Test
    fun `voiding last week's expired goods stops counting them that week`() = runTest {
        fixture.seedStock(units = 11_000L, unitCost = 1_355L)
        fixture.adjust(expired(3_000L))
        val adjustment = fixture.movementsOf(PRODUCT_ID).last()
        val lastWeek = ReportPeriod.Week(adjustment.businessDate)
        assertEquals(Money(4_065L), StockShrinkage.net(fixture.movementsOf(PRODUCT_ID), lastWeek))

        fixture.now = CatalogFixture.NOW + 8.days
        fixture.void(adjustment.id)

        val movements = fixture.movementsOf(PRODUCT_ID)
        val compensation = movements.last()
        assertEquals(adjustment.businessDate.plusDays(8), compensation.businessDate)
        assertEquals(Money.ZERO, StockShrinkage.net(movements, lastWeek))
        val thisWeek = ReportPeriod.Week(compensation.businessDate)
        assertEquals(Money.ZERO, StockShrinkage.net(movements, thisWeek))
        val product = fixture.product(PRODUCT_ID)
        assertEquals(QuantityMilli(11_000L), product.onHand)
        assertEquals(Money(14_905L), product.stockValue)
        assertEquals(Money(1_355L), product.averageCost)
    }

    @Test
    fun `voiding a count after a later exit keeps the cost the replay gives`() = runTest {
        fixture.seedStock(units = 10_000L, unitCost = 1_200L)
        fixture.adjust(StockAdjustmentRequest.Count(PRODUCT_ID, QuantityMilli(15_000L)))
        val count = fixture.movementsOf(PRODUCT_ID).last()
        fixture.adjust(expired(4_000L))

        fixture.void(count.id)

        val product = fixture.product(PRODUCT_ID)
        assertEquals(QuantityMilli(6_000L), product.onHand)
        assertEquals(Money(7_200L), product.stockValue)
        assertEquals(Money(1_200L), product.averageCost)
        assertEquals(product.stockValue, fixture.movementsOf(PRODUCT_ID).last().stockValueAfter)
    }

    @Test
    fun `a row is voided only once`() = runTest {
        val opening = requireNotNull(fixture.seedStock(units = 5_000L, unitCost = 100L))
        fixture.void(opening)
        val before = fixture.catalog.tables

        assertEquals(VoidAdjustmentResult.AlreadyVoided, fixture.void(opening))

        assertEquals(before, fixture.catalog.tables)
    }

    @Test
    fun `a compensation, a sale or a missing row are not voided here`() = runTest {
        val opening = requireNotNull(fixture.seedStock(units = 5_000L, unitCost = 100L))
        fixture.void(opening)
        val compensation = fixture.movementsOf(PRODUCT_ID).last().id
        seedRow(id = 50L, StockMovementType.PURCHASE, 1_000L)

        assertEquals(VoidAdjustmentResult.NotVoidable, fixture.void(compensation))
        assertEquals(VoidAdjustmentResult.NotVoidable, fixture.void(50L))
        assertEquals(VoidAdjustmentResult.NotFound, fixture.void(99L))
    }

    @Test
    fun `a damaged ledger is not replayed over`() = runTest {
        val opening = requireNotNull(fixture.seedStock(units = 5_000L, unitCost = 100L))
        // Una compra que resta no puede salir de la app: llegaría de una copia dañada.
        seedRow(id = 50L, StockMovementType.PURCHASE, -1_000L)
        val before = fixture.catalog.tables

        assertEquals(VoidAdjustmentResult.LedgerDamaged, fixture.void(opening))

        assertEquals(before, fixture.catalog.tables)
    }

    @Test
    fun `an unknown zone writes nothing`() = runTest {
        val unknown = StockFixture(zoneId = "Lugar/Inventado")
        unknown.catalog.seedProduct(id = PRODUCT_ID, name = "Arroz")
        unknown.catalog.database.seed { tables ->
            tables.copy(movements = mapOf(1L to row(1L, StockMovementType.OPENING_BALANCE, 1_000L)))
        }

        assertEquals(VoidAdjustmentResult.UnknownTimeZone, unknown.void(1L))
    }

    @Test
    fun `a void that drops below the minimum creates the pending alert`() = runTest {
        fixture.seedStock(units = 4_000L, unitCost = 100L, minStock = 5_000L)
        fixture.adjust(StockAdjustmentRequest.Count(PRODUCT_ID, QuantityMilli(8_000L)))
        val count = fixture.movementsOf(PRODUCT_ID).last()
        assertTrue(fixture.alertsOf(PRODUCT_ID).none { it.isActive })

        fixture.void(count.id)

        val alert = fixture.alertsOf(PRODUCT_ID).single { it.isActive }
        assertEquals(LowStockNotificationStatus.PENDING, alert.notificationStatus)
        assertEquals(QuantityMilli(4_000L), alert.onHandAtTrigger)
    }

    @Test
    fun `an archived product still gets its stock back`() = runTest {
        fixture.seedStock(units = 5_000L, unitCost = 100L)
        fixture.adjust(expired(1_000L))
        val adjustment = fixture.movementsOf(PRODUCT_ID).last()
        fixture.catalog.database.seed { tables ->
            val product = requireNotNull(tables.products[PRODUCT_ID])
            tables.copy(
                products = tables.products +
                    (PRODUCT_ID to product.copy(archivedAt = CatalogFixture.EARLIER)),
            )
        }

        assertTrue(fixture.void(adjustment.id) is VoidAdjustmentResult.Voided)

        assertEquals(QuantityMilli(5_000L), fixture.product(PRODUCT_ID).onHand)
        assertTrue(fixture.alertsOf(PRODUCT_ID).isEmpty())
    }

    @Test
    fun `each write that fails leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = {
                seedProduct(
                    id = PRODUCT_ID,
                    name = "Arroz",
                    onHand = QuantityMilli(1_000L),
                    minStock = QuantityMilli(500L),
                )
                database.seed { tables ->
                    tables.copy(
                        movements = mapOf(1L to row(1L, StockMovementType.OPENING_BALANCE, 1_000L)),
                        lastIds = tables.lastIds + ("stock_movements" to 1L),
                    )
                }
            },
            run = { StockFixture(this).void(1L) },
        )
    }

    @Test
    fun `a storage failure comes back as a result`() = runTest {
        val opening = requireNotNull(fixture.seedStock(units = 5_000L, unitCost = 100L))
        fixture.catalog.database.failAtWrite = 1
        fixture.catalog.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        val result = fixture.void(opening)

        assertEquals(VoidAdjustmentResult.StorageFailed(StorageFailure.DISK_FULL), result)
    }

    private fun seedRow(id: Long, type: StockMovementType, quantity: Long) {
        fixture.catalog.database.seed { tables ->
            tables.copy(
                movements = tables.movements + (id to row(id, type, quantity)),
                lastIds = tables.lastIds + ("stock_movements" to id),
            )
        }
    }

    private fun row(id: Long, type: StockMovementType, quantity: Long) = StockMovementRecord(
        movement = StockMovementDraft(
            key = StockKey(PRODUCT_ID, CatalogFixture.MAIN_LOCATION_ID, lotId = null),
            type = type,
            quantity = QuantityMilli(quantity),
            reversesMovementId = null,
        ).recordedAs(id),
        reason = null,
        unitCost = Money.ZERO,
        totalCost = Money.ZERO,
        balanceAfter = QuantityMilli(quantity),
        averageCostAfter = Money.ZERO,
        stockValueAfter = Money.ZERO,
        source = StockMovementSource.NONE,
        note = null,
        occurredAt = CatalogFixture.EARLIER,
        businessDate = requireNotNull(BusinessDate.parse("2023-11-13")),
        createdAt = CatalogFixture.EARLIER,
    )
}
