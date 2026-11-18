package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Juntar dos lecturas de la misma operación y cambiar la operación a mano, con tarjetas escritas a
 * mano: lo que se suma, lo que se corrige y lo que va como otra línea para no inventar nada.
 */
class DraftJoinsTest {
    private val units = { line: DraftLine -> line.unitId ?: BASE }

    private fun item(id: Long) = DraftTarget.Existing(id)

    private fun line(
        product: DraftTarget?,
        quantity: Long? = null,
        unit: Long? = null,
        unitPrice: Long? = null,
        lineTotal: Long? = null,
    ) = DraftLine(
        product,
        quantity?.let(::QuantityMilli),
        unit,
        unitPrice?.let(::Money),
        lineTotal?.let(::Money),
    )

    private fun join(old: List<DraftLine>, said: List<DraftLine>, sums: Boolean) =
        joinedLines(old, said, sums, units)

    // Las líneas.

    @Test
    fun `a product that is not on the card is one more line at the end`() {
        assertEquals(
            listOf(line(item(1), 2_000), line(item(2), 3_000)),
            join(listOf(line(item(1), 2_000)), listOf(line(item(2), 3_000)), sums = true),
        )
    }

    @Test
    fun `a line still to choose is never merged`() {
        val choose = line(null, 1_000)
        assertEquals(listOf(choose, choose), join(listOf(choose), listOf(choose), sums = true))
    }

    @Test
    fun `a product said again is summed in the same unit`() {
        assertEquals(
            listOf(line(item(1), 5_000, unitPrice = 600)),
            join(
                listOf(line(item(1), 2_000, unitPrice = 600)),
                listOf(line(item(1), 3_000)),
                sums = true,
            ),
        )
    }

    @Test
    fun `the base unit said or unsaid is the same unit when summing`() {
        assertEquals(
            listOf(line(item(1), 5_000, unitPrice = 600)),
            join(
                listOf(line(item(1), 2_000)),
                listOf(line(item(1), 3_000, unit = BASE, unitPrice = 600)),
                sums = true,
            ),
        )
    }

    @Test
    fun `equal prices are summed`() {
        assertEquals(
            listOf(line(item(1), 5_000, unitPrice = 600)),
            join(
                listOf(line(item(1), 2_000, unitPrice = 600)),
                listOf(line(item(1), 3_000, unitPrice = 600)),
                sums = true,
            ),
        )
    }

    @Test
    fun `what cannot be summed without inventing goes as another line`() {
        val had = line(item(1), 2_000, unitPrice = 600)
        val cases = listOf(
            // Otro precio.
            line(item(1), 3_000, unitPrice = 700),
            // Otra unidad.
            line(item(1), 3_000, unit = OTHER),
            // Sin cantidad.
            line(item(1), null),
            // Con total de línea.
            line(item(1), 3_000, lineTotal = 2_000),
        )
        for (said in cases) {
            assertEquals(listOf(had, said), join(listOf(had), listOf(said), sums = true))
        }
        val withTotal = line(item(1), 2_000, lineTotal = 1_200)
        val plain = line(item(1), 1_000)
        assertEquals(listOf(withTotal, plain), join(listOf(withTotal), listOf(plain), sums = true))
        val unknown = line(item(1), null)
        assertEquals(listOf(unknown, plain), join(listOf(unknown), listOf(plain), sums = true))
    }

    @Test
    fun `a sum that does not fit goes as another line`() {
        val huge = line(item(1), Long.MAX_VALUE)
        val more = line(item(1), 1_000)
        assertEquals(listOf(huge, more), join(listOf(huge), listOf(more), sums = true))
    }

    @Test
    fun `a product on two lines is not guessed`() {
        val first = line(item(1), 1_000)
        val second = line(item(1), 2_000, unit = OTHER)
        val said = line(item(1), 4_000)
        assertEquals(
            listOf(first, second, said),
            join(listOf(first, second), listOf(said), sums = false),
        )
    }

    @Test
    fun `without summing the quantity and price said replace the line`() {
        assertEquals(
            listOf(line(item(1), 5_000, unit = BASE, unitPrice = 700)),
            join(
                listOf(line(item(1), 2_000, unitPrice = 600)),
                listOf(line(item(1), 5_000, unit = BASE, unitPrice = 700)),
                sums = false,
            ),
        )
    }

