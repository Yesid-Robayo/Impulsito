package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseConversion
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BOX
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CARD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CASH
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CHOCO_CAKE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COOKIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CORN_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EMPANADA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.FARMER_CHEESE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.HAIRCUT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.JAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.LITER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA_T
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MILK
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ON_ACCOUNT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ORANGE_JUICE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POINT_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RAMIRO
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RENT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ROSA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SACK
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_BIG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_SMALL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.TRANSFER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.TRANSPORT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UTILITIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHOLE_BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.english
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.portuguese
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Formas del habla que el intérprete aprendió de la guía de conversación: relleno, presente,
 * dos oraciones, autocorrección, pagos repartidos, el billete con que se pagó, tandas, docenas,
 * envases, y quién compra o vende.
 */
class PhraseSpeechTest {
    private fun line(
        product: ProductMatch,
        quantity: QuantityMilli? = null,
        unit: Long? = null,
        unitPrice: Money? = null,
        lineTotal: Money? = null,
    ) = PhraseLine(product, quantity, unit, unitPrice, lineTotal)

    private fun found(id: Long) = ProductMatch.Found(id)

    private fun sale(vararg lines: PhraseLine) =
        PhraseReading(intent = PhraseIntent.SALE, lines = lines.toList())

    @Test
    fun `a demonstrative says one, and a soft filler alone is skipped`() {
        assertEquals(sale(line(found(WHOLE_BREAD), units(1))), spanish("vendí este pan integral"))
        assertEquals(sale(line(found(BREAD), units(2))), spanish("eh, este, vendí 2 panes"))
        assertEquals(sale(line(found(BREAD), units(2))), spanish("y vendí 2 panes"))
        assertEquals(
            sale(line(found(BREAD), units(1))),
            english("sold this bread roll"),
        )
    }

