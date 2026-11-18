package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BOX
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CARD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CARLOS
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CASH
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COMMA_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CORN_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ON_ACCOUNT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POUND
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RAMIRO
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ROSA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_BIG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_SMALL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.TRANSFER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UTILITIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.VANILLA_CAKE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La corrección con una segunda frase, en español, sobre el catálogo de prueba. */
class PhraseCorrectionTest {
    private fun PhraseDraft.fix(
        text: String,
        catalog: PhraseCatalog = PhraseCatalogs.spanish,
    ): PhraseDraft? = correctedBy(text, PhraseLanguage.ES, COMMA_DECIMAL, 2, catalog)

    private fun item(id: Long) = DraftTarget.Existing(id)

    private fun line(
        product: DraftTarget?,
        quantity: QuantityMilli? = null,
        unit: Long? = null,
        unitPrice: Money? = null,
        lineTotal: Money? = null,
    ) = DraftLine(product, quantity, unit, unitPrice, lineTotal)

    private fun sale(vararg lines: DraftLine) =
        PhraseDraft(PhraseIntent.SALE, lines = lines.toList())

    private fun purchase(vararg lines: DraftLine) =
        PhraseDraft(PhraseIntent.PURCHASE, lines = lines.toList())

    private val bread = line(item(BREAD), units(2))
    private val eggs = line(item(EGG), units(1))
    private val smallSoda = line(item(SODA_SMALL), units(2))
    private val bigSoda = line(item(SODA_BIG), units(1))

    // Cantidad y unidad.

    @Test
    fun `a bare number corrects the quantity of the only line`() {
        assertEquals(sale(line(item(BREAD), units(3))), sale(bread).fix("no, eran 3"))
    }

    @Test
    fun `a bare number goes to the last line said`() {
        assertEquals(sale(bread, line(item(EGG), units(5))), sale(bread, eggs).fix("eran 5"))
    }

    @Test
    fun `fillers and leading commas are skipped`() {
        assertEquals(sale(line(item(BREAD), units(3))), sale(bread).fix("eh, perdón, eran 3"))
    }

    @Test
    fun `a quantity with its unit changes both`() {
        assertEquals(
            purchase(line(item(WHEAT_FLOUR), units(5), KILOGRAM)),
            purchase(line(item(WHEAT_FLOUR), units(2), POUND)).fix("eran 5 kilos"),
        )
    }

    @Test
    fun `a unit alone keeps the quantity`() {
        assertEquals(
            purchase(line(item(WHEAT_FLOUR), units(2), KILOGRAM)),
            purchase(line(item(WHEAT_FLOUR), units(2))).fix("eran kilos"),
        )
    }

    @Test
    fun `a quantity said with the product goes to that line`() {
        assertEquals(
            sale(line(item(BREAD), units(3)), eggs),
            sale(bread, eggs).fix("no, eran 3 panes"),
        )
    }

    @Test
    fun `the product named before the verb chooses the line`() {
        assertEquals(
            sale(line(item(BREAD), units(4)), eggs),
            sale(bread, eggs).fix("los panes eran 4"),
        )
    }

    @Test
    fun `a quantity with a product that is in no line replaces the last one`() {
        assertEquals(sale(line(null, units(3))), sale(bread).fix("eran 3 gaseosas"))
    }

    @Test
    fun `a unit that the product does not take is not understood`() {
        assertNull(sale(bread).fix("eran 3 kilos"))
    }

    @Test
    fun `a quantity that cannot be read is not understood`() {
        assertNull(sale(bread).fix("eran unos"))
    }

    @Test
    fun `without lines there is nothing to correct`() {
        assertNull(sale().fix("eran 3"))
    }

    @Test
    fun `a product named before the verb that is in no line is not understood`() {
        assertNull(sale(bread).fix("el tornillo era 3"))
    }

