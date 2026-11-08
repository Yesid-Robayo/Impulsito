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
}
