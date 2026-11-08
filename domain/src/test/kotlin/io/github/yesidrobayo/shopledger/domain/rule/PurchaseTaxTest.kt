package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * El impuesto de una compra y si entra en el costo. Montos en unidad menor de una moneda cualquiera,
 * sin decimales salvo que se diga otra cosa; tasas inventadas.
 */
class PurchaseTaxTest {
    @Test
    fun `the recoverable tax example gives two different costs`() {
        // 10 unidades por 12100, con 21,00 % incluido.
        val recoverable = purchase(TaxMode.INCLUDED, 12_100L, 2_100L, recoverable = true)
        val notRecoverable = purchase(TaxMode.INCLUDED, 12_100L, 2_100L, recoverable = false)

        assertEquals(PurchaseLineTax(Money(12_100L), Money(2_100L), Money(10_000L)), recoverable)
        assertEquals(PurchaseLineTax(Money(12_100L), Money(2_100L), Money(12_100L)), notRecoverable)

        // El costo es la T del costo promedio: 1000 por unidad si se recupera, 1210 si no.
        assertEquals(Money(1_000L), averageAfterBuyingTen(recoverable.costAmount))
        assertEquals(Money(1_210L), averageAfterBuyingTen(notRecoverable.costAmount))
    }

    @Test
    fun `with the tax added the line pays net plus tax`() {
        // 10000 netos con 21 % sumado: se pagan 12100.
        assertEquals(
            PurchaseLineTax(Money(12_100L), Money(2_100L), Money(10_000L)),
            purchase(TaxMode.ADDED, 10_000L, 2_100L, recoverable = true),
        )
        assertEquals(
            PurchaseLineTax(Money(12_100L), Money(2_100L), Money(12_100L)),
            purchase(TaxMode.ADDED, 10_000L, 2_100L, recoverable = false),
        )
        // Dos decimales: 19,99 con 8,25 % sumado. El impuesto exacto es 1,649 y sube a 1,65.
        assertEquals(
            PurchaseLineTax(Money(2_164L), Money(165L), Money(1_999L)),
            purchase(TaxMode.ADDED, 1_999L, 825L, recoverable = true),
        )
    }

    @Test
    fun `an included tax with rounding keeps the paid total`() {
        // 999 con 19 % incluido: el neto exacto es 839,496 y queda en 839.
        assertEquals(
            PurchaseLineTax(Money(999L), Money(160L), Money(839L)),
            purchase(TaxMode.INCLUDED, 999L, 1_900L, recoverable = true),
        )
    }

    @Test
    fun `without tax the whole line is cost whatever the setting`() {
        for (recoverable in listOf(true, false)) {
            assertEquals(
                PurchaseLineTax(Money(12_100L), Money.ZERO, Money(12_100L)),
                purchase(TaxMode.NONE, 12_100L, 2_100L, recoverable),
            )
        }
    }

    @Test
    fun `a bonus line worth zero has no tax and no cost`() {
        for (mode in TaxMode.entries) {
            assertEquals(
                PurchaseLineTax(Money.ZERO, Money.ZERO, Money.ZERO),
                purchase(mode, 0L, 2_100L, recoverable = true),
            )
        }
    }

    @Test
    fun `a purchase line above the document cap is rejected`() {
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            TaxCalculator.purchaseLine(
                Money(Limit.DOCUMENT_TOTAL.max),
                TaxMode.ADDED,
                BasisPoints(2_100L),
                taxRecoverable = true,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TaxCalculator.purchaseLine(Money(1L), TaxMode.ADDED, BasisPoints(10_001L), true)
        }
    }

    private fun purchase(mode: TaxMode, entered: Long, rateBp: Long, recoverable: Boolean) = when (
        val result =
            TaxCalculator.purchaseLine(Money(entered), mode, BasisPoints(rateBp), recoverable)
    ) {
        is Checked.Value -> result.value
        else -> throw AssertionError("$result")
    }

    /** El costo promedio tras comprar 10 unidades por [cost], sin stock antes. */
    private fun averageAfterBuyingTen(cost: Money): Money {
        val after = WeightedAverageCost.entry(
            CostState.initial(Money.ZERO),
            QuantityMilli(10_000L),
            cost,
        )
        return (after as Checked.Value).value.averageCost
    }
}