    @Test
    fun `a stock adjustment and a production take the same corrections`() {
        val damaged = PhraseDraft(PhraseIntent.STOCK_ADJUSTMENT, lines = listOf(bread))
        assertEquals(
            damaged.copy(lines = listOf(line(item(BREAD), units(4)))),
            damaged.fix("eran 4"),
        )
        val baked = PhraseDraft(PhraseIntent.PRODUCTION, lines = listOf(bread))
        assertEquals(baked.copy(lines = listOf(line(item(BREAD), units(6)))), baked.fix("fueron 6"))
    }

    @Test
    fun `a recipe line takes a quantity in grams`() {
        val recipe = PhraseDraft(
            PhraseIntent.RECIPE,
            lines = listOf(line(item(WHEAT_FLOUR), QuantityMilli(200_000), PhraseCatalogs.GRAM)),
        )
        assertEquals(
            recipe.copy(
                lines = listOf(
                    line(item(WHEAT_FLOUR), QuantityMilli(300_000), PhraseCatalogs.GRAM),
                ),
            ),
            recipe.fix("eran 300 gramos"),
        )
    }

    // Producto.

    @Test
    fun `the name before the verb and what it was make the new product`() {
        assertEquals(
            purchase(line(item(CORN_FLOUR), units(1), KILOGRAM)),
            purchase(line(item(WHEAT_FLOUR), units(1), KILOGRAM)).fix("la harina era de maíz"),
        )
    }

    @Test
    fun `the name before the verb finds the line still waiting for a product`() {
        assertEquals(
            purchase(line(item(CORN_FLOUR), units(1), KILOGRAM)),
            purchase(line(null, units(1), KILOGRAM)).fix("la harina era de maíz"),
        )
    }

    @Test
    fun `a size word takes the head of the product the line had`() {
        assertEquals(sale(line(item(SODA_BIG), units(2))), sale(smallSoda).fix("no, era la grande"))
    }

    @Test
    fun `a complement takes the head of a product to be created`() {
        assertEquals(
            sale(line(item(VANILLA_CAKE), units(1))),
            sale(line(DraftTarget.New("Torta"), units(1))).fix("era de vainilla"),
        )
    }

    @Test
    fun `a new product that could be several leaves the line to choose`() {
        assertEquals(sale(line(null, units(2))), sale(bread).fix("era gaseosa"))
    }

    @Test
    fun `a unit the new product does not take is dropped`() {
        assertEquals(
            sale(line(item(BREAD), units(2))),
            sale(line(item(CORN_FLOUR), units(2), POUND)).fix("no, era pan"),
        )
    }

    @Test
    fun `a product that is not in the catalog is not understood`() {
        assertNull(sale(bread).fix("era de arroz"))
    }

    @Test
    fun `a size word on a line without a product is not understood`() {
        assertNull(sale(line(null, units(2))).fix("era la grande"))
    }

    @Test
    fun `a size word on a product that is no longer in the catalog is not understood`() {
        assertNull(sale(line(item(9_999L), units(2))).fix("era la grande"))
    }

    @Test
    fun `a product or a unit outside the catalog cannot be checked and is kept`() {
        assertEquals(
            sale(line(item(9_999L), units(3), KILOGRAM)),
            sale(line(item(9_999L), units(2))).fix("eran 3 kilos"),
        )
        assertEquals(
            sale(line(item(BREAD), units(3), 77L)),
            sale(line(item(BREAD), units(2), 77L)).fix("eran 3"),
        )
    }

    @Test
    fun `a cost or a price takes the amount however it is said`() {
        val cost = PhraseDraft(
            PhraseIntent.COST_CORRECTION,
            lines = listOf(line(item(WHEAT_FLOUR), unitPrice = money(4000))),
        )
        assertEquals(
            cost.copy(lines = listOf(line(item(WHEAT_FLOUR), unitPrice = money(4500)))),
            cost.fix("a 4500"),
        )
        assertEquals(
            cost.copy(lines = listOf(line(item(WHEAT_FLOUR), unitPrice = money(4400)))),
            cost.fix("por 4400"),
        )
        assertEquals(
            cost.copy(lines = listOf(line(item(WHEAT_FLOUR), null, KILOGRAM, money(4000)))),
            cost.fix("era el kilo"),
        )
    }

    // Precios dichos para otro producto o para otra unidad.

