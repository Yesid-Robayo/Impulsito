package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseDraftGapsTest {
    private val bread = DraftTarget.Existing(1L)

    private fun line(
        product: DraftTarget? = bread,
        quantity: Long? = 1_000L,
        unitPrice: Long? = null,
        lineTotal: Long? = null,
    ) = DraftLine(
        product = product,
        quantity = quantity?.let(::QuantityMilli),
        unitId = null,
        unitPrice = unitPrice?.let(::Money),
        lineTotal = lineTotal?.let(::Money),
    )

    private fun gaps(draft: PhraseDraft) = draft.gaps()

    @Test
    fun `a complete sale has no gaps`() {
        assertEquals(
            emptySet<DraftGap>(),
            gaps(PhraseDraft(PhraseIntent.SALE, lines = listOf(line()), paymentMethodId = 1L)),
        )
    }

    @Test
    fun `a sale without lines, product, quantity or payment says what is missing`() {
        assertEquals(
            setOf(DraftGap.PRODUCT, DraftGap.PAYMENT),
            gaps(PhraseDraft(PhraseIntent.SALE)),
        )
        assertEquals(
            setOf(DraftGap.PRODUCT, DraftGap.QUANTITY),
            gaps(
                PhraseDraft(
                    PhraseIntent.SALE,
                    lines = listOf(line(product = null), line(quantity = null)),
                    paymentMethodId = 1L,
                ),
            ),
        )
        // Un producto nuevo se crea al registrar, pero necesita nombre y su precio dicho.
        assertEquals(
            setOf(DraftGap.PRODUCT, DraftGap.QUANTITY, DraftGap.PRICE),
            gaps(
                PhraseDraft(
                    PhraseIntent.SALE,
                    lines = listOf(
                        line(product = DraftTarget.New("Zanahoria"), quantity = 0L),
                        line(product = DraftTarget.New(" ")),
                    ),
                    paymentMethodId = 1L,
                ),
            ),
        )
        val carrots = line(product = DraftTarget.New("Zanahoria"))
        val priced = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(carrots.copy(unitPrice = Money(500))),
            paymentMethodId = 1L,
        )
        assertEquals(emptySet<DraftGap>(), gaps(priced))
        val totaled = priced.copy(lines = listOf(carrots.copy(lineTotal = Money(900))))
        assertEquals(emptySet<DraftGap>(), gaps(totaled))
    }

    @Test
    fun `a new product is created where the operation creates it, elsewhere it is a gap`() {
        val carrots = line(product = DraftTarget.New("Zanahoria"))
        val count = PhraseDraft(
            PhraseIntent.STOCK_ADJUSTMENT,
            reason = StockAdjustmentReason.COUNT_CORRECTION,
            lines = listOf(carrots),
        )
        assertEquals(emptySet<DraftGap>(), gaps(count))
        val damaged = count.copy(reason = StockAdjustmentReason.DAMAGED)
        assertEquals(setOf(DraftGap.PRODUCT), gaps(damaged))
        val made = PhraseDraft(PhraseIntent.PRODUCTION, lines = listOf(carrots))
        // Lo nuevo no tiene receta: pide lo que lleva, y con eso se registra.
        assertEquals(setOf(DraftGap.INPUTS), gaps(made))
        assertEquals(
            emptySet<DraftGap>(),
            gaps(made.copy(inputs = listOf(line(product = DraftTarget.New("Harina"))))),
        )
        val purchase = PhraseDraft(
            PhraseIntent.PURCHASE,
            lines = listOf(carrots.copy(unitPrice = Money(100))),
        )
        assertEquals(emptySet<DraftGap>(), gaps(purchase))
        val price = PhraseDraft(
            PhraseIntent.PRICE_CHANGE,
            lines = listOf(carrots.copy(unitPrice = Money(100))),
        )
        assertEquals(setOf(DraftGap.PRODUCT), gaps(price))
    }

    @Test
    fun `a credit sale needs its customer`() {
        val credit = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(line()),
            paymentMethodId = 1L,
            credit = true,
        )
        assertEquals(setOf(DraftGap.PARTY), gaps(credit))
        assertEquals(emptySet<DraftGap>(), gaps(credit.copy(partyId = 7L)))
    }

    @Test
    fun `a purchase needs what each line cost, and a supplier on credit`() {
        val purchase = PhraseDraft(PhraseIntent.PURCHASE, lines = listOf(line()))
        assertEquals(setOf(DraftGap.COST), gaps(purchase))
        assertEquals(
            emptySet<DraftGap>(),
            gaps(purchase.copy(lines = listOf(line(unitPrice = 500L), line(lineTotal = 900L)))),
        )
        val credit = purchase.copy(lines = listOf(line(unitPrice = 500L)), credit = true)
        assertEquals(setOf(DraftGap.PARTY), gaps(credit))
        assertEquals(emptySet<DraftGap>(), gaps(credit.copy(partyId = 3L)))
    }

    @Test
    fun `a purchase total must be the sum of its lines`() {
        val lines = listOf(
            line(quantity = 2_000L, unitPrice = 1_500L),
            line(lineTotal = 4_000L),
        )
        val purchase = PhraseDraft(PhraseIntent.PURCHASE, lines = lines)
        assertEquals(emptySet<DraftGap>(), gaps(purchase.copy(total = Money(7_000))))
        assertEquals(setOf(DraftGap.AMOUNT), gaps(purchase.copy(total = Money(6_999))))
    }

    @Test
    fun `a purchase total is not checked again when a line lacks its amount or quantity`() {
        val unpriced = PhraseDraft(
            PhraseIntent.PURCHASE,
            lines = listOf(line(unitPrice = 500L), line()),
            total = Money(1),
        )
        assertEquals(setOf(DraftGap.COST), gaps(unpriced))
        val unquantified = unpriced.copy(lines = listOf(line(quantity = null, unitPrice = 500L)))
        assertEquals(setOf(DraftGap.QUANTITY), gaps(unquantified))
    }

    @Test
    fun `a purchase total that cannot be added up does not match`() {
        val huge = PhraseDraft(
            PhraseIntent.PURCHASE,
            lines = listOf(line(quantity = 2_000_000_000L, unitPrice = 1_000_000_000_000L)),
            total = Money(1),
        )
        assertEquals(emptySet<DraftGap>(), gaps(huge))
        val overflowing = PhraseDraft(
            PhraseIntent.PURCHASE,
            lines = listOf(line(lineTotal = Long.MAX_VALUE), line(lineTotal = Long.MAX_VALUE)),
            total = Money(1),
        )
        assertEquals(setOf(DraftGap.AMOUNT), gaps(overflowing))
    }

    @Test
    fun `a production needs a product and a quantity`() {
        assertEquals(
            setOf(DraftGap.QUANTITY),
            gaps(PhraseDraft(PhraseIntent.PRODUCTION, lines = listOf(line(quantity = null)))),
        )
        assertEquals(
            emptySet<DraftGap>(),
            gaps(PhraseDraft(PhraseIntent.PRODUCTION, lines = listOf(line()))),
        )
    }

    @Test
    fun `an adjustment needs a reason the phrase can carry`() {
        val adjustment = PhraseDraft(PhraseIntent.STOCK_ADJUSTMENT, lines = listOf(line()))
        assertEquals(setOf(DraftGap.NOTHING), gaps(adjustment))
        assertEquals(
            setOf(DraftGap.NOTHING),
            gaps(adjustment.copy(reason = StockAdjustmentReason.OTHER)),
        )
        assertEquals(
            emptySet<DraftGap>(),
            gaps(adjustment.copy(reason = StockAdjustmentReason.DAMAGED)),
        )
    }

    @Test
    fun `an expense needs a positive amount and a category`() {
        assertEquals(
            setOf(DraftGap.AMOUNT, DraftGap.CATEGORY),
            gaps(PhraseDraft(PhraseIntent.EXPENSE)),
        )
        assertEquals(
            setOf(DraftGap.AMOUNT),
            gaps(PhraseDraft(PhraseIntent.EXPENSE, categoryId = 1L, total = Money.ZERO)),
        )
        assertEquals(
            emptySet<DraftGap>(),
            gaps(PhraseDraft(PhraseIntent.EXPENSE, categoryId = 1L, total = Money(10))),
        )
    }

    @Test
    fun `a payment from a customer or to a supplier needs the person and the amount`() {
        for (intent in listOf(PhraseIntent.CUSTOMER_PAYMENT, PhraseIntent.SUPPLIER_PAYMENT)) {
            assertEquals(setOf(DraftGap.AMOUNT, DraftGap.PARTY), gaps(PhraseDraft(intent)))
            assertEquals(
                emptySet<DraftGap>(),
                gaps(PhraseDraft(intent, partyId = 2L, total = Money(5_000))),
            )
        }
    }

    @Test
    fun `a recipe needs an existing product, its inputs and a positive yield`() {
        // Uno nuevo se crea con la receta; uno sin nombre o sin elegir falta.
        assertEquals(
            setOf(DraftGap.PRODUCT),
            gaps(PhraseDraft(PhraseIntent.RECIPE, target = DraftTarget.New("Torta"))),
        )
        assertEquals(
            setOf(DraftGap.TARGET, DraftGap.PRODUCT),
            gaps(PhraseDraft(PhraseIntent.RECIPE, target = DraftTarget.New(""))),
        )
        assertEquals(
            setOf(DraftGap.TARGET, DraftGap.PRODUCT),
            gaps(PhraseDraft(PhraseIntent.RECIPE)),
        )
        val recipe = PhraseDraft(PhraseIntent.RECIPE, target = bread, lines = listOf(line()))
        assertEquals(emptySet<DraftGap>(), gaps(recipe))
        assertEquals(
            emptySet<DraftGap>(),
            gaps(recipe.copy(targetQuantity = QuantityMilli(10_000))),
        )
        assertEquals(
            setOf(DraftGap.QUANTITY),
            gaps(recipe.copy(targetQuantity = QuantityMilli.ZERO)),
        )
    }

    @Test
    fun `a new product needs its name`() {
        assertEquals(setOf(DraftGap.TARGET), gaps(PhraseDraft(PhraseIntent.NEW_PRODUCT)))
        assertEquals(
            setOf(DraftGap.TARGET),
            gaps(PhraseDraft(PhraseIntent.NEW_PRODUCT, target = DraftTarget.New("  "))),
        )
        assertEquals(
            setOf(DraftGap.TARGET),
            gaps(PhraseDraft(PhraseIntent.NEW_PRODUCT, target = bread)),
        )
        assertEquals(
            emptySet<DraftGap>(),
            gaps(PhraseDraft(PhraseIntent.NEW_PRODUCT, target = DraftTarget.New("Zanahoria"))),
        )
    }

    @Test
    fun `a cost correction needs the product and the cost, not a quantity`() {
        assertEquals(
            setOf(DraftGap.COST),
            gaps(PhraseDraft(PhraseIntent.COST_CORRECTION, lines = listOf(line(quantity = null)))),
        )
        assertEquals(
            emptySet<DraftGap>(),
            gaps(
                PhraseDraft(
                    PhraseIntent.COST_CORRECTION,
                    lines = listOf(line(quantity = null, unitPrice = 400L)),
                ),
            ),
        )
    }

    @Test
    fun `a price change needs the product and the price`() {
        // Sin línea falta el producto; el precio se pide con él.
        assertEquals(setOf(DraftGap.PRODUCT), gaps(PhraseDraft(PhraseIntent.PRICE_CHANGE)))
        assertEquals(
            setOf(DraftGap.PRICE),
            gaps(PhraseDraft(PhraseIntent.PRICE_CHANGE, lines = listOf(line(quantity = null)))),
        )
        assertEquals(
            emptySet<DraftGap>(),
            gaps(
                PhraseDraft(
                    PhraseIntent.PRICE_CHANGE,
                    lines = listOf(line(quantity = null, unitPrice = 600L)),
                ),
            ),
        )
    }
}
