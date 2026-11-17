package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CORN_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EMPANADA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.FARMER_CHEESE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.LITER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MANGO
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MILK
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ORANGE_JUICE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RAMIRO
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RENT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_BIG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_SMALL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Test

/** Casos de borde: lo que se dice a medias, de más o en un orden poco común. */
class PhraseEdgeTest {
    @Test
    fun `a one letter unit without a number is a word of the name`() {
        assertEquals(
            listOf(PhraseLine(ProductMatch.Unknown("G de queso"), null, null, null, null)),
            spanish("vendí g de queso").lines,
        )
    }

    @Test
    fun `a measured unit does not convert to a base unit without its own measure`() {
        assertEquals(
            setOf(PhraseIssue.UNIT_NOT_FOR_PRODUCT),
            spanish("vendí 2 kilos de cacao").issues,
        )
    }

    @Test
    fun `a new name takes at most three words`() {
        assertEquals(
            NameMatch.Unknown("Juan Pablo Pérez"),
            spanish("vendí pan a Juan Pablo Pérez Gómez").party,
        )
    }

    @Test
    fun `a line with only a unit is not an amount`() {
        assertEquals(null, spanish("vendí 3 panes y una docena").total)
    }

    @Test
    fun `the amount of a line without a product, from what was said`() {
        assertEquals(money(5000), spanish("vendí 3 panes, 5000 pesos").total)
        assertEquals(money(500), spanish("vendí 3 panes, a 500").total)
        assertEquals(null, spanish("vendí 3 panes, el").total)
    }