    @Test
    fun `a new product forgets the prices of the old one`() {
        assertEquals(
            purchase(line(item(CORN_FLOUR), units(1), KILOGRAM)),
            purchase(
                line(item(WHEAT_FLOUR), units(1), KILOGRAM, money(4000), money(4000)),
            ).fix("la harina era de maíz"),
        )
    }

    @Test
    fun `a new product keeps the price said with it`() {
        assertEquals(
            purchase(line(item(CORN_FLOUR), units(1), KILOGRAM, unitPrice = money(4500))),
            purchase(line(item(WHEAT_FLOUR), units(1), KILOGRAM, lineTotal = money(4000)))
                .fix("la harina era de maíz a 4500"),
        )
    }

    @Test
    fun `a new unit forgets the prices of the old one`() {
        assertEquals(
            purchase(line(item(WHEAT_FLOUR), units(5), POUND)),
            purchase(line(item(WHEAT_FLOUR), units(2), KILOGRAM, money(4000)))
                .fix("eran 5 libras"),
        )
        assertEquals(
            sale(line(DraftTarget.New("Tornillo de madera"), units(3), BOX)),
            sale(line(DraftTarget.New("Tornillo de madera"), units(2), unitPrice = money(300)))
                .fix("eran 3 cajas"),
        )
        assertEquals(
            sale(line(null, units(3), KILOGRAM)),
            sale(line(null, units(2), unitPrice = money(500))).fix("eran 3 kilos"),
        )
        assertEquals(
            sale(line(item(9_999L), units(3), KILOGRAM)),
            sale(line(item(9_999L), units(2), unitPrice = money(500))).fix("eran 3 kilos"),
        )
    }

    @Test
    fun `the base unit said out loud keeps the price`() {
        assertEquals(
            purchase(line(item(WHEAT_FLOUR), units(5), KILOGRAM, money(4000))),
            purchase(line(item(WHEAT_FLOUR), units(2), unitPrice = money(4000)))
                .fix("eran 5 kilos"),
        )
    }

    @Test
    fun `lines left to choose forget their prices, and lines left to count keep them`() {
        val small = line(item(SODA_SMALL), units(2), unitPrice = money(2000))
        val big = line(item(SODA_BIG), units(1), unitPrice = money(4000))
        assertEquals(
            sale(line(null, units(2)), line(null, units(1))),
            sale(small, big).fix("la gaseosa era la grande"),
        )
        assertEquals(
            sale(small.copy(quantity = null), big.copy(quantity = null)),
            sale(small, big).fix("las gaseosas eran 3"),
        )
    }

    // Varias líneas posibles.

    @Test
    fun `a quantity that could be of several lines leaves them to fill`() {
        assertEquals(
            sale(line(item(SODA_SMALL)), line(item(SODA_BIG))),
            sale(smallSoda, bigSoda).fix("las gaseosas eran 3"),
        )
    }

    @Test
    fun `a price for several lines goes to all of them`() {
        assertEquals(
            sale(
                line(item(SODA_SMALL), units(2), unitPrice = money(2500)),
                line(item(SODA_BIG), units(1), unitPrice = money(2500)),
            ),
            sale(smallSoda, bigSoda).fix("las gaseosas eran a 2500"),
        )
    }

    @Test
    fun `a product change that could be of several lines leaves them to choose`() {
        assertEquals(
            sale(line(null, units(2)), line(null, units(1))),
            sale(smallSoda, bigSoda).fix("la gaseosa era la grande"),
        )
    }

    // Precio.

    @Test
    fun `a price each replaces the line total`() {
        assertEquals(
            sale(line(item(BREAD), units(3), unitPrice = money(600))),
            sale(line(item(BREAD), units(3), lineTotal = money(1500))).fix("a 600 cada uno"),
        )
    }

    @Test
    fun `a line total replaces the price each`() {
        assertEquals(
            sale(line(item(BREAD), units(3), lineTotal = money(1500))),
            sale(line(item(BREAD), units(3), unitPrice = money(500))).fix("no, por 1500"),
        )
    }