    @Test
    fun `the present tense is a sale with a quantity and a price without one`() {
        assertEquals(sale(line(found(BREAD), units(3))), spanish("vendo 3 panes"))
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRICE_CHANGE,
                lines = listOf(line(found(BREAD), unitPrice = money(600))),
            ),
            spanish("el pan lo vendo a 600"),
        )
        assertEquals(sale(line(found(BREAD), units(3))), english("sell 3 bread rolls"))
    }

    @Test
    fun `two sentences complete one operation`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(found(CORN_FLOUR), units(10), KILOGRAM)),
                party = NameMatch.Found(RAMIRO),
            ),
            spanish("Compré harina de maíz. Fueron 10 kilos. Se la compré a Don Ramiro."),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRODUCTION,
                lines = listOf(line(found(EMPANADA), units(30))),
            ),
            spanish("Hice empanadas. Fueron 30."),
        )
        // Mil o más después de "fueron" es lo que costó, no cuántos.
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(line(found(BREAD))),
                total = money(3000),
            ),
            spanish("Vendí pan. Fueron 3 mil."),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(line(found(BREAD), units(3)), line(found(EMPANADA), units(2))),
                total = money(6000),
            ),
            spanish("vendí 3 panes y 2 empanadas, son 6 mil"),
        )
    }

    @Test
    fun `what is said after a self fix replaces the last line`() {
        assertEquals(sale(line(found(BREAD), units(4))), spanish("vendí 3 panes, perdón, 4 panes"))
        assertEquals(
            sale(line(found(EMPANADA), units(3))),
            spanish("vendí 3 panes, perdón, empanadas"),
        )
        assertEquals(sale(line(found(BREAD), units(4))), spanish("vendí 3 panes, no, 4"))
        // Lo que sigue al "no" no dice una línea: no es una corrección.
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(found(WHEAT_FLOUR), units(2), KILOGRAM)),
                issues = setOf(PhraseIssue.UNUSED_WORDS),
            ),
            spanish("compré 2 kilos de harina de trigo y esto no sé"),
        )
        assertEquals(sale(line(found(BREAD), units(3))), spanish("vendí 3 panes, perdón"))
        assertEquals(sale(line(found(BREAD), units(4))), english("sold 3 bread rolls, sorry, 4"))
    }

    @Test
    fun `payments split, tendered or said with the verb`() {
        assertEquals(
            NameMatch.Choose(listOf(CASH, CARD)),
            spanish("vendí 2 panes, la mitad en efectivo y la mitad con tarjeta").payment,
        )
        assertEquals(
            NameMatch.Found(CASH),
            spanish("vendí 2 panes en efectivo y 3 empanadas en efectivo").payment,
        )
        assertEquals(
            sale(line(found(BREAD), units(5))).copy(payment = NameMatch.Found(CASH)),
            spanish("vendí 5 panes todo en efectivo"),
        )
        assertEquals(
            sale(line(found(BREAD), units(3))).copy(payment = NameMatch.Found(CASH)),
            spanish("vendí 3 panes, me pagó con un billete de 20 mil"),
        )
        assertEquals(
            sale(line(found(BREAD), units(2))).copy(payment = NameMatch.Found(CASH)),
            english("sold 2 bread rolls, he paid with a 20 bill"),
        )
        // Sin "bill", "with a" en inglés no dice con cuánto pagó.
        assertNull(english("sold 2 bread rolls, paid with a 20").payment)
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.CUSTOMER_PAYMENT,
                payment = NameMatch.Found(TRANSFER),
                party = NameMatch.Found(MARIA_T),
                total = money(25_000),
            ),
            spanish("María Torres me transfirió 25 mil"),
        )
        // "20 de efectivo" cuenta algo, no dice cómo se pagó.
        assertNull(spanish("vendí 3 panes, 20 de efectivo").payment)
    }

    @Test
    fun `a credit form can carry the person inside`() {
        val reading = english("sold 2 bread rolls on Mary Smith's tab")
        assertEquals(true, reading.credit)
        assertEquals(NameMatch.Found(MARIA), reading.party)
        assertEquals(NameMatch.Found(ON_ACCOUNT), reading.payment)
        // "on my tab" ya es una forma de crédito, sin persona dentro.
        assertNull(english("sold 2 bread rolls on my tab").party)
        assertEquals(true, english("sold 2 bread rolls on my tab").credit)
    }

    @Test
    fun `someone unnamed took something, and a known person is who buys or sells`() {
        assertEquals(sale(line(found(BREAD), units(2))), english("a customer took 2 bread rolls"))
        assertEquals(
            sale(line(found(BREAD), units(3))).copy(party = NameMatch.Found(ROSA)),
            english("Miss Rose bought 3 bread rolls"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(found(WHEAT_FLOUR), units(2), SACK)),
                party = NameMatch.Found(RAMIRO),
            ),
            spanish("Don Ramiro vendió 2 bultos de harina de trigo"),
        )
        assertEquals(
            sale(line(found(BREAD), units(2))).copy(
                party = NameMatch.Found(ROSA),
                payment = NameMatch.Found(CASH),
            ),
            english("Miss Rose took 2 bread rolls and paid in cash"),
        )
    }

    @Test
    fun `charging is a sale with goods and a payment from a customer without them`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.CUSTOMER_PAYMENT,
                party = NameMatch.Found(ROSA),
                total = money(20_000),
            ),
            spanish("cobré a doña Rosa 20 mil de lo que debía"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(line(found(HAIRCUT), units(1), lineTotal = money(15_000))),
                party = NameMatch.Found(ROSA),
            ),
            spanish("le cobré 15000 a doña Rosa por un corte de cabello"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(line(found(COOKIES), units(1), PhraseCatalogs.PACK)),
                party = NameMatch.Found(MARIA),
            ),
            english("charged Mary Smith for a pack of cookies"),
        )
    }

    @Test
    fun `giving is a payment, an expense or a gift`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SUPPLIER_PAYMENT,
                party = NameMatch.Found(RAMIRO),
                total = money(100),
            ),
            english("Paid Mr. Ramiro. Gave him 100."),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(TRANSPORT),
                total = money(20_000),
            ),
            spanish("le di 20 mil al taxi"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.STOCK_ADJUSTMENT,
                reason = StockAdjustmentReason.GIFT_OR_SAMPLE,
                lines = listOf(line(found(EMPANADA), units(1))),
            ),
            spanish("le di una empanada de muestra a un cliente"),
        )
        assertEquals(
            PhraseIntent.STOCK_ADJUSTMENT,
            english("gave a bread roll to a neighbor").intent,
        )
    }

    @Test
    fun `an expense category without a verb wins over a word before it`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(RENT),
                total = money(800_000),
            ),
            spanish("el alquiler de este mes, 800.000"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(UTILITIES),
                total = Money(8550),
            ),
            portuguese("foi paga a conta de água, 85,50"),
        )
        // "conta" no se adivina como un verbo de conteo: abre una categoría.
        assertEquals(PhraseIntent.EXPENSE, portuguese("conta de luz 220").intent)
        // "nuevo" no es "huevo": una palabra que solo se parece a un producto no lo nombra.
        assertNull(spanish("nuevo, 3 empanadas").intent)
    }

    @Test
    fun `batches, dozens and containers count the product`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRODUCTION,
                lines = listOf(line(found(BREAD), units(40))),
            ),
            spanish("hice 2 tandas de 20 panes"),
        )
        val unknownBatch = spanish("hice 2 tandas de panes")
        assertEquals(listOf(line(found(BREAD))), unknownBatch.lines)
        assertEquals(setOf(PhraseIssue.UNREADABLE_QUANTITY), unknownBatch.issues)
        assertEquals(
            listOf(line(found(EGG), units(6))),
            spanish("compré media docena de huevos").lines,
        )
        // Con un precio por docena, la línea queda como se dijo.
        assertEquals(
            listOf(line(found(BREAD), units(2), unitPrice = money(500))),
            spanish("vendí 2 docenas de panes a 500").lines,
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.STOCK_ADJUSTMENT,
                reason = StockAdjustmentReason.EXPIRED,
                lines = listOf(line(found(JAM), units(2))),
            ),
            spanish("se venció la mermelada de fresa, 2 frascos"),
        )
        assertEquals(
            listOf(line(found(EGG), units(4))),
            spanish("se dañaron los huevos, 4").lines,
        )
    }

    @Test
    fun `a production with the ingredients said, and a new product has no recipe`() {
        // Lo dicho después de "con" es lo que llevó la tanda: se produce con eso.
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRODUCTION,
                lines = listOf(line(found(BREAD), units(40))),
                inputs = listOf(line(found(WHEAT_FLOUR), units(2), KILOGRAM)),
            ),
            spanish("hice 40 panes con 2 kilos de harina de trigo"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRODUCTION,
                lines = listOf(line(ProductMatch.Unknown("Galleta de avena"), units(12))),
                issues = setOf(PhraseIssue.NO_RECIPE),
            ),
            spanish("hice 12 galletas de avena"),
        )
    }

    @Test
    fun `a count says what there is, and leaves a cost out with a notice`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.STOCK_ADJUSTMENT,
                reason = StockAdjustmentReason.COUNT_CORRECTION,
                lines = listOf(line(found(BREAD), units(30))),
            ),
            spanish("ajusta el stock de pan a 30"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.STOCK_ADJUSTMENT,
                reason = StockAdjustmentReason.COUNT_CORRECTION,
                lines = listOf(line(found(WHEAT_FLOUR), units(20), KILOGRAM)),
                issues = setOf(PhraseIssue.COST_LEFT_OUT),
            ),
            spanish("tengo 20 kilos de harina de trigo que me costaron 4 mil el kilo"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(found(WHEAT_FLOUR), units(2), SACK, lineTotal = money(90_000))),
            ),
            spanish("compré 2 bultos de harina de trigo que me costaron 90 mil"),
        )
    }

    @Test
    fun `recipes per unit, yields first and no product of itself`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                lines = listOf(line(found(CORN_FLOUR), units(50), PhraseCatalogs.GRAM)),
                target = found(EMPANADA),
            ),
            spanish("cada empanada lleva 50 gramos de harina de maíz"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                lines = listOf(line(found(WHEAT_FLOUR), units(1), KILOGRAM)),
                target = found(BREAD),
                targetQuantity = units(20),
            ),
            spanish("por cada 20 panes uso 1 kilo de harina de trigo"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                lines = listOf(line(found(WHEAT_FLOUR), units(2), KILOGRAM)),
                target = found(BREAD),
                targetQuantity = units(10),
            ),
            spanish("2 kilos de harina de trigo me rinden 10 panes"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                lines = listOf(line(ProductMatch.Unknown("Naranja"), units(20))),
                target = found(ORANGE_JUICE),
                targetQuantity = units(10),
            ),
            spanish("para hacer 10 jugos de naranja uso 20 naranjas"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                lines = listOf(line(found(EGG), units(3))),
                target = found(CHOCO_CAKE),
            ),
            spanish("una torta de chocolate lleva 3 huevos"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.COST_CORRECTION,
                lines = listOf(line(found(EMPANADA), unitPrice = money(1200))),
            ),
            spanish("cada empanada me está costando mil doscientos"),
        )
    }

    @Test
    fun `setting a price, a cost over a price, and a new product`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRICE_CHANGE,
                lines = listOf(
                    line(found(FARMER_CHEESE), unit = KILOGRAM, unitPrice = money(18_000)),
                ),
            ),
            spanish("pon el kilo de queso campesino a 18000"),
        )
        assertNull(spanish("pon 2 panes").intent)
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.COST_CORRECTION,
                lines = listOf(line(found(BREAD), unitPrice = money(450))),
            ),
            spanish("el pan subió, ahora me cuesta 450"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Bolsa de regalo"),
                price = money(800),
            ),
            spanish("crea el producto bolsa de regalo a 800"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Galleta de avena"),
                price = money(2000),
            ),
            spanish("nuevo: galletas de avena a 2000"),
        )
    }

    @Test
    fun `a price said with is, an expense bought, and who pays or buys unnamed`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRICE_CHANGE,
                lines = listOf(line(found(BREAD), unitPrice = Money(70))),
            ),
            english("bread rolls are 0.70 now"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PRICE_CHANGE,
                lines = listOf(line(found(BREAD), unitPrice = money(600))),
            ),
            spanish("el pan es 600"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(RENT),
                total = money(800),
            ),
            english("the rent is 800"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(TRANSPORT),
                total = money(40_000),
            ),
            spanish("compré gasolina, 40 mil"),
        )
        assertEquals(
            sale(line(found(BREAD), units(2))),
            english("a customer bought 2 bread rolls"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.CUSTOMER_PAYMENT,
                party = NameMatch.Found(MARIA),
                total = money(50),
            ),
            english("Mary Smith settled her account, 50"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                lines = listOf(line(found(WHEAT_FLOUR), units(50), PhraseCatalogs.GRAM)),
                target = found(BREAD),
            ),
            english("the recipe for bread rolls is 50 grams of wheat flour"),
        )
    }

    @Test
    fun `a line said by its size alone takes the head of the one before`() {
        assertEquals(
            sale(line(found(SODA_BIG), units(2)), line(found(SODA_SMALL), units(1))),
            spanish("vendí 2 gaseosas grandes y una pequeña"),
        )
        assertEquals(
            sale(line(found(SODA_SMALL), units(1)), line(found(BREAD), units(2))),
            english("a 400 ml soda and two bread rolls"),
        )
    }

    @Test
    fun `an unknown seller after de closes the phrase`() {
        assertEquals(
            NameMatch.Unknown("Pedro"),
            portuguese("comprei 10 quilos de farinha de trigo do Pedro").party,
        )
        // Un nombre en mayúscula que se parece a un producto no es quien vendió.
        assertNull(spanish("compré 2 kilos de Harina").party)
    }

    @Test
    fun `a supplier name does not swallow the words after it`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(found(WHEAT_FLOUR), units(2), SACK, lineTotal = money(50_000))),
                party = NameMatch.Found(RAMIRO),
            ),
            spanish("le pagué 50 mil a Don Ramiro por 2 bultos de harina de trigo"),
        )
    }

    @Test
    fun `numbers said the way people say them`() {
        assertEquals(
            listOf(line(found(BREAD), units(3), unitPrice = money(2500))),
            spanish("vendí 3 panes a 2 mil 500").lines,
        )
        // Después de "mil", unas cifras que no son cientos no siguen el número.
        assertEquals(money(2000), spanish("vendí 3 panes a 2 mil 50").lines.first().unitPrice)
        assertEquals(
            listOf(line(found(FARMER_CHEESE), QuantityMilli(1_200), KILOGRAM)),
            spanish("vendí un kilo doscientos de queso campesino").lines,
        )
        assertEquals(
            listOf(line(found(WHEAT_FLOUR), QuantityMilli(1_200), KILOGRAM)),
            portuguese("vendi um quilo e duzentos de farinha de trigo").lines,
        )
        assertEquals(
            listOf(line(found(COOKIES), units(1), PhraseCatalogs.PACK, lineTotal = money(120))),
            english("bought a pack of cookies for a hundred and twenty").lines,
        )
        assertEquals(
            listOf(line(found(MILK), QuantityMilli(1_500), LITER)),
            english("sold one point five liters of whole milk").lines,
        )
        assertEquals(
            listOf(line(found(BREAD), units(3), unitPrice = Money(50))),
            english("sold 3 bread rolls at fifty cents").lines,
        )
        assertEquals(
            listOf(line(found(BREAD), units(3), unitPrice = Money(1275))),
            english("sold 3 bread rolls at twelve seventy five").lines,
        )
        assertEquals(
            listOf(line(found(WHEAT_FLOUR), units(2), KILOGRAM, unitPrice = Money(450))),
            portuguese("vendi 2 quilos de farinha de trigo a 4 e 50 o quilo").lines,
        )
        assertEquals(
            listOf(line(found(BREAD), unitPrice = Money(120))),
            english("the bread roll is costing me one twenty").lines,
        )
        // "a 1 y 20 empanadas": un precio y otra línea, no centavos.
        assertEquals(
            listOf(
                line(found(BREAD), units(3), unitPrice = money(1)),
                line(found(EMPANADA), units(20)),
            ),
            spanish("vendí 3 panes a 1 y 20 empanadas").lines,
        )
    }

    @Test
    fun `a currency without cents reads the parts of a price as they are`() {
        val reading = PhraseInterpreter.read(
            "sold 3 bread rolls at twelve seventy five",
            PhraseLanguage.EN,
            POINT_DECIMAL,
            0,
            PhraseCatalogs.english,
        )
        assertEquals(Money(12), reading.lines.first().unitPrice)
    }

    @Test
    fun `in English a plural modifier alone is another product`() {
        val withJuice = PhraseCatalogs.english.copy(
            products = PhraseCatalogs.english.products + PhraseProduct(
                id = ORANGE_JUICE,
                name = "Orange juice",
                baseUnitId = PhraseCatalogs.UNIT,
                conversions = listOf(PhraseConversion(BOX, 12_000L)),
                forSale = true,
                forPurchase = false,
                hasRecipe = true,
                stockMode = ProductStockMode.TRACKED,
            ),
        )
        fun read(text: String) =
            PhraseInterpreter.read(text, PhraseLanguage.EN, POINT_DECIMAL, 2, withJuice)
        assertEquals(
            listOf(line(ProductMatch.Unknown("Orange"), units(2))),
            read("sold 2 oranges").lines,
        )
        assertEquals(
            listOf(line(found(ORANGE_JUICE), units(2))),
            read("sold 2 orange juices").lines,
        )
        assertEquals(
            listOf(line(found(ORANGE_JUICE), units(1))),
            read("sold an orange juice").lines,
        )
        assertEquals(listOf(line(found(BREAD), units(2))), read("sold 2 bread rolls").lines)
    }
}
