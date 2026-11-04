package io.github.yesidrobayo.shopledger.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExactUnitCostTest {
    @Test
    fun `a whole unit cost is that amount for one unit`() {
        assertEquals(
            ExactUnitCost(Money(1355L), QuantityMilli(1000L)),
            ExactUnitCost.perUnit(Money(1355L)),
        )
    }

    @Test
    fun `a box keeps its total and its quantity without dividing them`() {
        // 1000 unidades por 400: 0,4 por unidad, que redondeado sería cero.
        val cost = ExactUnitCost(Money(400L), QuantityMilli(1_000_000L))

        assertEquals(Money(400L), cost.amount)
        assertEquals(QuantityMilli(1_000_000L), cost.quantity)
    }

    @Test
    fun `a cost without amount is still a valid value`() {
        // Sin costo conocido: quien sugiere un precio lo rechaza, no este tipo.
        assertEquals(Money.ZERO, ExactUnitCost.perUnit(Money.ZERO).amount)
    }

    @Test
    fun `a cost needs a positive quantity`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExactUnitCost(Money(400L), QuantityMilli.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExactUnitCost(Money(400L), QuantityMilli(-1000L))
        }
    }
}
