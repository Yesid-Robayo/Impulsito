package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseConversion
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CASH
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CHOCO_CAKE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COMMA_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.DOZEN
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.FARMER_CHEESE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POINT_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POUND
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RENT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ROSA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SMALL_JUICE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UNIT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UTILITIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.VANILLA_CAKE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.english
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.portuguese
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Los bordes de las formas del habla: lo que se parece a una forma y no lo es, para que cada regla
 * nueva solo actúe donde debe.
 */
class PhraseFormsTest {
    private fun line(
        product: ProductMatch,
        quantity: QuantityMilli? = null,
        unit: Long? = null,
        unitPrice: Money? = null,
        lineTotal: Money? = null,
    ) = PhraseLine(product, quantity, unit, unitPrice, lineTotal)

    private fun found(id: Long) = ProductMatch.Found(id)

    private val withWater = PhraseCatalogs.spanish.copy(
        products = PhraseCatalogs.spanish.products + PhraseProduct(
            id = 900L,
            name = "Agua",
            baseUnitId = UNIT,
            conversions = emptyList(),
            forSale = true,
            forPurchase = true,
            hasRecipe = false,
            stockMode = ProductStockMode.TRACKED,
        ),
    )

    @Test
    fun `soft fillers, create leads and leading separators at the edges`() {
        assertEquals(listOf(line(found(BREAD), units(2))), spanish("vendí 2 panes este").lines)
        assertNull(spanish("nuevo").intent)
        assertNull(spanish("nuevo, 3 empanadas").intent)
    }

    @Test
    fun `who opens the phrase only changes the verb right after them`() {
        assertEquals(
            PhraseIntent.PURCHASE,
            spanish("Carlos, compré 2 kilos de harina de trigo").intent,
        )
        assertEquals(PhraseIntent.SALE, spanish("Don Ramiro, vendí 2 panes").intent)
    }

