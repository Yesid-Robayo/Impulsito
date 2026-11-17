package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CHOCO_CAKE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CORN_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EMPANADA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.FARMER_CHEESE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.GRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.HAIRCUT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.JAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.LITER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MOZZARELLA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_BIG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_SMALL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseCatalogEditTest {
    private fun line(product: ProductMatch, quantity: QuantityMilli? = null, unit: Long? = null) =
        PhraseLine(product, quantity, unit, null, null)

    private fun priced(product: ProductMatch, unit: Long? = null, price: Long) =
        PhraseLine(product, null, unit, money(price), null)

    @Test
    fun `a recipe with the product before lleva`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                target = ProductMatch.Found(CHOCO_CAKE),
                lines = listOf(
                    line(ProductMatch.Found(WHEAT_FLOUR), units(200), GRAM),
                    line(ProductMatch.Found(EGG), units(3)),
                    line(ProductMatch.Unknown("Azúcar"), units(100), GRAM),
                ),
            ),
            spanish(
                "la torta de chocolate lleva 200 gramos de harina de trigo, 3 huevos " +
                    "y 100 gramos de azúcar",
            ),
        )
    }

    @Test
    fun `a recipe with what it yields`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                target = ProductMatch.Found(EMPANADA),
                targetQuantity = units(10),
                lines = listOf(
                    line(ProductMatch.Found(CORN_FLOUR), units(1), KILOGRAM),
                    line(ProductMatch.Found(EGG), units(2)),
                ),
            ),
            spanish("para hacer 10 empanadas uso 1 kilo de harina de maíz y 2 huevos"),
        )
    }

    @Test
    fun `a recipe introduced by its name and a colon`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                target = ProductMatch.Found(BREAD),
                lines = listOf(line(ProductMatch.Found(WHEAT_FLOUR), units(50), GRAM)),
            ),
            spanish("receta del pan: 50 gramos de harina de trigo"),
        )
    }

    @Test
    fun `a recipe with the ingredients before the product`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                target = ProductMatch.Found(JAM),
                targetQuantity = units(4),
                lines = listOf(line(ProductMatch.Found(EGG), units(2))),
            ),
            spanish("uso 2 huevos para hacer 4 mermeladas"),
        )
    }

    @Test
    fun `a recipe for a new product, and one without ingredients`() {
        assertEquals(ProductMatch.Unknown("Arepa"), spanish("a la arepa le echo 2 huevos").target)
        assertEquals(
            PhraseReading(intent = PhraseIntent.RECIPE, target = ProductMatch.Found(HAIRCUT)),
            spanish("receta del corte de cabello"),
        )
        assertEquals(PhraseReading(intent = PhraseIntent.RECIPE), spanish("lleva"))
    }

    @Test
    fun `other verbs inside a recipe are left out`() {
        assertEquals(
            listOf(
                line(ProductMatch.Found(EGG), units(2)),
                line(ProductMatch.Found(FARMER_CHEESE)),
            ),
            spanish("la empanada lleva 2 huevos y uso queso campesino").lines,
        )
    }

    @Test
    fun `a recipe flags a unit the ingredient does not admit`() {
        assertEquals(
            setOf(PhraseIssue.UNIT_NOT_FOR_PRODUCT),
            spanish("la empanada lleva 2 litros de huevo").issues,
        )
    }

    @Test
    fun `a new product with price, cost and unit`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Zanahoria"),
                price = money(1500),
                unitId = KILOGRAM,
            ),
            spanish("crear producto zanahoria a 1500 el kilo"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Vela de canela"),
                price = money(15000),
                cost = money(6000),
            ),
            spanish("nuevo producto vela de canela, precio 15000, costo 6000"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Galleta de avena"),
                price = money(3000),
                cost = money(1000),
                unitId = LITER,
            ),
            spanish("agrega galletas de avena 3000 1000 por litro"),
        )
    }

    @Test
    fun `a new product that already exists is flagged`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Found(BREAD),
                issues = setOf(PhraseIssue.ALREADY_EXISTS),
            ),
            spanish("crear producto pan"),
        )
        assertEquals(ProductMatch.Unknown("Harina"), spanish("crear producto harina").target)
        assertEquals(PhraseReading(intent = PhraseIntent.NEW_PRODUCT), spanish("nuevo producto"))
    }

    @Test
    fun `a new cost of a product with its own stock`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.COST_CORRECTION,
                lines = listOf(priced(ProductMatch.Found(BREAD), price = 400)),
            ),
            spanish("el costo del pan ahora es 400"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.COST_CORRECTION,
                lines = listOf(
                    priced(ProductMatch.Choose(listOf(WHEAT_FLOUR, CORN_FLOUR)), KILOGRAM, 4500),
                ),
            ),
            spanish("la harina me sale a 4500 el kilo"),
        )
        assertEquals(
            listOf(priced(ProductMatch.Choose(listOf(FARMER_CHEESE, MOZZARELLA)), price = 18000)),
            spanish("corrige el costo del queso a 18000").lines,
        )
    }

    @Test
    fun `a new cost of something without stock is flagged`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.COST_CORRECTION,
                lines = listOf(priced(ProductMatch.Found(HAIRCUT), price = 5000)),
                issues = setOf(PhraseIssue.NO_STOCK),
            ),
            spanish("el costo del corte de cabello es 5000"),
        )
    }

    @Test
    fun `a new price`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRICE_CHANGE,
                lines = listOf(priced(ProductMatch.Found(BREAD), price = 600)),
            ),
            spanish("el pan ahora vale 600"),
        )
        assertEquals(
            listOf(priced(ProductMatch.Choose(listOf(SODA_SMALL, SODA_BIG)), price = 2500)),
            spanish("sube la gaseosa a 2500").lines,
        )
        assertEquals(PhraseReading(intent = PhraseIntent.PRICE_CHANGE), spanish("sube a 2500"))
    }

    @Test
    fun `a price word after a purchase or a sale verb does not change a price`() {
        assertEquals(PhraseIntent.PURCHASE, spanish("compré harina a 4500").intent)
        assertEquals(PhraseIntent.SALE, spanish("vendí el pan a 600, el precio de siempre").intent)
    }
}
