package io.github.yesidrobayo.shopledger.domain.rule.purchase

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Las dos formas de escribir una línea de compra, con los ejemplos de la regla de la compra. Montos
 * en unidad menor de una moneda cualquiera.
 */
class PurchaseLineRulesTest {
    @Test
    fun `a line total gives the rounded unit cost`() {
        // 3 kg por 25000: 8333,33 por kilogramo.
        assertEquals(
            Checked.Value(PurchaseLineAmounts(Money(8_333), Money(25_000))),
            PurchaseLineRules.amounts(
                QuantityMilli(3_000),
                PurchaseLineAmount.LineTotal(Money(25_000)),
            ),
        )
        // 3 por 20000: 6666,67 sube a 6667.
        assertEquals(
            Checked.Value(PurchaseLineAmounts(Money(6_667), Money(20_000))),
            PurchaseLineRules.amounts(
                QuantityMilli(3_000),
                PurchaseLineAmount.LineTotal(Money(20_000)),
            ),
        )
    }

    @Test
    fun `a unit cost gives the rounded line total`() {
        assertEquals(
            Checked.Value(PurchaseLineAmounts(Money(1_200), Money(12_000))),
            PurchaseLineRules.amounts(
                QuantityMilli(10_000),
                PurchaseLineAmount.UnitCost(Money(1_200)),
            ),
        )
        // 0,250 kg a 12500 por kilogramo: 3125.
        assertEquals(
            Checked.Value(PurchaseLineAmounts(Money(12_500), Money(3_125))),
            PurchaseLineRules.amounts(
                QuantityMilli(250),
                PurchaseLineAmount.UnitCost(Money(12_500)),
            ),
        )
        // Una bonificación vale cero.
        assertEquals(
            Checked.Value(PurchaseLineAmounts(Money.ZERO, Money.ZERO)),
            PurchaseLineRules.amounts(
                QuantityMilli(2_000),
                PurchaseLineAmount.UnitCost(Money.ZERO),
            ),
        )
    }

    @Test
    fun `amounts over their caps are rejected with the cap`() {
        val unitCap = Limit.UNIT_MONEY.max
        val documentCap = Limit.DOCUMENT_TOTAL.max
        assertEquals(
            Checked.AboveLimit(Limit.UNIT_MONEY),
            PurchaseLineRules.amounts(
                QuantityMilli(1_000),
                PurchaseLineAmount.UnitCost(Money(unitCap + 1)),
            ),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            PurchaseLineRules.amounts(
                QuantityMilli(1_000_000_000),
                PurchaseLineAmount.UnitCost(Money(unitCap)),
            ),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            PurchaseLineRules.amounts(
                QuantityMilli(1_000),
                PurchaseLineAmount.LineTotal(Money(documentCap + 1)),
            ),
        )
        assertEquals(
            Checked.AboveLimit(Limit.UNIT_MONEY),
            PurchaseLineRules.amounts(
                QuantityMilli(1),
                PurchaseLineAmount.LineTotal(Money(unitCap)),
            ),
        )
        // Justo en el tope sí vale.
        assertEquals(
            Checked.Value(Money(unitCap)),
            PurchaseLineRules.costPerUnit(QuantityMilli(1), Money(1_000_000_000)),
        )
    }

    @Test
    fun `a quantity that is not positive or a negative amount is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            PurchaseLineRules.amounts(QuantityMilli.ZERO, PurchaseLineAmount.UnitCost(Money(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PurchaseLineRules.amounts(QuantityMilli(1_000), PurchaseLineAmount.UnitCost(Money(-1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PurchaseLineRules.amounts(QuantityMilli(1_000), PurchaseLineAmount.LineTotal(Money(-1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PurchaseLineRules.costPerUnit(QuantityMilli(-1), Money(1))
        }
    }
}
