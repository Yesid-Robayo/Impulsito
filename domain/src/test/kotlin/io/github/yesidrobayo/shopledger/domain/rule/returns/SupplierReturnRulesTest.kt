package io.github.yesidrobayo.shopledger.domain.rule.returns

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Las cuentas de una devolución a proveedor, con los ejemplos de la regla escritos a mano. Montos en
 * unidad menor de una moneda cualquiera.
 */
class SupplierReturnRulesTest {
    @Test
    fun `two units of a line of 1400 leave at 2800, and the average falls to 1160`() {
        // Q = 12000, V = 14400 y C = 1200, con una línea de compra de 2 unidades a 1400.
        val bought = purchaseLine(quantity = 2_000, costAmount = 2_800, lineTotal = 2_800)

        val line = priced(
            price(
                listOf(SupplierReturnPricingLine(1, bought, milli(2_000))),
                cost(12_000, 1_200, 14_400),
            ),
        )

        assertEquals(
            SupplierReturnLineAmounts(Money(2_800), Money(1_400), Money(2_800)),
            line.amounts,
        )
        assertEquals(cost(10_000, 1_160, 11_600), line.after)
    }

    @Test
    fun `returning a line of three units one by one adds up to what it cost`() {
        val bought = purchaseLine(quantity = 3_000, costAmount = 10_000, lineTotal = 10_000)
        var taken = LineAmounts.NONE
        val costs = (1..3).map {
            val share = priced(
                price(
                    listOf(SupplierReturnPricingLine(1, bought, milli(1_000))),
                    cost(3_000, 0, 10_000),
                    taken,
                ),
            ).amounts
            taken =
                LineAmounts(
                    taken.quantity + milli(1_000),
                    taken.net + share.lineTotal,
                    Money.ZERO,
                    taken.cost + share.cost,
                )
            share.cost
        }

        assertEquals(listOf(Money(3_333), Money(3_334), Money(3_333)), costs)
    }

    @Test
    fun `after voiding a return in the middle the active ones still add up to the line`() {
        // Se devolvieron 3333, 3334 y 3333 y se anuló la segunda: las activas llevan 2 unidades y 6666.
        val bought = purchaseLine(quantity = 3_000, costAmount = 10_000, lineTotal = 10_000)
        val taken = LineAmounts(milli(2_000), Money(6_666), Money.ZERO, Money(6_666))

        val line = priced(
            price(
                listOf(SupplierReturnPricingLine(1, bought, milli(1_000))),
                cost(1_000, 3_334, 3_334),
                taken,
            ),
        )

        assertEquals(Money(3_334), line.amounts.cost)
        assertEquals(Money(3_334), line.amounts.lineTotal)
    }

    @Test
    fun `the supplier gives back the tax with the line while the stock leaves at its cost`() {
        // Impuesto recuperable: la línea pagó 12100 y al stock entraron 10000.
        val bought = purchaseLine(quantity = 10_000, costAmount = 10_000, lineTotal = 12_100)
        val taken = LineAmounts(milli(4_000), Money(4_840), Money.ZERO, Money(4_000))

        val line = priced(
            price(
                listOf(SupplierReturnPricingLine(1, bought, milli(5_000))),
                cost(6_000, 1_000, 6_000),
                taken,
            ),
        )

        assertEquals(
            SupplierReturnLineAmounts(Money(5_000), Money(1_000), Money(6_050)),
            line.amounts,
        )
        assertEquals(
            LineAmounts(milli(10_000), Money(12_100), Money.ZERO, Money(10_000)),
            SupplierReturnRules.boughtOf(bought.content),
        )
    }

    @Test
    fun `a purchase line gives no more than was bought, and later lines still price`() {
        val bought = purchaseLine(quantity = 3_000, costAmount = 3_000, lineTotal = 3_000)
        val taken = LineAmounts(milli(500), Money(500), Money.ZERO, Money(500))

        val pricing = price(
            listOf(
                SupplierReturnPricingLine(1, bought, milli(2_000)),
                SupplierReturnPricingLine(1, bought, milli(2_000)),
                SupplierReturnPricingLine(1, bought, milli(500)),
            ),
            cost(3_000, 1_000, 3_000),
            taken,
        )

        val (first, second, third) = pricing.lines
        assertEquals(Money(2_000), (first as SupplierReturnLinePricing.Priced).amounts.lineTotal)
        assertEquals(SupplierReturnLinePricing.AboveRemaining(milli(500)), second)
        assertEquals(Money(500), (third as SupplierReturnLinePricing.Priced).amounts.lineTotal)
        assertEquals(cost(500, 1_000, 500), third.after)
        // Una línea sin valor deja la devolución sin total.
        assertNull(pricing.total)
    }

    @Test
    fun `a quantity that is not positive has no value, with a purchase line or without`() {
        val bought = purchaseLine(quantity = 3_000, costAmount = 3_000, lineTotal = 3_000)

        val pricing = price(
            listOf(
                SupplierReturnPricingLine(1, bought, milli(0)),
                SupplierReturnPricingLine(1, null, milli(-1)),
            ),
            cost(3_000, 1_000, 3_000),
        )

        assertEquals(
            listOf(
                SupplierReturnLinePricing.InvalidQuantity,
                SupplierReturnLinePricing.InvalidQuantity,
            ),
            pricing.lines,
        )
        assertNull(pricing.total)
    }

