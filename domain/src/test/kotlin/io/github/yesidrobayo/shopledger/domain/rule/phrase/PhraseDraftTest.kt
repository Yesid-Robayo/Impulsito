package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CASH
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.FARMER_CHEESE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MOZZARELLA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RENT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_BIG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_SMALL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhraseDraftTest {
    private fun line(
        product: ProductMatch,
        quantity: QuantityMilli? = null,
        unit: Long? = null,
        unitPrice: Money? = null,
        lineTotal: Money? = null,
    ) = PhraseLine(product, quantity, unit, unitPrice, lineTotal)

    private fun reading(intent: PhraseIntent?, vararg lines: PhraseLine) =
        PhraseReading(intent = intent, lines = lines.toList())

    private val one = QuantityMilli(1_000)

    @Test
    fun `a reading without an operation has no card`() {
        val mixed = PhraseReading(intent = null, issues = setOf(PhraseIssue.MIXED_OPERATIONS))
        assertNull(mixed.toDraft())
    }

    @Test
    fun `a sale from a phrase keeps what is clear and leaves the choices as gaps`() {
        assertEquals(
            PhraseDraft(
                intent = PhraseIntent.SALE,
                lines = listOf(
                    DraftLine(DraftTarget.Existing(BREAD), units(3), null),
                    DraftLine(null, units(2), null),
                    DraftLine(null, QuantityMilli(500), KILOGRAM),
                ),
                paymentMethodId = CASH,
            ),
            spanish("vendí 3 panes, 2 gaseosas y medio kilo de queso en efectivo").toDraft(),
        )
    }

    @Test
    fun `an expense from a phrase carries its amount and category`() {
        assertEquals(
            PhraseDraft(intent = PhraseIntent.EXPENSE, categoryId = RENT, total = money(800_000)),
            spanish("pagué el arriendo 800000").toDraft(),
        )
    }

    @Test
    fun `a found party and payment pass as ids, and the others stay empty`() {
        val draft = PhraseReading(
            intent = PhraseIntent.SALE,
            lines = listOf(line(ProductMatch.Found(BREAD), units(1))),
            payment = NameMatch.Choose(listOf(CASH, 2L)),
            party = NameMatch.Found(MARIA),
            category = NameMatch.Unknown("Algo"),
            credit = true,
        ).toDraft()
        assertEquals(
            PhraseDraft(
                intent = PhraseIntent.SALE,
                lines = listOf(DraftLine(DraftTarget.Existing(BREAD), units(1), null)),
                paymentMethodId = null,
                partyId = MARIA,
                categoryId = null,
                credit = true,
            ),
            draft,
        )
    }

    @Test
    fun `an unknown product is offered as new in a sale, a purchase and a recipe only`() {
        val unknown = line(ProductMatch.Unknown("Zanahoria"), units(2))
        val offered = listOf(PhraseIntent.SALE, PhraseIntent.PURCHASE, PhraseIntent.RECIPE)
        for (intent in PhraseIntent.entries) {
            val product = reading(intent, unknown).toDraft()?.lines?.single()?.product
            val expected = if (intent in offered) DraftTarget.New("Zanahoria") else null
            assertEquals(intent.name, expected, product)
        }
    }

    @Test
    fun `a missing quantity is one unit where the screens assume one`() {
        val unsaid = line(ProductMatch.Found(BREAD))
        val assumed = setOf(
            PhraseIntent.SALE,
            PhraseIntent.PURCHASE,
            PhraseIntent.PRODUCTION,
            PhraseIntent.STOCK_ADJUSTMENT,
        )
        for (intent in PhraseIntent.entries) {
            val quantity = PhraseReading(
                intent = intent,
                reason = StockAdjustmentReason.DAMAGED.takeIf {
                    intent == PhraseIntent.STOCK_ADJUSTMENT
                },
                lines = listOf(unsaid),
            ).toDraft()?.lines?.single()?.quantity
            assertEquals(intent.name, if (intent in assumed) one else null, quantity)
        }
    }

    @Test
    fun `a count without a number asks for it, because the counted stock is real`() {
        val draft = PhraseReading(
            intent = PhraseIntent.STOCK_ADJUSTMENT,
            reason = StockAdjustmentReason.COUNT_CORRECTION,
            lines = listOf(line(ProductMatch.Found(BREAD))),
        ).toDraft()
        assertNull(draft?.lines?.single()?.quantity)
    }

    @Test
    fun `with an unreadable quantity every missing quantity is asked, and the said ones stay`() {
        val draft = PhraseReading(
            intent = PhraseIntent.SALE,
            lines = listOf(
                line(ProductMatch.Found(BREAD)),
                line(ProductMatch.Found(BREAD), units(2)),
            ),
            issues = setOf(PhraseIssue.UNREADABLE_QUANTITY),
        ).toDraft()
        assertEquals(listOf(null, units(2)), draft?.lines?.map { it.quantity })
    }

    @Test
    fun `a single unpriced line of a sale or a purchase takes the said total`() {
        for (intent in listOf(PhraseIntent.SALE, PhraseIntent.PURCHASE)) {
            val draft = PhraseReading(
                intent = intent,
                lines = listOf(line(ProductMatch.Found(BREAD), units(3))),
                total = money(1_000),
            ).toDraft()
            assertEquals(money(1_000), draft?.lines?.single()?.lineTotal)
            assertEquals(money(1_000), draft?.total)
        }
    }

    @Test
    fun `the total stays apart when the line has its own amount or there are several lines`() {
        val priced = PhraseReading(
            intent = PhraseIntent.SALE,
            lines = listOf(line(ProductMatch.Found(BREAD), units(3), unitPrice = money(500))),
            total = money(1_000),
        ).toDraft()
        assertNull(priced?.lines?.single()?.lineTotal)
        val withTotal = PhraseReading(
            intent = PhraseIntent.PURCHASE,
            lines = listOf(line(ProductMatch.Found(BREAD), units(3), lineTotal = money(900))),
            total = money(1_000),
        ).toDraft()
        assertEquals(money(900), withTotal?.lines?.single()?.lineTotal)
        val several = PhraseReading(
            intent = PhraseIntent.SALE,
            lines = listOf(
                line(ProductMatch.Found(SODA_SMALL), units(1)),
                line(ProductMatch.Found(SODA_BIG), units(1)),
            ),
            total = money(1_000),
        ).toDraft()
        assertEquals(listOf(null, null), several?.lines?.map { it.lineTotal })
        val untotaled = reading(PhraseIntent.SALE, line(ProductMatch.Found(BREAD), units(3)))
        assertNull(untotaled.toDraft()?.lines?.single()?.lineTotal)
    }

    @Test
    fun `outside a sale or a purchase a total is never a line total`() {
        val draft = PhraseReading(
            intent = PhraseIntent.PRODUCTION,
            lines = listOf(line(ProductMatch.Found(BREAD), units(3))),
            total = money(1_000),
        ).toDraft()
        assertNull(draft?.lines?.single()?.lineTotal)
    }

    @Test
    fun `the product of a recipe or a new product can be new, elsewhere it is a gap`() {
        val unknown = ProductMatch.Unknown("Torta de limón")
        assertEquals(
            DraftTarget.New("Torta de limón"),
            PhraseReading(intent = PhraseIntent.RECIPE, target = unknown).toDraft()?.target,
        )
        assertEquals(
            DraftTarget.New("Torta de limón"),
            PhraseReading(intent = PhraseIntent.NEW_PRODUCT, target = unknown).toDraft()?.target,
        )
        val priceChange = PhraseReading(intent = PhraseIntent.PRICE_CHANGE, target = unknown)
        assertNull(priceChange.toDraft()?.target)
        assertEquals(
            DraftTarget.Existing(BREAD),
            PhraseReading(intent = PhraseIntent.RECIPE, target = ProductMatch.Found(BREAD))
                .toDraft()?.target,
        )
        val choice = ProductMatch.Choose(listOf(FARMER_CHEESE, MOZZARELLA))
        assertNull(PhraseReading(intent = PhraseIntent.RECIPE, target = choice).toDraft()?.target)
    }

    @Test
    fun `a new product keeps its price, cost, unit and yield fields as read`() {
        assertEquals(
            PhraseDraft(
                intent = PhraseIntent.NEW_PRODUCT,
                target = DraftTarget.New("Zanahoria"),
                targetQuantity = units(2),
                price = money(1_500),
                cost = money(600),
                unitId = KILOGRAM,
            ),
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Zanahoria"),
                targetQuantity = units(2),
                price = money(1_500),
                cost = money(600),
                unitId = KILOGRAM,
                issues = setOf(PhraseIssue.ALREADY_EXISTS),
            ).toDraft(),
        )
    }
}