    @Test
    fun `a quantity keeps the prices already said`() {
        val priced = line(item(BREAD), units(3), unitPrice = money(500), lineTotal = money(1500))
        assertEquals(sale(priced.copy(quantity = units(4))), sale(priced).fix("eran 4"))
    }

    @Test
    fun `a price word that does not open a new phrase stays a price`() {
        assertEquals(
            sale(line(item(BREAD), units(2), unitPrice = money(600))),
            sale(bread).fix("eran a 600"),
        )
        assertEquals(
            sale(line(item(BREAD), units(2), unitPrice = money(700))),
            sale(bread).fix("ahora a 700"),
        )
    }

    @Test
    fun `a bare number is the new cost or price`() {
        val cost = PhraseDraft(
            PhraseIntent.COST_CORRECTION,
            lines = listOf(line(item(WHEAT_FLOUR), null, KILOGRAM, unitPrice = money(4000))),
        )
        assertEquals(
            cost.copy(lines = listOf(line(item(WHEAT_FLOUR), null, KILOGRAM, money(4500)))),
            cost.fix("no, 4500"),
        )
        assertEquals(
            cost.copy(lines = listOf(line(item(WHEAT_FLOUR), null, KILOGRAM, money(4600)))),
            cost.fix("el costo es 4600"),
        )
        val price = PhraseDraft(
            PhraseIntent.PRICE_CHANGE,
            lines = listOf(line(item(BREAD), unitPrice = money(600))),
        )
        assertEquals(
            price.copy(lines = listOf(line(item(BREAD), unitPrice = money(700)))),
            price.fix("no, vale 700"),
        )
    }

    @Test
    fun `a new product takes its price or its cost`() {
        val product = PhraseDraft(PhraseIntent.NEW_PRODUCT, target = DraftTarget.New("Zanahoria"))
        assertEquals(product.copy(price = money(1500)), product.fix("a 1500"))
        assertEquals(product.copy(price = money(1800)), product.fix("no, era 1800"))
        assertEquals(product.copy(cost = money(600)), product.fix("el costo es 600"))
        assertNull(product.fix("era rico"))
    }

    // Quitar.

    @Test
    fun `a removal takes out the line still waiting to choose`() {
        assertEquals(sale(bread), sale(bread, line(null, units(2))).fix("quita las gaseosas"))
    }

    @Test
    fun `a removal takes out the line of that product`() {
        assertEquals(sale(eggs), sale(bread, eggs).fix("sin el pan"))
    }

    @Test
    fun `a removal finds a product to be created by its words`() {
        val screws = line(DraftTarget.New("Tornillo de madera"), units(2))
        assertEquals(sale(bread), sale(bread, screws).fix("quita los tornillos de madera"))
    }

    @Test
    fun `a removal skips a product to be created with other words`() {
        val screws = line(DraftTarget.New("Tornillo de madera"), units(2))
        assertEquals(sale(screws), sale(screws, bread).fix("sin el pan"))
    }

    @Test
    fun `a removal that could be of several lines takes out none`() {
        assertNull(sale(smallSoda, bigSoda).fix("quita la gaseosa"))
    }

    @Test
    fun `a removal that names nothing is not understood`() {
        assertNull(sale(bread, line(DraftTarget.New("Tornillo"), units(1))).fix("quita la"))
    }

    @Test
    fun `there is no line to remove from an expense`() {
        assertNull(PhraseDraft(PhraseIntent.EXPENSE, total = money(20000)).fix("quita el pan"))
    }

    // Agregar.

    @Test
    fun `an addition appends a line`() {
        assertEquals(sale(bread, line(item(EGG), units(2))), sale(bread).fix("agrega 2 huevos"))
    }

    @Test
    fun `a separator at the start adds a line too`() {
        assertEquals(
            sale(eggs, line(item(BREAD), units(1))),
            sale(eggs).fix("y también un pan"),
        )
    }

    @Test
    fun `an addition may add several lines with their prices`() {
        assertEquals(
            sale(
                bread,
                line(item(EGG), units(2), unitPrice = money(800)),
                line(null, units(1), lineTotal = money(3000))
                    .copy(candidates = listOf(SODA_SMALL, SODA_BIG)),
            ),
            sale(bread).fix("agrega 2 huevos a 800 y una gaseosa por 3000"),
        )
    }

