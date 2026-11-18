package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CHOCO_CAKE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COOKIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.GRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.LITER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MILK
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RAMIRO
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RENT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.TRANSPORT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UNIT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UTILITIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.english
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.portuguese
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lecturas que antes salían con confianza y equivocadas: otra operación, otro producto u otro
 * monto. Cada una ahora lee lo que se dijo, o deja un hueco para preguntar.
 */
class PhraseConfidentReadsTest {
    private fun line(
        product: Long,
        quantity: QuantityMilli? = null,
        unit: Long? = null,
        unitPrice: Money? = null,
        lineTotal: Money? = null,
    ) = PhraseLine(ProductMatch.Found(product), quantity, unit, unitPrice, lineTotal)

    private fun count(vararg lines: PhraseLine) = PhraseReading(
        intent = PhraseIntent.STOCK_ADJUSTMENT,
        reason = StockAdjustmentReason.COUNT_CORRECTION,
        lines = lines.toList(),
    )

    private val nothing =
        PhraseReading(intent = null, issues = setOf(PhraseIssue.NOTHING_UNDERSTOOD))

    @Test
    fun `whoever buys in the third person is a customer`() {
        val she = english("she bought 3 packs of cookies")
        assertEquals(PhraseIntent.SALE, she.intent)
        assertEquals(listOf(ProductMatch.Found(COOKIES)), she.lines.map { it.product })
        val ela = portuguese("ela comprou 2 ovos")
        assertEquals(PhraseIntent.SALE, ela.intent)
        assertEquals(listOf(line(EGG, units(2))), ela.lines)
        assertEquals(PhraseIntent.SALE, spanish("ella compró 3 galletas").intent)
        // Quien compra para el negocio sigue siendo una compra.
        assertEquals(PhraseIntent.PURCHASE, english("we bought 2 packs of cookies").intent)
    }

