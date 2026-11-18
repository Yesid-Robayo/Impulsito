package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.CardFollowUp
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CASH
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CHOCO_CAKE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COMMA_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CORN_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EMPANADA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.FARMER_CHEESE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MILK
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UTILITIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Lo que hace con la tarjeta abierta una frase que no la corrigió, leída entera con el catálogo de
 * prueba en español: se suma, corrige el producto repetido, pregunta por otra operación o dice que
 * un producto ya existe. Nunca quita una línea.
 */
class PhraseFollowUpTest {
    private fun PhraseDraft.follow(text: String): CardFollowUp = followedBy(
        text,
        PhraseCatalogs.spanish(text),
        PhraseLanguage.ES,
        COMMA_DECIMAL,
        2,
        PhraseCatalogs.spanish,
    )

    private fun PhraseDraft.fix(text: String): PhraseDraft? =
        correctedBy(text, PhraseLanguage.ES, COMMA_DECIMAL, 2, PhraseCatalogs.spanish)

    private fun item(id: Long) = DraftTarget.Existing(id)

    private fun line(
        product: DraftTarget?,
        quantity: QuantityMilli?,
        unit: Long? = null,
        unitPrice: Money? = null,
    ) = DraftLine(product, quantity, unit, unitPrice)

    private fun sale(vararg lines: DraftLine) =
        PhraseDraft(PhraseIntent.SALE, lines = lines.toList())

    private fun purchase(vararg lines: DraftLine) =
        PhraseDraft(PhraseIntent.PURCHASE, lines = lines.toList())

    private fun count(vararg lines: DraftLine) = PhraseDraft(
        PhraseIntent.STOCK_ADJUSTMENT,
        reason = StockAdjustmentReason.COUNT_CORRECTION,
        lines = lines.toList(),
    )

    private fun joined(draft: PhraseDraft) = CardFollowUp.Joined(draft)

    private val bread = line(item(BREAD), units(2))
    private val cheese = line(item(FARMER_CHEESE), units(1), KILOGRAM)

    // La misma operación, con su verbo.

    @Test
    fun `a sale said again adds its lines and its payment`() {
        assertEquals(
            joined(
                sale(bread, line(item(EGG), units(3))).copy(paymentMethodId = CASH),
            ),
            sale(bread).follow("vendí 3 huevos en efectivo"),
        )
    }

    @Test
    fun `a purchase said again adds its lines`() {
        assertEquals(
            joined(
                purchase(
                    line(item(WHEAT_FLOUR), units(2), KILOGRAM),
                    line(item(CORN_FLOUR), units(1), KILOGRAM),
                ),
            ),
            purchase(line(item(WHEAT_FLOUR), units(2), KILOGRAM))
                .follow("compré 1 kilo de harina de maíz"),
        )
    }

    @Test
    fun `a production said again adds what was made`() {
        val production = PhraseDraft(
            PhraseIntent.PRODUCTION,
            lines = listOf(line(item(EMPANADA), units(10))),
        )
        assertEquals(
            joined(production.copy(lines = production.lines + line(item(BREAD), units(5)))),
            production.follow("hice 5 panes"),
        )
    }

    @Test
    fun `a count said again adds its products`() {
        assertEquals(
            joined(count(cheese, line(item(MILK), units(3), null))),
            count(cheese).follow("tengo 3 leches"),
        )
    }

    @Test
    fun `a count said again corrects the product it repeats`() {
        assertEquals(
            joined(count(line(item(FARMER_CHEESE), units(3), KILOGRAM))),
            count(cheese).follow("tengo 3 kilos de queso campesino"),
        )
    }

    @Test
    fun `a count sums only with others`() {
        assertEquals(
            joined(count(line(item(FARMER_CHEESE), units(3), KILOGRAM))),
            count(cheese).follow("y tengo otros 2 kilos de queso campesino"),
        )
    }

    @Test
    fun `a sale sums a repeated product only when it is added`() {
        assertEquals(
            joined(sale(line(item(BREAD), units(5)))),
            sale(bread).follow("y vendí 3 panes"),
        )
        assertEquals(joined(sale(line(item(BREAD), units(3)))), sale(bread).follow("vendí 3 panes"))
    }

