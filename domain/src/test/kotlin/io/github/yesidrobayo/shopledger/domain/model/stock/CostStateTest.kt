package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CostStateTest {
    @Test
    fun `a product without movements starts without stock or value and at its initial cost`() {
        assertEquals(
            CostState(QuantityMilli.ZERO, Money(900L), Money.ZERO),
            CostState.initial(Money(900L)),
        )
    }

    @Test
    fun `the stock value can be zero or positive`() {
        assertEquals(Money.ZERO, CostState(QuantityMilli(5000L), Money(0L), Money.ZERO).stockValue)
        // 1000 unidades por 400: el promedio redondea a 0, y el valor lleva el costo.
        assertEquals(
            Money(400L),
            CostState(QuantityMilli(1_000_000L), Money(0L), Money(400L)).stockValue,
        )
    }

    @Test
    fun `the stock value is never negative`() {
        assertThrows(IllegalArgumentException::class.java) {
            CostState(QuantityMilli(1000L), Money(1000L), Money(-1L))
        }
    }
}