    @Test
    fun `a price word after a new product is part of it`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.NEW_PRODUCT,
                target = ProductMatch.Unknown("Vela de canela"),
                price = money(15_000),
            ),
            spanish("nuevo producto vela de canela, precio de venta 15000"),
        )
    }

    @Test
    fun `a misdictated first word is a verb only if it decides the operation`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(line(found(BREAD), units(3))),
                payment = NameMatch.Found(CASH),
            ),
            spanish("efectivo, 3 panes"),
        )
        // Parecido a "cuestan", que no dice solo la operación: no se adivina.
        assertNull(spanish("cuestam 3 panes").intent)
    }

    @Test
    fun `a yield without what follows or with a price is a cost`() {
        assertEquals(
            PhraseIntent.COST_CORRECTION,
            spanish("2 panes me salen a 500").intent,
        )
        assertEquals(
            PhraseIntent.COST_CORRECTION,
            spanish("2 kilos de harina de trigo me rinden").intent,
        )
    }

    @Test
    fun `refusals and percentages at the edges`() {
        // La devolución va después de otro verbo: igual no se registra.
        assertNull(spanish("vendí 3 panes y me devolvieron 1").intent)
        assertNull(spanish("devolví 3 panes que compré").intent)
        // Un porcentaje al principio, sin cifra delante, no lo es.
        assertEquals(
            listOf(line(found(BREAD), units(2))),
            spanish("porciento, vendí 2 panes").lines,
        )
    }

    @Test
    fun `a self fix keeps what the fixed line said and the fix did not`() {
        assertEquals(
            listOf(line(found(BREAD), units(4))),
            spanish("vendí, perdón, 4 panes").lines,
        )
        assertEquals(
            listOf(line(found(FARMER_CHEESE), units(3), KILOGRAM, unitPrice = money(18_000))),
            spanish("vendí 2 kilos de queso campesino a 18000, perdón, 3").lines,
        )
        assertEquals(
            listOf(line(found(FARMER_CHEESE), units(3), POUND)),
            spanish("vendí 2 kilos de queso campesino, perdón, 3 libras").lines,
        )
        assertEquals(
            listOf(line(found(BREAD), units(4), lineTotal = money(1500))),
            spanish("vendí 3 panes por 1500, perdón, 4").lines,
        )
        assertEquals(
            listOf(line(found(BREAD), units(4), unitPrice = money(600))),
            spanish("vendí 3 panes a 500, perdón, 4 a 600").lines,
        )
    }

    @Test
    fun `a tender without an amount or without cash in the catalog`() {
        assertEquals(
            NameMatch.Found(CASH),
            spanish("vendí 3 panes, me pagó con un billete").payment,
        )
        val noCash = PhraseCatalogs.spanish.copy(
            paymentMethods = PhraseCatalogs.spanish.paymentMethods.filterNot { it.id == CASH },
        )
        val reading = spanish("vendí 3 panes, me pagó con un billete de 20 mil", noCash)
        assertNull(reading.payment)
        assertEquals(listOf(line(found(BREAD), units(3))), reading.lines)
        // La mitad sin método detrás no reparte nada.
        assertNull(spanish("vendí 2 panes, la mitad").payment)
        // Un pago suelto al principio, o después de una palabra, sí es el pago.
        assertEquals(NameMatch.Found(CASH), spanish("efectivo, vendí 3 panes").payment)
        assertEquals(NameMatch.Found(CASH), spanish("vendí 3 panes efectivo").payment)
        assertEquals(NameMatch.Found(CASH), spanish("vendí panes de efectivo").payment)
    }

    @Test
    fun `two sentences may carry the amount with the measure`() {
        assertEquals(
            listOf(line(found(WHEAT_FLOUR), units(10), KILOGRAM, unitPrice = money(4000))),
            spanish("Compré harina de trigo. Fueron 10 kilos a 4 mil.").lines,
        )
        assertEquals(
            listOf(line(found(WHEAT_FLOUR), units(10), KILOGRAM, unitPrice = money(4000))),
            spanish("Compré harina de trigo a 4 mil. Fueron 10 kilos.").lines,
        )
    }

    @Test
    fun `containers without a count and a size after a complement`() {
        assertEquals(
            listOf(line(found(PhraseCatalogs.JAM)), line(found(PhraseCatalogs.JAR))),
            spanish("se venció la mermelada de fresa, frascos").lines,
        )
        assertEquals(
            listOf(
                line(found(PhraseCatalogs.ORANGE_JUICE), units(2)),
                line(found(SMALL_JUICE), units(1)),
            ),
            spanish("vendí 2 jugos de naranja y uno pequeño").lines,
        )
    }

    @Test
    fun `an unknown seller that does not close the phrase is not one`() {
        assertEquals(
            NameMatch.Unknown("Pedro"),
            portuguese("comprei 10 quilos de farinha de trigo do Pedro, em dinheiro").party,
        )
        assertNull(portuguese("comprei farinha de trigo do Pedro e 2 ovos").party)
    }

    @Test
    fun `an expense category that is also a product, or with goods`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(UTILITIES),
                total = money(120_000),
            ),
            spanish("la luz y el agua, 120 mil", withWater),
        )
        assertNull(spanish("el arriendo, 2 panes").intent)
        // El precio de un producto que también es una categoría sí se cambia.
        assertEquals(
            PhraseIntent.PRICE_CHANGE,
            spanish("el agua vale 2000", withWater).intent,
        )
        assertEquals(
            PhraseReading(intent = PhraseIntent.PRICE_CHANGE),
            spanish("vale 900"),
        )
    }

    @Test
    fun `weak steps with and without what decides them`() {
        assertEquals(PhraseIntent.SALE, spanish("vendo pan").intent)
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.CUSTOMER_PAYMENT,
                party = NameMatch.Found(ROSA),
                total = money(20_000),
            ),
            spanish("doña Rosa me pagó el arriendo, 20 mil"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(UTILITIES),
                total = money(30_000),
            ),
            spanish("Don Ramiro me pagó la luz, 30 mil"),
        )
        assertEquals(
            PhraseReading(intent = PhraseIntent.SALE, total = money(20_000)),
            spanish("cobré 20 mil"),
        )
        assertEquals(
            PhraseReading(intent = PhraseIntent.SALE, total = money(50_000)),
            spanish("vendí todo por 50 mil"),
        )
        assertNull(spanish("vendí").intent)
    }

    @Test
    fun `an adjustment leaves out a line total too, and counts at the edges`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.STOCK_ADJUSTMENT,
                reason = StockAdjustmentReason.DAMAGED,
                lines = listOf(line(found(BREAD), units(3))),
                issues = setOf(PhraseIssue.COST_LEFT_OUT),
            ),
            spanish("se dañaron 3 panes por 1500"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.STOCK_ADJUSTMENT,
                reason = StockAdjustmentReason.COUNT_CORRECTION,
                lines = listOf(
                    line(
                        ProductMatch.Choose(
                            listOf(PhraseCatalogs.SODA_SMALL, PhraseCatalogs.SODA_BIG),
                        ),
                    ),
                ),
                issues = setOf(PhraseIssue.COST_LEFT_OUT),
            ),
            spanish("hay gaseosas que me costaron 3 mil cada una"),
        )
        // Una cifra suelta después de otra que ya contaba no es la de nadie.
        assertEquals(
            listOf(line(found(BREAD), units(3))),
            spanish("se dañaron 3 panes, 4").lines,
        )
        assertNull(spanish("se dañaron 3, 4").intent)
    }

    @Test
    fun `a recipe for something new, and dozens at the edges`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                lines = listOf(line(found(EGG), units(2))),
                target = ProductMatch.Unknown("Galleta de coco"),
                targetQuantity = units(10),
            ),
            spanish("para hacer 10 galletas de coco uso 2 huevos"),
        )
        assertEquals(
            listOf(line(found(BREAD), units(12), lineTotal = money(6000))),
            spanish("vendí una docena de panes por 6000").lines,
        )
        assertEquals(listOf(line(found(BREAD), units(12))), spanish("vendí docena de panes").lines)
        val flour = spanish("vendí una docena de harina de trigo")
        assertEquals(listOf(line(found(WHEAT_FLOUR), units(1))), flour.lines)
        assertEquals(setOf(PhraseIssue.UNIT_NOT_FOR_PRODUCT), flour.issues)
        assertEquals(
            listOf(line(found(EGG), units(2), DOZEN)),
            spanish("vendí 2 docenas de huevos").lines,
        )
    }

    @Test
    fun `lines that open with por, a demonstrative or an article`() {
        val apart = spanish("vendí 3 panes, por 1500")
        assertEquals(listOf(line(found(BREAD), units(3))), apart.lines)
        assertEquals(money(1500), apart.total)
        assertEquals(listOf(line(found(BREAD), units(3))), spanish("vendí 3 panes y por").lines)
        assertNull(english("sold this").intent)
        assertNull(english("sold this is it").intent)
        assertEquals(
            listOf(line(found(BREAD), units(2))),
            spanish("vendí 2 panes a una amiga").lines,
        )
        assertEquals(
            listOf(line(found(BREAD), units(2), unitPrice = money(1))),
            spanish("vendí 2 panes a 1 amiga").lines.take(1),
        )
        assertEquals(
            money(1000),
            spanish("vendí 2 panes a un mil").lines.first().unitPrice,
        )
        assertEquals(
            money(1),
            spanish("vendí 2 panes a un peso").lines.first().unitPrice,
        )
        assertEquals(money(1), spanish("vendí 2 panes a un").lines.first().unitPrice)
    }

    @Test
    fun `grams after a kilo only when they fit`() {
        assertEquals(
            listOf(line(found(FARMER_CHEESE), QuantityMilli(1_200), KILOGRAM)),
            spanish("vendí kilo doscientos de queso campesino").lines,
        )
        // Sin producto, la medida sola no es una línea.
        assertNull(spanish("vendí un kilo doscientos").intent)
        // Dinero, mil o más, o cero después del kilo no son sus gramos.
        val money = spanish("vendí un kilo 200 pesos de queso campesino").lines
        assertEquals(listOf<QuantityMilli?>(null), money.map { it.quantity })
        val many = spanish("vendí un kilo 1500 de queso campesino").lines
        assertEquals(listOf<QuantityMilli?>(units(1500)), many.map { it.quantity })
        val zero = spanish("vendí un kilo 0 de queso campesino").lines
        assertEquals(listOf<QuantityMilli?>(QuantityMilli(0)), zero.map { it.quantity })
        assertEquals(
            listOf(line(found(FARMER_CHEESE), QuantityMilli(1_200), KILOGRAM)),
            spanish("vendí queso campesino, un kilo doscientos").lines,
        )
        assertEquals(
            listOf(line(found(FARMER_CHEESE), QuantityMilli(1_200), KILOGRAM)),
            spanish("vendí queso campesino, un kilo doscientos, en efectivo").lines,
        )
    }

    @Test
    fun `batches that do not fit or bring nothing`() {
        assertEquals(
            listOf(line(found(BREAD), QuantityMilli(Long.MAX_VALUE))),
            spanish("hice 999999999999 tandas de 999999999999 panes").lines,
        )
        assertEquals(
            listOf(line(found(BREAD), QuantityMilli(0))),
            spanish("hice 2 tandas de 0 panes").lines,
        )
    }

    @Test
    fun `decimals said in words at the edges`() {
        assertEquals(
            listOf(line(found(BREAD), QuantityMilli(1_250))),
            english("sold one point two five bread rolls").lines,
        )
        assertEquals(
            listOf(line(found(BREAD), QuantityMilli(1_250))),
            english("sold one point 25 bread rolls").lines,
        )
        assertEquals(units(1), english("sold one point the bread roll").lines.first().quantity)
        assertEquals(units(1), spanish("vendí uno coma medio pan").lines.first().quantity)
        assertEquals(units(1), spanish("vendí uno coma").lines.first().quantity)
    }

    @Test
    fun `cents said in two parts at the edges`() {
        assertEquals(money(2), spanish("vendí 3 panes a 2 y 5").lines.first().unitPrice)
        assertEquals(money(150), spanish("vendí 3 panes a 150 y 20").lines.first().unitPrice)
        assertEquals(Money(50), spanish("vendí 3 panes a 0,5 y 20").lines.first().unitPrice)
        assertEquals(money(2), spanish("vendí 3 panes a 2 y 150").lines.first().unitPrice)
        assertEquals(
            Money(220),
            spanish("vendí 3 panes a 2 y 20, en efectivo").lines.first().unitPrice,
        )
        assertEquals(money(2), spanish("vendí 3 panes a 2 y 20,5").lines.first().unitPrice)
    }

    @Test
    fun `digits after a thousand at the edges`() {
        assertEquals(money(2000), spanish("vendí 3 panes a 2 mil 1500").lines.first().unitPrice)
        assertEquals(
            money(2000),
            spanish("vendí 3 panes a 2 mil 1234567890123").lines.first().unitPrice,
        )
    }

    @Test
    fun `an English head word at the edges`() {
        fun product(id: Long, name: String) = PhraseProduct(
            id = id,
            name = name,
            baseUnitId = UNIT,
            conversions = listOf(PhraseConversion(PhraseCatalogs.BOX, 12_000L)),
            forSale = true,
            forPurchase = true,
            hasRecipe = false,
            stockMode = ProductStockMode.TRACKED,
        )
        val catalog = PhraseCatalogs.english.copy(
            products = listOf(
                product(901L, "Q R"),
                product(902L, "White shirt M"),
                product(903L, "Orange juice"),
            ),
        )
        fun read(text: String) =
            PhraseInterpreter.read(text, PhraseLanguage.EN, POINT_DECIMAL, 2, catalog)
        assertEquals(listOf(line(found(902L), units(2))), read("sold 2 white shirts").lines)
        assertEquals(listOf(line(found(903L), units(2))), read("sold 2 juices").lines)
        assertEquals(listOf(line(found(901L), units(2))), read("sold 2 q r").lines)
    }

    @Test
    fun `more edges of verbs, fixes, counts and recipes`() {
        assertEquals(
            PhraseIntent.NEW_PRODUCT,
            english("create the product honey, sold by the pound, at 6").intent,
        )
        assertNull(spanish("costoo 3 panes").intent)
        assertNull(spanish("cuestaa 3 panes").intent)
        assertNull(spanish("recibo 3 panes").intent)
        assertEquals(
            PhraseIntent.STOCK_ADJUSTMENT,
            spanish("se venció la mermelada de fresa, 2 panes").intent,
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.RECIPE,
                lines = listOf(
                    line(ProductMatch.Unknown("Chocolate"), units(200), PhraseCatalogs.GRAM),
                ),
                target = found(CHOCO_CAKE),
            ),
            english("a chocolate cake takes 200 grams of chocolate"),
        )
        assertEquals(
            listOf(line(found(BREAD), units(4), lineTotal = money(2000))),
            spanish("vendí 3 panes, perdón, 4 panes por 2000").lines,
        )
        assertEquals(
            listOf(line(found(WHEAT_FLOUR), units(10), KILOGRAM, lineTotal = money(40_000))),
            spanish("Compré harina de trigo. Fueron 10 kilos por 40 mil.").lines,
        )
        assertEquals(
            PhraseIntent.STOCK_ADJUSTMENT,
            spanish("se venció la mermelada de fresa, 2 cajas").intent,
        )
        assertNull(spanish("3 tornillos de madera").intent)
        assertEquals(
            listOf(line(found(BREAD))),
            spanish("conté pan").lines,
        )
        assertEquals(
            listOf(line(found(BREAD), units(3))),
            spanish("se dañaron 3 panes, por 1500").lines,
        )
        assertEquals(PhraseIntent.RECIPE, spanish("lleva 3 huevos").intent)
        assertEquals(PhraseIntent.SALE, spanish("vendí 1 kilo 3 panes").intent)
        assertEquals(units(1), spanish("vendí uno coma veinte panes").lines.first().quantity)
    }

    @Test
    fun `what is paid for and how many, on both sides of por`() {
        assertEquals(
            listOf(line(found(WHEAT_FLOUR), units(10), KILOGRAM, lineTotal = money(20_000))),
            spanish("compré 20000 por 10 kilos de harina de trigo").lines,
        )
        assertEquals(
            listOf(line(found(WHEAT_FLOUR), lineTotal = money(20_000))),
            spanish("compré 20000 por harina de trigo").lines,
        )
        assertEquals(
            listOf(line(found(BREAD), units(3), lineTotal = money(2500))),
            spanish("vendí 2500 pesos por 3 panes").lines,
        )
        fun PhraseDraft.fix(text: String) =
            correctedBy(text, PhraseLanguage.ES, COMMA_DECIMAL, 2, PhraseCatalogs.spanish)
        val cakes = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(DraftLine(DraftTarget.Existing(CHOCO_CAKE), units(2), null)),
        )
        val three = DraftLine(DraftTarget.Existing(CHOCO_CAKE), units(3), null)
        assertEquals(
            cakes.copy(lines = listOf(three.copy(lineTotal = money(120_000)))),
            cakes.fix("no, eran 3 por 120 mil"),
        )
        assertEquals(
            cakes.copy(lines = listOf(three.copy(lineTotal = money(1500)))),
            cakes.fix("no, eran 3 por 1500 pesos"),
        )
    }

    @Test
    fun `possessives only after a proper name`() {
        fun words(text: String) = PhraseTokenizer.tokenize(text).map { it.norm }
        assertEquals(listOf("mary", "tab"), words("Mary's tab"))
        assertEquals(listOf("mary", "tab"), words("MARY'S tab"))
        assertEquals(listOf("its", "cash"), words("It's cash"))
        assertEquals(listOf("marys", "tab"), words("mary's tab"))
        assertEquals(listOf("marystar"), words("Mary'star"))
        assertEquals(listOf("s", "tab"), words("'s tab"))
        assertEquals(listOf("on", "mary", "tab"), words("on Mary's tab"))
        assertEquals(listOf("tab", "of", "mary"), words("tab of Mary's"))
        assertEquals(listOf("oneil"), words("O'Neil"))
        assertEquals(listOf("mary"), words("Mary'"))
        assertEquals(listOf("marysx"), words("MARY'Sx"))
    }

    @Test
    fun `a size among products without a measure does not choose`() {
        val cakes = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(
                DraftLine(null, units(2), null, candidates = listOf(CHOCO_CAKE, VANILLA_CAKE)),
            ),
        )
        assertNull(
            cakes.correctedBy(
                "las grandes",
                PhraseLanguage.ES,
                COMMA_DECIMAL,
                2,
                PhraseCatalogs.spanish,
            ),
        )
    }

    @Test
    fun `corrections at the edges`() {
        fun PhraseDraft.fix(text: String, decimals: Int = 2) =
            correctedBy(text, PhraseLanguage.ES, COMMA_DECIMAL, decimals, PhraseCatalogs.spanish)
        val sale =
            PhraseDraft(
                PhraseIntent.SALE,
                lines = listOf(DraftLine(DraftTarget.Existing(BREAD), units(2), null)),
            )
        // Un motivo en una venta no cambia la operación ni se pone.
        assertNull(sale.fix("no, se vencieron"))
        // Sin centavos, el monto se lee en unidades enteras.
        val expense = PhraseDraft(PhraseIntent.EXPENSE, categoryId = RENT)
        assertEquals(expense.copy(total = Money(90)), expense.fix("no, 90", decimals = 0))
        assertEquals(expense.copy(total = money(90)), expense.fix("no, 90"))
        // Un total con la línea ya con su monto no la cambia.
        val priced = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(
                DraftLine(DraftTarget.Existing(BREAD), units(2), null, lineTotal = money(1000)),
            ),
        )
        assertEquals(priced.copy(total = money(1200)), priced.fix("todo por 1200"))
        // Un precio sin líneas no tiene a qué ponerse.
        assertNull(PhraseDraft(PhraseIntent.PRICE_CHANGE).fix("650"))
        // Un producto que ya no está en el catálogo no se nombra por sus palabras.
        val gone = PhraseDraft(
            PhraseIntent.SALE,
            lines = listOf(
                DraftLine(DraftTarget.Existing(999L), units(1), null),
                DraftLine(DraftTarget.Existing(BREAD), units(1), null),
            ),
        )
        assertNull(gone.fix("quita la naranja"))
    }
}