    @Test
    fun `a recipe of the same product adds its ingredients`() {
        val recipe = PhraseDraft(
            PhraseIntent.RECIPE,
            lines = listOf(line(item(EGG), units(3))),
            target = item(CHOCO_CAKE),
        )
        val grown = recipe.follow("la torta de chocolate lleva 2 kilos de harina de maíz")
        assertEquals(
            joined(recipe.copy(lines = recipe.lines + line(item(CORN_FLOUR), units(2), KILOGRAM))),
            grown,
        )
        val other = recipe.follow("la torta de vainilla lleva 2 huevos")
        assertEquals(PhraseIntent.RECIPE, (other as CardFollowUp.OtherOperation).draft.intent)
    }

    @Test
    fun `a person said in the same sale corrects it`() {
        assertEquals(
            joined(sale(bread, line(item(EGG), units(1))).copy(partyId = MARIA)),
            sale(bread).follow("vendí un huevo a María Gómez"),
        )
    }

    // Otra operación.

    @Test
    fun `another operation asks before another card`() {
        val asked = purchase(line(item(WHEAT_FLOUR), units(2), KILOGRAM)).follow("vendí 2 panes")
        assertEquals(CardFollowUp.OtherOperation(sale(bread)), asked)
    }

    @Test
    fun `another adjustment reason is another operation`() {
        val damaged = PhraseDraft(
            PhraseIntent.STOCK_ADJUSTMENT,
            reason = StockAdjustmentReason.DAMAGED,
            lines = listOf(bread),
        )
        val asked = damaged.follow("tengo 3 panes")
        assertEquals(
            StockAdjustmentReason.COUNT_CORRECTION,
            (asked as CardFollowUp.OtherOperation).draft.reason,
        )
    }

    @Test
    fun `an operation without lines said again asks`() {
        val expense = PhraseDraft(PhraseIntent.EXPENSE, categoryId = UTILITIES, total = money(80))
        val asked = expense.follow("pagué la luz 50")
        assertEquals(PhraseIntent.EXPENSE, (asked as CardFollowUp.OtherOperation).draft.intent)
    }

    @Test
    fun `no before another operation replaces the card`() {
        val replaced = sale(bread).follow("no, compré 2 huevos y 1 litro de leche")
        assertEquals(PhraseIntent.PURCHASE, (replaced as CardFollowUp.Replaced).draft.intent)
    }

    @Test
    fun `a stated phrase that is not one operation is not understood`() {
        assertEquals(
            CardFollowUp.NotUnderstood,
            sale(bread).follow("vendí 2 panes y compré 3 huevos"),
        )
    }

    // Sin verbo.

    @Test
    fun `without a verb the lines take the operation of the card`() {
        assertEquals(
            joined(count(cheese, line(item(CORN_FLOUR), units(1), KILOGRAM))),
            count(cheese).follow("un kilo de harina de maíz"),
        )
    }

    @Test
    fun `without a verb a person of the other side is left out`() {
        val bought = purchase(line(item(EGG), units(1)))
        val grown = bought.follow("2 huevos a María Gómez")
        assertNull((grown as CardFollowUp.Joined).draft.partyId)
        assertEquals(
            joined(sale(bread, line(item(EGG), units(2))).copy(partyId = MARIA)),
            sale(bread).follow("2 huevos a María Gómez"),
        )
    }

    @Test
    fun `without a verb nothing to read is not understood`() {
        assertEquals(CardFollowUp.NotUnderstood, sale(bread).follow("hola"))
        val expense = PhraseDraft(PhraseIntent.EXPENSE, categoryId = UTILITIES, total = money(80))
        assertEquals(CardFollowUp.NotUnderstood, expense.follow("2 panes"))
    }

    @Test
    fun `a forgotten sale sums the repeated product`() {
        assertEquals(
            joined(sale(line(item(BREAD), units(5)))),
            sale(bread).follow("se me olvidó, vendí 3 panes"),
        )
    }

    @Test
    fun `a verb that does not say the operation takes the one of the card`() {
        assertEquals(
            joined(sale(bread, line(item(EGG), units(3)))),
            sale(bread).follow("recibí 3 huevos"),
        )
    }

    @Test
    fun `the decimals of the money are checked`() {
        for (decimals in listOf(-1, 4)) {
            assertThrows(IllegalArgumentException::class.java) {
                sale(bread).followedBy(
                    "vendí 2 panes",
                    PhraseReading(intent = null),
                    PhraseLanguage.ES,
                    COMMA_DECIMAL,
                    decimals,
                    PhraseCatalogs.spanish,
                )
            }
        }
    }

