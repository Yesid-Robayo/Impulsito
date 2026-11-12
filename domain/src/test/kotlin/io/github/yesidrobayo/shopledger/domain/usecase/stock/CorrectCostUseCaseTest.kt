package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.MAIN_LOCATION_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Corregir a mano el costo de un producto: una fila de cantidad cero, nunca una edición. */
class CorrectCostUseCaseTest {
    @Test
    fun `stock with cost 0 corrected to 1000 leaves a zero-quantity row and the new value`() =
        runTest {
            val fixture = CatalogFixture()
            fixture.seedProduct(1L, "Arroz 1 kg", onHand = QuantityMilli(12_000L))

            val result = fixture.correct(1L, Money(1_000L), " Factura perdida ")

            val id = (result as CorrectCostResult.Corrected).movementId
            val row = fixture.tables.movements.getValue(id)
            assertEquals(StockMovementType.COST_CORRECTION, row.movement.type)
            assertEquals(QuantityMilli.ZERO, row.movement.quantity)
            assertEquals(MAIN_LOCATION_ID, row.movement.key.locationId)
            assertEquals(Money(1_000L), row.unitCost)
            assertEquals(Money.ZERO, row.totalCost)
            assertEquals(QuantityMilli(12_000L), row.balanceAfter)
            assertEquals(Money(1_000L), row.averageCostAfter)
            assertEquals(Money(12_000L), row.stockValueAfter)
            assertEquals(StockMovementSource.NONE, row.source)
            assertEquals("Factura perdida", row.note)
            assertEquals(NOW, row.occurredAt)
            val product = fixture.product(1L)
            assertEquals(Money(1_000L), product.averageCost)
            assertEquals(Money(12_000L), product.stockValue)
            assertEquals(QuantityMilli(12_000L), product.onHand)
            assertEquals(1L, fixture.tables.changeSeq)
        }

    @Test
    fun `fractional stock is valued with rounding, and no stock is worth nothing`() = runTest {
        val fixture = CatalogFixture()
        fixture.seedProduct(1L, "Queso", onHand = QuantityMilli(1_250L))
        fixture.seedProduct(2L, "Leche", onHand = QuantityMilli(-3_000L), initialCost = Money(900L))

        fixture.correct(1L, Money(3_333L))
        fixture.correct(2L, Money(1_100L))

        // 1,25 × 3333 = 4166,25, que redondea a 4166.
        assertEquals(Money(4_166L), fixture.product(1L).stockValue)
        assertEquals(Money.ZERO, fixture.product(2L).stockValue)
        assertEquals(Money(1_100L), fixture.product(2L).averageCost)
    }

    @Test
    fun `the same cost writes nothing`() = runTest {
        val fixture = CatalogFixture()
        fixture.seedProduct(1L, "Arroz", initialCost = Money(800L), onHand = QuantityMilli(1_000L))
        val before = fixture.tables

        assertEquals(CorrectCostResult.Unchanged, fixture.correct(1L, Money(800L)))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `each reason to refuse writes nothing`() = runTest {
        val fixture = CatalogFixture()
        fixture.seedProduct(1L, "Arroz", onHand = QuantityMilli(1_000L))
        fixture.seedProduct(2L, "Viejo", archived = true)
        fixture.seedProduct(3L, "Corte", stockMode = ProductStockMode.UNTRACKED)
        val before = fixture.tables

        assertEquals(CorrectCostResult.CostOutOfRange, fixture.correct(1L, Money(-1L)))
        assertEquals(
            CorrectCostResult.CostOutOfRange,
            fixture.correct(1L, Money(1_000_000_000_001L)),
        )
        assertEquals(
            CorrectCostResult.NoteTooLong(200),
            fixture.correct(1L, Money(10L), "a".repeat(201)),
        )
        assertEquals(CorrectCostResult.NotFound, fixture.correct(99L, Money(10L)))
        assertEquals(CorrectCostResult.ProductArchived, fixture.correct(2L, Money(10L)))
        assertEquals(CorrectCostResult.NotTracked, fixture.correct(3L, Money(10L)))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `the cost limit is accepted, but not a stock value the rule cannot hold`() = runTest {
        val fixture = CatalogFixture()
        fixture.seedProduct(1L, "Caro", onHand = QuantityMilli(1_000L))
        fixture.seedProduct(2L, "Mucho", onHand = QuantityMilli(8_000_000_000L))
        val before = fixture.tables

        // El tope del costo con el tope del stock pasa de lo que la regla calcula: no se escribe.
        assertEquals(
            CorrectCostResult.StockValueTooLarge,
            fixture.correct(2L, Money(1_000_000_000_000L)),
        )
        assertEquals(before, fixture.tables)
        assertEquals(
            CorrectCostResult.Corrected(1L),
            fixture.correct(1L, Money(1_000_000_000_000L)),
        )
        assertEquals(Money(1_000_000_000_000L), fixture.product(1L).stockValue)
    }

    @Test
    fun `an unknown zone, an unconfirmed currency and a restore are results`() = runTest {
        val noZone = CatalogFixture(zoneId = "Nowhere/Unknown")
        noZone.seedProduct(1L, "Arroz", onHand = QuantityMilli(1_000L))
        assertEquals(CorrectCostResult.UnknownTimeZone, noZone.correct(1L, Money(10L)))

        val noCurrency = CatalogFixture(currencyConfirmed = false)
        noCurrency.seedProduct(1L, "Arroz", onHand = QuantityMilli(1_000L))
        assertEquals(CorrectCostResult.CurrencyUnconfirmed, noCurrency.correct(1L, Money(10L)))

        val restoring = CatalogFixture()
        restoring.seedProduct(1L, "Arroz", onHand = QuantityMilli(1_000L))
        restoring.transactions.restoreInProgress = true
        assertEquals(
            CorrectCostResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            restoring.correct(1L, Money(10L)),
        )
    }

    @Test
    fun `each write that fails leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing({
            seedProduct(1L, "Arroz", onHand = QuantityMilli(1_000L))
        }) { correct(1L, Money(1_000L)) }
    }

    private suspend fun CatalogFixture.correct(
        productId: Long,
        cost: Money,
        note: String? = null,
    ): CorrectCostResult = CorrectCostUseCase(
        transactions,
        products,
        systemRows,
        ledger,
        BusinessCalendarSource(settings),
        CurrencyLock(settings),
        clock,
    )(productId, cost, note)
}
