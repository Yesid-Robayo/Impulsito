package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import org.junit.Assert.assertEquals
import org.junit.Test

class OpeningBalanceRuleTest {
    @Test
    fun `24 units at 1200 leave an average of 1200 and a stock value of 28800`() {
        // El ejemplo del inventario inicial en las reglas de negocio, escrito a mano.
        val entry = OpeningBalanceRule.entry(
            CostState.initial(Money(1_200L)),
            QuantityMilli(24_000L),
            Money(1_200L),
        )

        assertEquals(
            Checked.Value(
                OpeningBalanceEntry(
                    totalCost = Money(28_800L),
                    after = CostState(QuantityMilli(24_000L), Money(1_200L), Money(28_800L)),
                ),
            ),
            entry,
        )
    }

    @Test
    fun `a zero cost enters with no known cost`() {
        val entry = OpeningBalanceRule.entry(
            CostState.initial(Money.ZERO),
            QuantityMilli(5_000L),
            Money.ZERO,
        )

        assertEquals(
            Checked.Value(
                OpeningBalanceEntry(
                    Money.ZERO,
                    CostState(QuantityMilli(5_000L), Money.ZERO, Money.ZERO),
                ),
            ),
            entry,
        )
    }

    @Test
    fun `a weighed quantity rounds its total to the nearest minor unit`() {
        // 0,333 kg a 18500 por kilogramo: 6160,5, que redondea a 6161.
        val entry = OpeningBalanceRule.entry(
            CostState.initial(Money.ZERO),
            QuantityMilli(333L),
            Money(18_500L),
        )

        assertEquals(Money(6_161L), (entry as Checked.Value).value.totalCost)
    }

    @Test
    fun `a total above the document limit is rejected`() {
        // Un millón de unidades al costo máximo: 10^18, sobre el tope de 10^15.
        val entry = OpeningBalanceRule.entry(
            CostState.initial(Money.ZERO),
            QuantityMilli(1_000_000_000L),
            Money(1_000_000_000_000L),
        )

        assertEquals(Checked.AboveLimit(Limit.DOCUMENT_TOTAL), entry)
    }

    @Test
    fun `a total that does not fit in a Long overflows`() {
        val entry = OpeningBalanceRule.entry(
            CostState.initial(Money.ZERO),
            QuantityMilli(Long.MAX_VALUE),
            Money(Long.MAX_VALUE),
        )

        assertEquals(Checked.Overflow, entry)
    }

    @Test
    fun `an entry whose stock value does not fit in a Long overflows`() {
        val state = CostState(QuantityMilli(1_000L), Money(1L), Money(Long.MAX_VALUE))

        assertEquals(
            Checked.Overflow,
            OpeningBalanceRule.entry(state, QuantityMilli(1_000L), Money(1L)),
        )
    }

    @Test
    fun `the stock value and the on hand quantity keep their limits`() {
        val richState = CostState(QuantityMilli(1_000L), Money(1L), Money(9_000_000_000_000_000L))
        assertEquals(
            Checked.AboveLimit(Limit.STOCK_VALUE),
            OpeningBalanceRule.entry(richState, QuantityMilli(1_000L), Money(1L)),
        )
        val fullState = CostState(QuantityMilli(8_000_000_000L), Money(1L), Money(8_000_000L))
        assertEquals(
            Checked.AboveLimit(Limit.ON_HAND_MILLI),
            OpeningBalanceRule.entry(fullState, QuantityMilli(1_000L), Money(1L)),
        )
    }

    @Test
    fun `a thousand grams written as 1000 in total are worth exactly 1000 at 1 per gram`() {
        // Mil unidades enteras de la unidad base, como mil gramos: 1000 × 1000 / 1000000 = 1.
        val entry = OpeningBalanceRule.entryOfTotal(
            CostState.initial(Money(1L)),
            QuantityMilli(1_000_000L),
            Money(1_000L),
        )

        assertEquals(
            Checked.Value(
                OpeningBalanceEntry(
                    totalCost = Money(1_000L),
                    after = CostState(QuantityMilli(1_000_000L), Money(1L), Money(1_000L)),
                ),
            ),
            entry,
        )
    }

    @Test
    fun `three kilograms for 1000 in total keep 1000 and cost 333 each`() {
        // 1000 × 1000 / 3000 = 333,33, que redondea a 333; el valor queda en 1000, no en 999.
        val entry = OpeningBalanceRule.entryOfTotal(
            CostState.initial(Money(333L)),
            QuantityMilli(3_000L),
            Money(1_000L),
        )

        assertEquals(
            Checked.Value(
                OpeningBalanceEntry(
                    totalCost = Money(1_000L),
                    after = CostState(QuantityMilli(3_000L), Money(333L), Money(1_000L)),
                ),
            ),
            entry,
        )
        // Por unidad, los mismos 333 dejan el valor en 999.
        val perUnit = OpeningBalanceRule.entry(
            CostState.initial(Money(333L)),
            QuantityMilli(3_000L),
            Money(333L),
        )
        assertEquals(Money(999L), (perUnit as Checked.Value).value.after.stockValue)
    }

    @Test
    fun `a cheap total whose unit cost rounds to zero keeps its whole value`() {
        // Mil gramos por 400: el gramo vale 0,4, que redondea a 0, pero el stock vale 400.
        val entry = OpeningBalanceRule.entryOfTotal(
            CostState.initial(Money.ZERO),
            QuantityMilli(1_000_000L),
            Money(400L),
        )

        assertEquals(
            Checked.Value(
                OpeningBalanceEntry(
                    totalCost = Money(400L),
                    after = CostState(QuantityMilli(1_000_000L), Money.ZERO, Money(400L)),
                ),
            ),
            entry,
        )
    }

    @Test
    fun `the unit cost of a total rounds to the nearest minor unit`() {
        assertEquals(
            Checked.Value(Money(1L)),
            OpeningBalanceRule.unitCostOf(QuantityMilli(1_000_000L), Money(1_000L)),
        )
        assertEquals(
            Checked.Value(Money(333L)),
            OpeningBalanceRule.unitCostOf(QuantityMilli(3_000L), Money(1_000L)),
        )
        assertEquals(
            Checked.Value(Money.ZERO),
            OpeningBalanceRule.unitCostOf(QuantityMilli(1_000_000L), Money(400L)),
        )
        // 500 × 1000 / 1000000 = 0,5: el empate se aleja de cero.
        assertEquals(
            Checked.Value(Money(1L)),
            OpeningBalanceRule.unitCostOf(QuantityMilli(1_000_000L), Money(500L)),
        )
    }

    @Test
    fun `a total or a unit cost above their limits is rejected`() {
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            OpeningBalanceRule.entryOfTotal(
                CostState.initial(Money.ZERO),
                QuantityMilli(1_000L),
                Money(1_000_000_000_000_001L),
            ),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            OpeningBalanceRule.unitCostOf(QuantityMilli(1_000L), Money(1_000_000_000_000_001L)),
        )
        // Una milésima por 10^12 sería 10^15 por unidad entera, sobre el tope de 10^12.
        assertEquals(
            Checked.AboveLimit(Limit.UNIT_MONEY),
            OpeningBalanceRule.unitCostOf(QuantityMilli(1L), Money(1_000_000_000_000L)),
        )
    }
}
