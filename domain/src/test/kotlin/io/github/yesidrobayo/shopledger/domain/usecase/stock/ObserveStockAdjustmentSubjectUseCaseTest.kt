package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockFixture.Companion.PRODUCT_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que el formulario del ajuste lee del producto. */
class ObserveStockAdjustmentSubjectUseCaseTest {
    private val fixture = StockFixture()

    @Test
    fun `a product without movements still accepts its opening balance`() = runTest {
        fixture.seedStock(units = 0L, unitCost = 700L, minStock = 2_000L)

        val subject = requireNotNull(fixture.observe(PRODUCT_ID).first())

        assertTrue(subject.acceptsOpeningBalance)
        assertEquals(CatalogFixture.UNIT_ID, subject.unit.id)
        assertEquals(QuantityMilli(2_000L), subject.minStock)
        assertEquals(CostState(QuantityMilli.ZERO, Money(700L), Money.ZERO), subject.cost)
        assertFalse(subject.isArchived)
    }

    @Test
    fun `after a movement it shows the stock and no longer accepts an opening balance`() = runTest {
        fixture.seedStock(units = 5_000L, unitCost = 700L)
        fixture.adjust(
            StockAdjustmentRequest.Change(
                PRODUCT_ID,
                StockAdjustmentReason.DAMAGED,
                QuantityMilli(1_000L),
            ),
        )

        val subject = requireNotNull(fixture.observe(PRODUCT_ID).first())

        assertFalse(subject.acceptsOpeningBalance)
        assertEquals(QuantityMilli(4_000L), subject.cost.onHand)
        assertEquals(Money(2_800L), subject.cost.stockValue)
        assertEquals("Producto inventado 1", subject.name)
    }

    @Test
    fun `a missing product reads as null`() = runTest {
        assertNull(fixture.observe(PRODUCT_ID).first())
    }
}
