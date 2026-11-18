package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseConversion
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhrasePrices
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhraseEstimatesTest {
    private fun product(id: Long, base: Long, vararg conversions: PhraseConversion) = PhraseProduct(
        id = id,
        name = "Producto $id",
        baseUnitId = base,
        conversions = conversions.toList(),
        forSale = true,
        forPurchase = true,
        hasRecipe = false,
        stockMode = ProductStockMode.TRACKED,
    )

    private val catalog = PhraseCatalog(
        products = listOf(
            product(BREAD, UNIT, PhraseConversion(BOX, 12_000)),
            product(CHEESE, KILOGRAM),
            product(SODA, UNIT, PhraseConversion(BOX, 6_000)),
            product(LOST_BASE, 999L),
            product(UNPRICED, UNIT),
            product(UNPRICED_CHEESE, KILOGRAM),
        ),
        units = listOf(
            PhraseUnit(UNIT, "unit", "Unidad", null, UnitKind.COUNT, null),
            PhraseUnit(KILOGRAM, "kilogram", "Kilogramo", "kg", UnitKind.MASS, 1_000_000L),
            PhraseUnit(GRAM, "gram", "Gramo", "g", UnitKind.MASS, 1_000L),
            PhraseUnit(LITER, "liter", "Litro", "L", UnitKind.VOLUME, 1_000_000L),
            PhraseUnit(BOX, "box", "Caja", null, UnitKind.COUNT, null),
            PhraseUnit(POUND, "pound", "Libra", "lb", UnitKind.MASS, null),
        ),
        paymentMethods = emptyList(),
        suppliers = emptyList(),
        customers = emptyList(),
        expenseCategories = emptyList(),
    )

    private val prices = PhrasePrices(
        sale = mapOf(
            BREAD to Money(500),
            CHEESE to Money(18_500),
            SODA to Money(2_000),
            LOST_BASE to Money(100),
        ),
        saleByUnit = mapOf((SODA to BOX) to Money(11_000)),
        lastCost = mapOf(CHEESE to Money(12_000)),
    )

    private fun line(
        product: DraftTarget?,
        quantity: Long?,
        unitId: Long? = null,
        unitPrice: Long? = null,
        lineTotal: Long? = null,
    ) = DraftLine(
        product = product,
        quantity = quantity?.let(::QuantityMilli),
        unitId = unitId,
        unitPrice = unitPrice?.let(::Money),
        lineTotal = lineTotal?.let(::Money),
    )

    private fun of(id: Long) = DraftTarget.Existing(id)

    private fun amounts(intent: PhraseIntent, vararg lines: DraftLine): List<Money?> =
        PhraseDraft(intent, lines = lines.toList()).estimatedLineAmounts(prices, catalog)

    @Test
    fun `what was said is the amount of the line`() {
        assertEquals(
            listOf(Money(1_000), Money(900)),
            amounts(
                PhraseIntent.SALE,
                line(of(BREAD), 3_000, lineTotal = 1_000),
                line(of(BREAD), 2_000, unitPrice = 450),
            ),
        )
    }

    @Test
    fun `a sale line of a product without a price asks for one`() {
        val sale = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(
                // Con precio en la base.
                line(of(BREAD), 1_000),
                // Sin precio: lo pide.
                line(of(UNPRICED), 1_000),
                // Sin precio, pero dicho: el total o el precio.
                line(of(UNPRICED), 1_000, lineTotal = 900),
                line(of(UNPRICED), 1_000, unitPrice = 900),
                // Sin precio en la base, en una unidad sin precio propio: lo pide.
                line(of(UNPRICED), 1_000, unitId = BOX),
                // Un producto nuevo o uno por elegir: lo dicen sus propios huecos.
                line(DraftTarget.New("Flan"), 1_000),
                line(null, 1_000),
            ),
        )
        assertEquals(setOf(1, 4), sale.unpricedSaleLines(prices))
        // Sin precio en la base, pero con el de su presentación.
        val boxed = prices.copy(saleByUnit = mapOf((UNPRICED to BOX) to Money(7_000)))
        assertEquals(setOf(1), sale.unpricedSaleLines(boxed))
        // Fuera de una venta, ninguna: una compra pide su costo aparte.
        assertEquals(
            emptySet<Int>(),
            sale.copy(intent = PhraseIntent.PURCHASE).unpricedSaleLines(prices),
        )
    }

    @Test
    fun `a sale line without a price takes the price the sale will use`() {
        assertEquals(
            listOf(
                // 3 panes a 500.
                Money(1_500),
                Money(500),
                // 2 cajas con precio propio de 11000.
                Money(22_000),
                // Una caja de 12 panes que no es presentación de venta: 12 a 500.
                Money(6_000),
                // 500 gramos de un queso que se lleva por kilo: medio kilo a 18500.
                Money(9_250),
            ),
            amounts(
                PhraseIntent.SALE,
                line(of(BREAD), 3_000),
                line(of(BREAD), 1_000, unitId = UNIT),
                line(of(SODA), 2_000, unitId = BOX),
                line(of(BREAD), 1_000, unitId = BOX),
                line(of(CHEESE), 500_000, unitId = GRAM),
            ),
        )
    }

    @Test
    fun `a sale line without a product, a quantity or a known price has no amount`() {
        assertEquals(
            List<Money?>(6) { null },
            amounts(
                PhraseIntent.SALE,
                line(null, 1_000),
                line(DraftTarget.New("Zanahoria"), 1_000),
                line(of(999L), 1_000),
                line(of(BREAD), null),
                line(of(UNPRICED), 1_000),
                line(of(UNPRICED_CHEESE), 500_000, unitId = GRAM),
            ),
        )
    }

    @Test
    fun `a sale line in a unit that does not reach the base unit has no amount`() {
        assertEquals(
            List<Money?>(6) { null },
            amounts(
                PhraseIntent.SALE,
                // Una unidad que no existe, un producto sin su unidad base y una libra sin medida.
                line(of(CHEESE), 1_000, unitId = 999L),
                line(of(LOST_BASE), 1_000, unitId = GRAM),
                line(of(CHEESE), 1_000, unitId = POUND),
                // Un gramo no es una unidad, un litro no es un peso y un miligramo redondea a
                // cero.
                line(of(BREAD), 1_000, unitId = GRAM),
                line(of(CHEESE), 1_000, unitId = LITER),
                line(of(CHEESE), 1, unitId = GRAM),
            ),
        )
    }

    @Test
    fun `an amount above the limit of a document is not shown`() {
        val huge = prices.copy(sale = mapOf(BREAD to Money(1_000_000_000_000)))
        val draft = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(line(of(BREAD), 2_000_000_000)),
        )
        assertEquals(listOf<Money?>(null), draft.estimatedLineAmounts(huge, catalog))
    }

    @Test
    fun `outside a sale only what was said counts`() {
        assertEquals(
            listOf(null, Money(6_000)),
            amounts(
                PhraseIntent.PURCHASE,
                line(of(CHEESE), 1_000),
                line(of(CHEESE), 500, unitPrice = 12_000),
            ),
        )
    }

    @Test
    fun `the total is the said one, or the sum of lines that all have an amount`() {
        val sale = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(line(of(BREAD), 3_000), line(of(SODA), 1_000, unitId = BOX)),
        )
        assertEquals(Money(12_500), sale.estimatedTotal(prices, catalog))
        assertEquals(
            Money(12_000),
            sale.copy(total = Money(12_000)).estimatedTotal(prices, catalog),
        )
        val partial = sale.copy(lines = sale.lines + line(of(UNPRICED), 1_000))
        assertNull(partial.estimatedTotal(prices, catalog))
    }

    @Test
    fun `an expense or a payment shows its amount, and nothing without one`() {
        val expense = PhraseDraft(PhraseIntent.EXPENSE, categoryId = 1L)
        assertNull(expense.estimatedTotal(prices, catalog))
        assertEquals(
            Money(80_000),
            expense.copy(total = Money(80_000)).estimatedTotal(prices, catalog),
        )
    }

    @Test
    fun `a total that does not fit has no amount`() {
        val draft = PhraseDraft(
            PhraseIntent.PURCHASE,
            lines = listOf(
                line(of(CHEESE), 1_000, lineTotal = Long.MAX_VALUE),
                line(of(BREAD), 1_000, lineTotal = Long.MAX_VALUE),
            ),
        )
        assertNull(draft.estimatedTotal(prices, catalog))
    }

    private companion object {
        const val UNIT = 1L
        const val KILOGRAM = 2L
        const val GRAM = 3L
        const val LITER = 4L
        const val BOX = 5L
        const val POUND = 6L

        const val BREAD = 100L
        const val CHEESE = 101L
        const val SODA = 102L
        const val LOST_BASE = 103L
        const val UNPRICED = 104L
        const val UNPRICED_CHEESE = 105L
    }
}