    @Test
    fun `an expense with a total said`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(RENT),
                total = money(5000),
            ),
            spanish("pagué el arriendo, en total 5000"),
        )
    }

    @Test
    fun `a price change with a comma or without an amount`() {
        assertEquals(
            listOf(PhraseLine(ProductMatch.Found(BREAD), null, null, money(600), null)),
            spanish("el pan, ahora vale 600").lines,
        )
        assertEquals(
            listOf(PhraseLine(ProductMatch.Found(BREAD), null, null, null, null)),
            spanish("sube el pan").lines,
        )
    }

    @Test
    fun `a new product with leads, units and extra words`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Zanahoria"),
                price = money(1500),
                unitId = KILOGRAM,
            ),
            spanish("crear producto zanahoria a 1500 el kilo fresca"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Vela de canela"),
            ),
            spanish("agrega: producto llamado vela de canela"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Zanahoria"),
                price = money(1500),
                unitId = KILOGRAM,
            ),
            spanish("crear producto zanahoria por kilo a 1500"),
        )
        assertEquals(
            ProductMatch.Unknown("Pan de la casa"),
            spanish("crear producto pan de la casa a 1500").target,
        )
    }

    @Test
    fun `a price per unit right after the product`() {
        assertEquals(
            listOf(
                PhraseLine(ProductMatch.Found(FARMER_CHEESE), null, KILOGRAM, money(20000), null),
            ),
            spanish("vendí queso campesino el kilo a 20000").lines,
        )
    }

    @Test
    fun `the closest name wins over one with an error`() {
        assertEquals(
            listOf(PhraseLine(ProductMatch.Found(MANGO), units(2), null, null, null)),
            spanish("vendí 2 mangos").lines,
        )
    }

    @Test
    fun `a size word alone is the name`() {
        assertEquals(
            listOf(PhraseLine(ProductMatch.Unknown("Grande"), units(2), null, null, null)),
            spanish("vendí 2 grandes").lines,
        )
    }

    @Test
    fun `a unit with its fraction and no number can be part of a name`() {
        assertEquals(
            listOf(PhraseLine(ProductMatch.Found(SODA_BIG), units(1), null, null, null)),
            spanish("vendí una gaseosa de litro y medio").lines,
        )
        assertEquals(
            listOf(
                PhraseLine(
                    ProductMatch.Found(FARMER_CHEESE),
                    QuantityMilli(1_500),
                    KILOGRAM,
                    null,
                    null,
                ),
            ),
            spanish("vendí queso campesino kilo y medio").lines,
        )
        assertEquals(
            listOf(
                PhraseLine(ProductMatch.Unknown("Gaseosa caja y media"), null, null, null, null),
            ),
            spanish("vendí gaseosa caja y media").lines,
        )
    }

    @Test
    fun `naming only what goes after de is not the product`() {
        assertEquals(
            listOf(PhraseLine(ProductMatch.Unknown("Naranja"), units(3), null, null, null)),
            spanish("vendí 3 naranjas").lines,
        )
    }

    @Test
    fun `a number right after a product starts another line`() {
        assertEquals(
            listOf(
                PhraseLine(ProductMatch.Found(BREAD), units(3), null, null, null),
                PhraseLine(ProductMatch.Found(EMPANADA), units(2), null, null, null),
            ),
            spanish("tres panes dos empanadas").lines,
        )
        assertEquals(
            listOf(PhraseLine(ProductMatch.Found(BREAD), units(2), null, null, money(1500))),
            spanish("vendí 1500 por 2 panes").lines,
        )
        assertEquals(emptyList<PhraseLine>(), spanish("vendí 1500 2 panes").lines)
        assertEquals(
            listOf(PhraseLine(ProductMatch.Found(EGG), units(4), null, money(800), null)),
            spanish("vendí 4 huevos 800 c/u").lines,
        )
    }

    @Test
    fun `a question records nothing`() {
        assertEquals(
            PhraseReading(intent = null, issues = setOf(PhraseIssue.NOTHING_UNDERSTOOD)),
            spanish("cuánto vendí hoy"),
        )
    }

    @Test
    fun `a recipe said by what it yields`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                target = ProductMatch.Found(EMPANADA),
                targetQuantity = units(20),
                lines = listOf(
                    PhraseLine(ProductMatch.Found(CORN_FLOUR), units(1), KILOGRAM, null, null),
                ),
            ),
            spanish("con 1 kilo de harina de maíz me salen 20 empanadas"),
        )
        assertEquals(PhraseIntent.COST_CORRECTION, spanish("las empanadas me salen a 500").intent)
    }

    @Test
    fun `a measure after a comma belongs to the product before`() {
        assertEquals(
            listOf(PhraseLine(ProductMatch.Found(MILK), units(2), LITER, null, null)),
            spanish("se venció la leche, como 2 litros").lines,
        )
        assertEquals(money(5000), spanish("vendí 5000 y 2 kilos").total)
    }

    @Test
    fun `a line that only says the complement takes the head of the one before`() {
        assertEquals(
            listOf(
                PhraseLine(ProductMatch.Found(ORANGE_JUICE), units(2), null, null, null),
                PhraseLine(ProductMatch.Unknown("Jugo de piña"), units(1), null, null, null),
                PhraseLine(ProductMatch.Unknown("Jugo de lulo"), units(1), null, null, null),
            ),
            spanish("vendí 2 jugos de naranja, 1 de piña y 1 de lulo").lines,
        )
        assertEquals(
            listOf(
                PhraseLine(ProductMatch.Found(EMPANADA), units(2), null, null, null),
                PhraseLine(ProductMatch.Unknown("Empanada de pollo"), units(3), null, null, null),
            ),
            spanish("vendí 2 empanadas y 3 de pollo").lines,
        )
        assertEquals(
            PhraseLine(ProductMatch.Found(MANGO), units(3), null, null, null),
            spanish("vendí 2 jugos de naranja y 3 de mango").lines.last(),
        )
    }

    @Test
    fun `a word that only replaces the name takes the one before`() {
        assertEquals(
            listOf(
                PhraseLine(ProductMatch.Found(SODA_SMALL), units(2), null, null, null),
                PhraseLine(ProductMatch.Found(SODA_BIG), units(1), null, null, null),
            ),
            PhraseCatalogs.english("sold 2 small sodas and a big one").lines,
        )
        assertEquals(
            listOf(PhraseLine(ProductMatch.Unknown("One"), null, null, null, null)),
            PhraseCatalogs.english("sold the ones").lines,
        )
    }

    @Test
    fun `an unknown word before the list is not a product`() {
        assertEquals(
            listOf(PhraseLine(ProductMatch.Found(BREAD), units(3), null, null, null)),
            spanish("vendí ayyy 3 panes").lines,
        )
        assertEquals(
            PhraseReading(intent = null, issues = setOf(PhraseIssue.NOTHING_UNDERSTOOD)),
            spanish("zumbido 3 panes"),
        )
        assertEquals(
            listOf(PhraseLine(ProductMatch.Found(BREAD), units(3), null, null, null)),
            spanish("vendí el, 3 panes").lines,
        )
    }

    @Test
    fun `a doubled preposition before a supplier goes with it`() {
        val reading = spanish("compré 2 huevos de donde Don Ramiro")
        assertEquals(NameMatch.Found(RAMIRO), reading.party)
        assertEquals(
            listOf(PhraseLine(ProductMatch.Found(EGG), units(2), null, null, null)),
            reading.lines,
        )
    }

    @Test
    fun `a payment verb with a category does not join a purchase`() {
        assertEquals(
            PhraseReading(intent = null, issues = setOf(PhraseIssue.MIXED_OPERATIONS)),
            spanish("compré 2 huevos y pagué la luz"),
        )
    }

    @Test
    fun `took is a sale when a customer is named, and alone it is not clear`() {
        assertEquals(PhraseIntent.SALE, spanish("Carlos llevó 3 panes").intent)
        assertEquals(setOf(PhraseIssue.NOTHING_UNDERSTOOD), spanish("llevó 3 panes").issues)
        assertEquals(setOf(PhraseIssue.NOTHING_UNDERSTOOD), spanish("saqué 3 panes").issues)
        assertEquals(StockAdjustmentReason.OWN_USE, spanish("saqué 3 panes para la casa").reason)
    }

    @Test
    fun `receiving is a purchase with products and a payment from a customer`() {
        assertEquals(PhraseIntent.PURCHASE, spanish("recibí 3 huevos de Don Ramiro").intent)
        assertEquals(PhraseIntent.CUSTOMER_PAYMENT, spanish("recibí 5000 de Carlos").intent)
        assertEquals(setOf(PhraseIssue.NOTHING_UNDERSTOOD), spanish("recibí 5000").issues)
    }
}