    @Test
    fun `a total said replaces the price of the line`() {
        assertEquals(
            listOf(line(item(1), 2_000, lineTotal = 1_500)),
            join(
                listOf(line(item(1), 2_000, unitPrice = 600)),
                listOf(line(item(1), null, lineTotal = 1_500)),
                sums = false,
            ),
        )
    }

    @Test
    fun `a price alone keeps the quantity and unit`() {
        assertEquals(
            listOf(line(item(1), 2_000, unit = OTHER, unitPrice = 900)),
            join(
                listOf(line(item(1), 2_000, unit = OTHER)),
                listOf(line(item(1), null, unitPrice = 900)),
                sums = false,
            ),
        )
    }

    @Test
    fun `a line that says nothing new keeps the old one`() {
        val had = line(item(1), 2_000, unit = OTHER, unitPrice = 600)
        assertEquals(listOf(had), join(listOf(had), listOf(line(item(1), null)), sums = false))
    }

    @Test
    fun `a quantity in another unit forgets the old price`() {
        assertEquals(
            listOf(line(item(1), 3_000, unit = OTHER)),
            join(
                listOf(line(item(1), 2_000, unitPrice = 600, lineTotal = 1_200)),
                listOf(line(item(1), 3_000, unit = OTHER)),
                sums = false,
            ),
        )
    }

    @Test
    fun `a quantity in the same unit keeps the old price`() {
        assertEquals(
            listOf(line(item(1), 3_000, unitPrice = 600)),
            join(
                listOf(line(item(1), 2_000, unitPrice = 600)),
                listOf(line(item(1), 3_000)),
                sums = false,
            ),
        )
    }

    @Test
    fun `a new product said again keeps what was said of it`() {
        val input = NewProductTraits(usage = ProductUsage.INPUT)
        val category = NewProductTraits(newCategory = "Harinas")
        val had = line(DraftTarget.New("Harina integral", input), 1_000)
        val said = line(DraftTarget.New("harina INTEGRAL", category), 2_000)
        val both = NewProductTraits(usage = ProductUsage.INPUT, newCategory = "Harinas")
        assertEquals(
            listOf(line(DraftTarget.New("Harina integral", both), 3_000)),
            join(listOf(had), listOf(said), sums = true),
        )
    }

    @Test
    fun `products are the same only by id or by the name of a new one`() {
        assertTrue(sameProduct(item(1), item(1)))
        assertFalse(sameProduct(item(1), item(2)))
        assertFalse(sameProduct(item(1), DraftTarget.New("Pan")))
        assertTrue(sameProduct(DraftTarget.New("Pan"), DraftTarget.New("pan")))
        assertFalse(sameProduct(DraftTarget.New("Pan"), DraftTarget.New("Pan integral")))
        assertFalse(sameProduct(DraftTarget.New("Pan"), item(1)))
        assertFalse(sameProduct(null, item(1)))
    }

    @Test
    fun `an existing product said as new keeps the existing one`() {
        val had = line(item(1), 1_000)
        assertEquals(
            listOf(line(item(1), 2_000)),
            join(listOf(had), listOf(line(item(1), 2_000)), sums = false),
        )
        val fresh = line(DraftTarget.New("Pan"), 1_000)
        assertEquals(
            listOf(line(DraftTarget.New("Pan"), 4_000)),
            join(listOf(fresh), listOf(line(DraftTarget.New("pan"), 4_000)), sums = false),
        )
    }

    // El resto de la tarjeta.