    @Test
    fun `without a purchase line it leaves with the exit cost and lines of one product chain`() {
        // 24 unidades por 10000: el promedio redondea a 417, y 6 se llevan la cuarta parte exacta.
        val pricing = price(
            listOf(
                SupplierReturnPricingLine(1, null, milli(6_000)),
                SupplierReturnPricingLine(1, null, milli(6_000)),
            ),
            cost(24_000, 417, 10_000),
        )

        val (first, second) = pricing.lines.map { it as SupplierReturnLinePricing.Priced }
        assertEquals(
            SupplierReturnLineAmounts(Money(2_500), Money(417), Money(2_500)),
            first.amounts,
        )
        assertEquals(first.after, second.before)
        assertEquals(cost(12_000, 417, 5_000), second.after)
        assertEquals(Money(5_000), pricing.total)
    }

    @Test
    fun `the total is refused when it passes a document`() {
        val big = cost(1_000, 1, Limit.DOCUMENT_TOTAL.max)

        val pricing = SupplierReturnRules.price(
            listOf(
                SupplierReturnPricingLine(1, null, milli(1_000)),
                SupplierReturnPricingLine(2, null, milli(1_000)),
            ),
            stateOf = { big },
            takenOf = { LineAmounts.NONE },
        )

        assertNull(pricing.total)
    }

    @Test
    fun `a line whose figures do not fit is too large and moves nothing`() {
        val huge = cost(0, Long.MAX_VALUE / 2, 0)
        // Queda una milésima con un valor enorme: su promedio no cabe.
        val narrow = cost(2, 0, Long.MAX_VALUE / 2)
        val free = purchaseLine(quantity = 1_000, costAmount = 0, lineTotal = 0, productId = 2)

        val pricing = SupplierReturnRules.price(
            listOf(
                SupplierReturnPricingLine(1, null, milli(1_000_000)),
                SupplierReturnPricingLine(2, free, milli(1)),
            ),
            stateOf = { if (it == 1L) huge else narrow },
            takenOf = { LineAmounts.NONE },
        )

        assertEquals(
            listOf(SupplierReturnLinePricing.TooLarge, SupplierReturnLinePricing.TooLarge),
            pricing.lines,
        )
        assertNull(pricing.total)
    }

    @Test
    fun `a unit cost, a line total or a cost that does not fit is refused`() {
        val share = ReturnShare(Money.ZERO, Money.ZERO, Money.ZERO)

        assertEquals(
            Checked.Overflow,
            SupplierReturnRules.fromPurchaseLine(newLine(1, Long.MAX_VALUE / 2, 0), share),
        )
        assertEquals(
            Checked.AboveLimit(Limit.UNIT_MONEY),
            SupplierReturnRules.fromPurchaseLine(
                newLine(1_000, Limit.UNIT_MONEY.max + 1, 0),
                share,
            ),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            SupplierReturnRules.fromPurchaseLine(
                newLine(1_000, 0, 0),
                ReturnShare(Money(Limit.DOCUMENT_TOTAL.max + 1), Money.ZERO, Money.ZERO),
            ),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            SupplierReturnRules.fromPurchaseLine(
                newLine(1_000, 0, 0),
                ReturnShare(Money.ZERO, Money.ZERO, Money(Limit.DOCUMENT_TOTAL.max + 1)),
            ),
        )
    }

    @Test
    fun `without a purchase line an exit cost that does not fit is refused`() {
        assertEquals(
            Checked.Overflow,
            SupplierReturnRules.loose(cost(0, Long.MAX_VALUE / 2, 0), milli(1_000_000)),
        )
    }

    @Test
    fun `a line of another product or a negative loose quantity is a programming error`() {
        val other = purchaseLine(quantity = 1_000, costAmount = 0, lineTotal = 0, productId = 2)

        assertThrows(IllegalArgumentException::class.java) {
            SupplierReturnPricingLine(1, other, milli(1_000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SupplierReturnRules.loose(CostState.initial(Money.ZERO), milli(-1))
        }
    }

    private fun price(
        lines: List<SupplierReturnPricingLine>,
        state: CostState,
        taken: LineAmounts = LineAmounts.NONE,
    ): SupplierReturnPricing = SupplierReturnRules.price(lines, stateOf = {
        state
    }, takenOf = { taken })

    private fun priced(pricing: SupplierReturnPricing): SupplierReturnLinePricing.Priced =
        pricing.lines.single() as SupplierReturnLinePricing.Priced

    private fun milli(value: Long) = QuantityMilli(value)

    private fun cost(milli: Long, average: Long, value: Long) =
        CostState(QuantityMilli(milli), Money(average), Money(value))

    private fun purchaseLine(
        quantity: Long,
        costAmount: Long,
        lineTotal: Long,
        productId: Long = 1,
    ) = PurchaseLine(7, 1, newLine(quantity, costAmount, lineTotal, productId))

    private fun newLine(quantity: Long, costAmount: Long, lineTotal: Long, productId: Long = 1) =
        NewPurchaseLine(
            productId = productId,
            unitId = 1,
            unitFactor = milli(1_000),
            enteredQuantity = milli(quantity),
            quantity = milli(quantity),
            unitCost = Money.ZERO,
            lineTotal = Money(lineTotal),
            taxRate = BasisPoints.ZERO,
            taxNameSnapshot = null,
            taxAmount = Money.ZERO,
            costAmount = Money(costAmount),
            lotId = null,
            referenceUnitCost = null,
        )
}