    @Test
    fun `what an expense category cost is an expense, and a product keeps its cost`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(TRANSPORT),
                total = money(40),
            ),
            english("the freight from the port cost me 40"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(TRANSPORT),
                total = money(15_000),
            ),
            spanish("el taxi del centro me costó 15 mil"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.COST_CORRECTION,
                lines = listOf(line(COOKIES, unitPrice = money(2))),
            ),
            english("the cookies cost me 2"),
        )
    }

    @Test
    fun `a category word inside a product name is the product`() {
        val water = PhraseCatalogs.spanish.copy(
            products = PhraseCatalogs.spanish.products + PhraseProduct(
                id = BOTTLED_WATER,
                name = "Agua en botella",
                baseUnitId = UNIT,
                conversions = emptyList(),
                forSale = true,
                forPurchase = true,
                hasRecipe = false,
                stockMode = ProductStockMode.TRACKED,
            ),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRICE_CHANGE,
                lines = listOf(line(BOTTLED_WATER, unitPrice = money(2_500))),
            ),
            spanish("el agua en botella es 2.500", water),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(BOTTLED_WATER, lineTotal = money(12_000))),
            ),
            spanish("compré agua en botella por 12 mil", water),
        )
        // Dicha sola, la palabra sigue siendo el servicio.
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(UTILITIES),
                total = money(30_000),
            ),
            spanish("pagué el agua, 30 mil", water),
        )
    }

    @Test
    fun `a wholesale price is not the price, but a wholesale cost is a cost`() {
        assertEquals(nothing, english("at wholesale the cookies go for 1.50"))
        assertEquals(nothing, portuguese("no atacado o pão custa 0,40"))
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.COST_CORRECTION,
                lines = listOf(line(COOKIES, unitPrice = Money(120))),
            ),
            english("the cookies cost me 1.20 wholesale"),
        )
    }

    @Test
    fun `a person said to be a supplier is paid as one`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SUPPLIER_PAYMENT,
                party = NameMatch.Unknown("Peter"),
                total = money(45),
            ),
            english("paid 45 to Peter, my supplier"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SUPPLIER_PAYMENT,
                party = NameMatch.Unknown("Pedro"),
                total = money(70),
            ),
            portuguese("paguei 70 ao Pedro, nosso fornecedor"),
        )
        // Sin nombre, se pregunta a cuál.
        assertEquals(
            PhraseReading(intent = PhraseIntent.SUPPLIER_PAYMENT, total = money(45)),
            english("paid the supplier 45"),
        )
    }

    @Test
    fun `a supplier whose name says supplier keeps the name`() {
        val named = PhraseCatalogs.spanish.copy(
            suppliers = PhraseCatalogs.spanish.suppliers +
                PhraseName(STORE_SUPPLIER, null, "El Proveedor"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SUPPLIER_PAYMENT,
                party = NameMatch.Found(STORE_SUPPLIER),
                total = money(30_000),
            ),
            spanish("le pagué 30 mil a El Proveedor", named),
        )
    }

    @Test
    fun `a recipe's inputs start after what joins them to the product`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                lines = listOf(line(EGG, units(3)), line(MILK, units(1), LITER)),
                target = ProductMatch.Found(CHOCO_CAKE),
            ),
            english("the chocolate cake recipe is 3 eggs and 1 liter of whole milk"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                lines = listOf(line(EGG, units(4))),
                target = ProductMatch.Found(CHOCO_CAKE),
            ),
            english("for a chocolate cake we need 4 eggs"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                lines = listOf(line(WHEAT_FLOUR, units(500), GRAM)),
                target = ProductMatch.Found(BREAD),
            ),
            portuguese("para o pão eu preciso de 500 gramas de farinha de trigo"),
        )
        // Un artículo antes de un insumo dice uno: no sale.
        assertEquals(
            listOf(line(EGG, units(1))),
            english("the chocolate cake takes an egg").lines,
        )
    }

    @Test
    fun `a count opened by a setter takes the number as what there is`() {
        assertEquals(count(line(COOKIES, units(15))), english("set the cookies stock to 15"))
        assertEquals(
            count(line(MILK, units(8), LITER)),
            english("adjust the whole milk stock to 8 liters"),
        )
        assertEquals(count(line(BREAD, units(40))), portuguese("ajusta o estoque do pão para 40"))
        assertEquals(count(line(COOKIES, units(12))), spanish("ajusta el stock de galletas a 12"))
        // Una cifra antes de la marca ya es la cantidad: el monto de después no la reemplaza.
        assertEquals(
            listOf(line(COOKIES, units(9))),
            spanish("quedan 9 galletas a 2.000").lines,
        )
        // Sin verbo de fijar, la cifra dicha al principio o después de la marca también cuenta.
        assertEquals(count(line(COOKIES, units(14))), english("14 cookies left"))
        assertEquals(count(line(COOKIES, units(6))), english("cookies at 6 remaining"))
    }

    @Test
    fun `registering a product reads its cost and its price`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Mint tea"),
                price = money(2),
                cost = money(1),
            ),
            english("register the product mint tea, costs me 1 and I sell it at 2"),
        )
    }

    @Test
    fun `going for says a price`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRICE_CHANGE,
                lines = listOf(line(MILK, unitPrice = Money(110))),
            ),
            english("whole milk is going for 1.10"),
        )
    }

    @Test
    fun `what something costs to make is its cost, not its recipe`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.COST_CORRECTION,
                lines = listOf(line(CHOCO_CAKE, unitPrice = money(9))),
            ),
            english("the chocolate cake costs me 9 to make"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.COST_CORRECTION,
                lines = listOf(line(BREAD, unitPrice = money(1))),
            ),
            portuguese("o pão me custa 1 pra fazer"),
        )
        // Con lo que lleva, sigue siendo una receta.
        assertEquals(
            PhraseIntent.RECIPE,
            english("to make a chocolate cake I use 3 eggs").intent,
        )
        assertEquals(
            PhraseIntent.RECIPE,
            english("the chocolate cake takes 3 eggs and costs me 9").intent,
        )
    }

    @Test
    fun `a supplier who got paid was paid by me`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SUPPLIER_PAYMENT,
                party = NameMatch.Found(RAMIRO),
                total = money(75),
            ),
            english("Mr. Ramiro got paid 75"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SUPPLIER_PAYMENT,
                party = NameMatch.Found(RAMIRO),
                total = money(60),
            ),
            portuguese("Seu Ramiro foi pago, 60"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(RENT),
                total = money(500),
            ),
            english("the rent was paid, 500"),
        )
    }

    private companion object {
        const val BOTTLED_WATER = 900L
        const val STORE_SUPPLIER = 950L
    }
}