    @Test
    fun `payment person and total said correct a sale`() {
        val sale = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(line(item(1), 2_000)),
            paymentMethodId = ON_ACCOUNT,
            partyId = 7,
            total = Money(1_000),
            credit = true,
        )
        val said = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(line(item(2), 1_000), line(item(3), 1_000)),
            paymentMethodId = CASH,
            partyId = 8,
            total = Money(5_000),
        )
        assertEquals(
            sale.copy(
                lines = sale.lines + said.lines,
                paymentMethodId = CASH,
                partyId = 8,
                total = Money(5_000),
                credit = false,
            ),
            sale.joinedWith(said, sums = true, unitOf = units),
        )
    }

    @Test
    fun `what a sale does not say again stays`() {
        val sale = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(line(item(1), 2_000)),
            paymentMethodId = CASH,
            partyId = 7,
            total = Money(1_000),
        )
        val said = PhraseDraft(PhraseIntent.SALE, lines = listOf(line(item(2), 1_000)))
        assertEquals(
            sale.copy(lines = sale.lines + said.lines),
            sale.joinedWith(said, sums = true, unitOf = units),
        )
    }

    @Test
    fun `on credit said makes the purchase on credit`() {
        val purchase = PhraseDraft(PhraseIntent.PURCHASE, lines = listOf(line(item(1), 2_000)))
        val said = PhraseDraft(PhraseIntent.PURCHASE, credit = true)
        assertEquals(
            purchase.copy(credit = true),
            purchase.joinedWith(said, sums = true, unitOf = units),
        )
    }

    @Test
    fun `the total of one line stays in that line`() {
        val sale = PhraseDraft(PhraseIntent.SALE, lines = listOf(line(item(1), 2_000)))
        val said = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(line(item(2), 1_000, lineTotal = 900)),
            total = Money(900),
        )
        assertEquals(
            sale.copy(lines = sale.lines + said.lines),
            sale.joinedWith(said, sums = true, unitOf = units),
        )
    }

    @Test
    fun `a count takes no payment person or total`() {
        val count = PhraseDraft(
            PhraseIntent.STOCK_ADJUSTMENT,
            reason = StockAdjustmentReason.COUNT_CORRECTION,
            lines = listOf(line(item(1), 2_000)),
        )
        val said = count.copy(
            lines = listOf(line(item(2), 1_000)),
            paymentMethodId = CASH,
            partyId = 7,
            total = Money(500),
            credit = true,
        )
        assertEquals(
            count.copy(lines = count.lines + said.lines),
            count.joinedWith(said, sums = false, unitOf = units),
        )
    }

    @Test
    fun `a recipe keeps its product and takes the yield said`() {
        val recipe = PhraseDraft(
            PhraseIntent.RECIPE,
            lines = listOf(line(item(1), 2_000)),
            target = item(9),
            targetQuantity = QuantityMilli(10_000),
        )
        val said = PhraseDraft(
            PhraseIntent.RECIPE,
            lines = listOf(line(item(2), 1_000)),
            target = item(9),
            targetQuantity = QuantityMilli(12_000),
        )
        assertEquals(
            recipe.copy(lines = recipe.lines + said.lines, targetQuantity = QuantityMilli(12_000)),
            recipe.joinedWith(said, sums = false, unitOf = units),
        )
        val untargeted = recipe.copy(target = null, targetQuantity = null)
        assertEquals(
            recipe.copy(lines = recipe.lines + said.lines),
            untargeted.joinedWith(said.copy(targetQuantity = null), sums = false, unitOf = units)
                .copy(targetQuantity = QuantityMilli(10_000)),
        )
    }

    // Cambiar la operación a mano.

    @Test
    fun `changing the operation keeps lines payment and total`() {
        val sale = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(line(item(1), 2_000, unitPrice = 600)),
            paymentMethodId = CASH,
            partyId = 7,
            total = Money(1_200),
        )
        assertEquals(
            sale.copy(
                intent = PhraseIntent.STOCK_ADJUSTMENT,
                reason = StockAdjustmentReason.COUNT_CORRECTION,
                partyId = null,
            ),
            sale.asOperation(
                PhraseIntent.STOCK_ADJUSTMENT,
                StockAdjustmentReason.COUNT_CORRECTION,
            ),
        )
        assertEquals(
            sale.copy(intent = PhraseIntent.PURCHASE, partyId = null),
            sale.asOperation(PhraseIntent.PURCHASE, StockAdjustmentReason.DAMAGED),
        )
        assertEquals(sale, sale.asOperation(PhraseIntent.SALE, reason = null))
    }

    private companion object {
        const val BASE = 2L
        const val OTHER = 3L
        const val CASH = 400L
        const val ON_ACCOUNT = 403L
    }
}