    @Test
    fun `an addition proposes to create a product that is not in the catalog`() {
        assertEquals(
            sale(bread, line(DraftTarget.New("Tornillo de madera"), units(2))),
            sale(bread).fix("agrega 2 tornillos de madera"),
        )
        assertEquals(sale(eggs, line(item(BREAD))), sale(eggs).fix("agrega pan"))
        val baked = PhraseDraft(PhraseIntent.PRODUCTION, lines = listOf(bread))
        assertEquals(
            baked.copy(lines = listOf(bread, line(null, units(3)))),
            baked.fix("agrega 3 tornillos de madera"),
        )
    }

    @Test
    fun `after a separator an unknown product needs a quantity`() {
        assertNull(sale(bread).fix("y tornillos de madera"))
    }

    @Test
    fun `an addition that cannot be read is not understood`() {
        assertNull(sale(bread).fix("agrega unos panes"))
        assertNull(sale(bread).fix("agrega 2 kilos de pan"))
        assertNull(sale(bread).fix("agrega 2 huevos y el"))
        assertNull(PhraseDraft(PhraseIntent.EXPENSE).fix("agrega 2 panes"))
    }

    // Pago y crédito.

    @Test
    fun `a payment method is set and leaves the credit`() {
        val onCredit = sale(bread).copy(paymentMethodId = ON_ACCOUNT, credit = true)
        assertEquals(sale(bread).copy(paymentMethodId = CASH), onCredit.fix("en efectivo"))
        assertEquals(
            sale(bread).copy(paymentMethodId = TRANSFER),
            sale(bread).fix("fue por transferencia"),
        )
    }

    @Test
    fun `credit sets the credit method`() {
        assertEquals(
            sale(bread).copy(paymentMethodId = ON_ACCOUNT, credit = true),
            sale(bread).copy(paymentMethodId = CASH).fix("fiado"),
        )
        assertEquals(
            sale(bread).copy(paymentMethodId = ON_ACCOUNT, credit = true),
            sale(bread).fix("con crédito"),
        )
    }

    @Test
    fun `credit without a credit method keeps the method`() {
        val catalog = PhraseCatalogs.spanish.copy(
            paymentMethods = PhraseCatalogs.spanish.paymentMethods.filterNot {
                it.id == ON_ACCOUNT
            },
        )
        assertEquals(
            sale(bread).copy(paymentMethodId = CASH, credit = true),
            sale(bread).copy(paymentMethodId = CASH).fix("fiado", catalog),
        )
    }

    @Test
    fun `a method without a credit method in the catalog never sets credit`() {
        val catalog = PhraseCatalogs.spanish.copy(
            paymentMethods = PhraseCatalogs.spanish.paymentMethods.filterNot {
                it.id == ON_ACCOUNT
            },
        )
        assertEquals(
            sale(bread).copy(paymentMethodId = CASH),
            sale(bread).fix("en efectivo", catalog),
        )
    }

    @Test
    fun `several possible methods leave the payment to choose`() {
        val catalog = PhraseCatalogs.spanish.copy(
            paymentMethods = listOf(
                PhraseName(CASH, "cash", "Efectivo"),
                PhraseName(410L, null, "Tarjeta Visa"),
                PhraseName(411L, null, "Tarjeta Master"),
            ),
        )
        assertEquals(
            sale(bread),
            sale(bread).copy(paymentMethodId = CASH).fix("con tarjeta", catalog),
        )
    }

    @Test
    fun `an expense takes a method but never credit`() {
        val expense = PhraseDraft(PhraseIntent.EXPENSE, total = money(20000))
        assertEquals(expense.copy(paymentMethodId = CARD), expense.fix("con tarjeta"))
    }

    @Test
    fun `what does not apply to the operation is not understood`() {
        val baked = PhraseDraft(PhraseIntent.PRODUCTION, lines = listOf(bread))
        assertNull(baked.fix("en efectivo"))
        assertNull(baked.fix("fiado"))
        assertNull(baked.fix("todo por 3000"))
    }