    // Lo que es un producto que ya existe.

    @Test
    fun `the type of a product that exists keeps the card`() {
        assertEquals(
            CardFollowUp.KnownProduct(BREAD),
            count(cheese).follow("el pan es para vender"),
        )
        assertEquals(
            CardFollowUp.KnownProduct(FARMER_CHEESE),
            count(cheese).follow("es un insumo"),
        )
        assertEquals(
            CardFollowUp.KnownProduct(FARMER_CHEESE),
            count(cheese).follow("el queso campesino, 2 kilos, es un insumo"),
        )
        assertEquals(CardFollowUp.KnownProduct(BREAD), sale(bread).follow("el pan era para vender"))
    }

    @Test
    fun `among similar products the one on the card is described`() {
        val flour = purchase(line(item(WHEAT_FLOUR), units(2), KILOGRAM))
        assertEquals(CardFollowUp.KnownProduct(WHEAT_FLOUR), flour.follow("la harina es un insumo"))
        assertEquals(CardFollowUp.NotUnderstood, sale(bread).follow("la harina es un insumo"))
    }

    @Test
    fun `the product of a recipe is described too`() {
        val recipe = PhraseDraft(PhraseIntent.RECIPE, target = item(CHOCO_CAKE))
        assertEquals(CardFollowUp.KnownProduct(CHOCO_CAKE), recipe.follow("es para vender"))
    }

    @Test
    fun `a product that does not exist is not understood`() {
        assertEquals(CardFollowUp.NotUnderstood, sale(bread).follow("el ajo es un insumo"))
        assertEquals(CardFollowUp.NotUnderstood, sale().follow("es un insumo"))
    }

    @Test
    fun `the reading given is the one followed`() {
        val nothing = PhraseReading(intent = null)
        val stated = sale(bread).followedBy(
            "vendí 2 panes",
            nothing,
            PhraseLanguage.ES,
            COMMA_DECIMAL,
            2,
            PhraseCatalogs.spanish,
        )
        assertEquals(CardFollowUp.NotUnderstood, stated)
    }

    // Lo que se corrige sin leer la frase entera.

    @Test
    fun `a product without a verb is one more line and never replaces the last`() {
        assertEquals(
            count(cheese, line(item(CORN_FLOUR), units(1), KILOGRAM)),
            count(cheese).fix("un kilo de harina de maíz"),
        )
        assertEquals(
            sale(bread, line(item(EGG), null, unitPrice = money(6))),
            sale(bread).fix("el huevo a 6"),
        )
    }

    @Test
    fun `others sums the bread already on the sale`() {
        assertEquals(sale(line(item(BREAD), units(4))), sale(bread).fix("y otros 2 panes"))
        assertEquals(sale(line(item(BREAD), units(4))), sale(bread).fix("2 panes más"))
    }

    @Test
    fun `and with a product already counted corrects it`() {
        assertEquals(
            count(line(item(FARMER_CHEESE), units(3), KILOGRAM)),
            count(cheese).fix("y 3 kilos de queso campesino"),
        )
        assertEquals(
            count(line(item(FARMER_CHEESE), units(3), KILOGRAM)),
            count(cheese).fix("y otros 2 kilos de queso campesino"),
        )
    }

    @Test
    fun `no or was still change the last line`() {
        assertEquals(sale(line(item(EMPANADA), units(3))), sale(bread).fix("no, 3 empanadas"))
        assertEquals(sale(line(item(EMPANADA), units(3))), sale(bread).fix("eran 3 empanadas"))
    }

    @Test
    fun `a product being chosen takes the one said`() {
        val choosing = purchase(
            DraftLine(null, units(2), KILOGRAM, candidates = listOf(WHEAT_FLOUR, CORN_FLOUR)),
        )
        assertEquals(
            purchase(line(item(CORN_FLOUR), units(3), KILOGRAM)),
            choosing.fix("3 kilos de harina de maíz"),
        )
    }

    @Test
    fun `a verb of another operation without no is not a correction`() {
        assertNull(purchase(line(item(EGG), units(1))).fix("vendí"))
    }

    @Test
    fun `the verb of the same operation alone keeps the card`() {
        assertEquals(sale(bread), sale(bread).fix("vendí"))
    }

    @Test
    fun `no it was a sale turns a count into a sale with its lines`() {
        assertEquals(sale(cheese), count(cheese).fix("no, fue una venta"))
    }
}