    // Persona, categoría y totales.

    @Test
    fun `a customer is set on a sale`() {
        assertEquals(sale(bread).copy(partyId = CARLOS), sale(bread).fix("a Carlos"))
        assertEquals(sale(bread).copy(partyId = ROSA), sale(bread).fix("Doña Rosa"))
        assertEquals(sale(bread).copy(partyId = ROSA), sale(bread).fix("era doña Rosa"))
    }

    @Test
    fun `two possible customers leave the person to choose`() {
        assertEquals(sale(bread), sale(bread).copy(partyId = CARLOS).fix("a María"))
    }

    @Test
    fun `a supplier is set on a purchase and on a payment to it`() {
        val flour = line(item(WHEAT_FLOUR), units(1), KILOGRAM)
        assertEquals(
            purchase(flour).copy(partyId = RAMIRO),
            purchase(flour).fix("era de Don Ramiro"),
        )
        val payment = PhraseDraft(PhraseIntent.SUPPLIER_PAYMENT, total = money(50000))
        assertEquals(payment.copy(partyId = RAMIRO), payment.fix("era donde Don Ramiro"))
    }

    @Test
    fun `a person who is not in the catalog is not understood`() {
        assertNull(sale(bread).fix("a Pedro"))
    }

    @Test
    fun `an expense takes its category and its amount`() {
        val expense = PhraseDraft(PhraseIntent.EXPENSE, total = money(20000))
        assertEquals(expense.copy(categoryId = UTILITIES), expense.fix("era la luz"))
        assertEquals(expense.copy(total = money(25000)), expense.fix("no, eran 25000"))
        assertEquals(expense.copy(total = money(25000)), expense.fix("pagué 25000"))
        assertNull(expense.fix("eran 6 aguas"))
        assertNull(expense.fix("el pan fue 3"))
        assertNull(expense.fix("era hola"))
    }

    @Test
    fun `a customer payment takes its customer and its amount`() {
        val payment = PhraseDraft(PhraseIntent.CUSTOMER_PAYMENT, total = money(5000))
        assertEquals(
            payment.copy(partyId = ROSA, total = money(6000)),
            payment.fix("Doña Rosa, 6000"),
        )
    }

    @Test
    fun `a total for the whole sale`() {
        // Con una sola línea sin monto, el total también es el de la línea, como al leer la frase.
        val one = line(item(BREAD), units(2), lineTotal = money(30000))
        assertEquals(sale(one).copy(total = money(30000)), sale(bread).fix("todo por 30000"))
        val priced = line(item(BREAD), units(2), unitPrice = money(500))
        assertEquals(sale(priced).copy(total = money(12000)), sale(priced).fix("12000 en total"))
        val two = sale(bread, line(item(EGG), units(1)))
        assertEquals(two.copy(total = money(7000)), two.fix("todo por 7000"))
    }

    // Lo que no es una corrección.

    @Test
    fun `a new whole phrase is not a correction`() {
        assertNull(sale(bread).fix("vendí 3 panes y 2 gaseosas"))
    }

    @Test
    fun `an operation verb without products still corrects`() {
        assertEquals(sale(line(item(BREAD), units(3))), sale(bread).fix("no, vendí 3"))
    }

    @Test
    fun `what says nothing is not understood`() {
        assertNull(sale(bread).fix(""))
        assertNull(sale(bread).fix("no"))
        assertNull(sale(bread).fix("no, eran"))
        assertNull(sale(bread).fix("hola"))
        assertNull(sale(bread).fix("en total"))
        assertNull(sale(bread).fix("recibí 3"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `more money decimals than any currency are rejected`() {
        sale(
            bread,
        ).correctedBy("eran 3", PhraseLanguage.ES, COMMA_DECIMAL, 4, PhraseCatalogs.spanish)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative money decimals are rejected`() {
        sale(
            bread,
        ).correctedBy("eran 3", PhraseLanguage.ES, COMMA_DECIMAL, -1, PhraseCatalogs.spanish)
    }
}
