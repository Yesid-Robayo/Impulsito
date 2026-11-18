package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.UnfitQuantity
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BOX
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CABLE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CARD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CARLOS
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CASH
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CENTRAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CHOCO_CAKE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CORN_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.DOZEN
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EMPANADA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.FARMER_CHEESE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.GRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.HARDWARE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.LITER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA_T
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.METER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MILK
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MOZZARELLA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ON_ACCOUNT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.OWN_WALLET
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RAMIRO
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ROSA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SACK
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SALT_PEPPER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SCREW
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SMALL_JUICE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_BIG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_SMALL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.TRANSFER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHOLE_BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseSaleTest {
    private fun line(
        product: ProductMatch,
        quantity: QuantityMilli? = null,
        unit: Long? = null,
        unitPrice: Money? = null,
        lineTotal: Money? = null,
    ) = PhraseLine(product, quantity, unit, unitPrice, lineTotal)

    private fun found(id: Long) = ProductMatch.Found(id)

    private fun choose(vararg ids: Long) = ProductMatch.Choose(ids.toList())

    @Test
    fun `a sale with several lines, a fraction and a payment method`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(
                    line(found(BREAD), units(3)),
                    line(choose(SODA_SMALL, SODA_BIG), units(2)),
                    line(choose(FARMER_CHEESE, MOZZARELLA), QuantityMilli(500), KILOGRAM),
                ),
                payment = NameMatch.Found(CASH),
            ),
            spanish("vendí 3 panes, 2 gaseosas y medio kilo de queso en efectivo"),
        )
    }

    @Test
    fun `a list of products without a verb is a sale`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(line(found(BREAD), units(3)), line(found(EMPANADA), units(2))),
            ),
            spanish("3 panes y 2 empanadas"),
        )
    }

    @Test
    fun `a dictation error still finds the product`() {
        assertEquals(
            listOf(line(choose(WHEAT_FLOUR, CORN_FLOUR), units(1), KILOGRAM)),
            spanish("compré 1 kilo de hariana").lines,
        )
        assertEquals(
            listOf(line(found(WHOLE_BREAD), units(2))),
            spanish("vendí 2 panes intergrales").lines,
        )
    }

    @Test
    fun `an exact name wins over a longer one`() {
        assertEquals(listOf(line(found(BREAD), units(2))), spanish("vendí 2 pan").lines)
    }

    @Test
    fun `a sale only offers what is sold, and a purchase what is bought`() {
        assertEquals(listOf(line(found(CORN_FLOUR))), spanish("vendí harina").lines)
        assertEquals(listOf(line(choose(WHEAT_FLOUR, CORN_FLOUR))), spanish("compré harina").lines)
    }

    @Test
    fun `a product that the sale does not admit is still proposed if nothing else is close`() {
        assertEquals(listOf(line(found(WHEAT_FLOUR))), spanish("vendí harina de trigo").lines)
    }

    @Test
    fun `a name that matches nothing is a new product in singular`() {
        assertEquals(
            listOf(line(ProductMatch.Unknown("Torta de fresa"), units(2))),
            spanish("vendí 2 tortas de fresa").lines,
        )
    }

    @Test
    fun `a size word picks the product by its measure`() {
        assertEquals(
            listOf(line(found(SODA_BIG), units(2)), line(found(SODA_SMALL), units(1))),
            spanish("vendí 2 gaseosas grandes y una gaseosa pequeña").lines,
        )
    }

    @Test
    fun `a size word skips a product without a measure`() {
        assertEquals(listOf(line(found(SMALL_JUICE))), spanish("vendí jugo grande").lines)
    }

    @Test
    fun `a size word alone among products without a measure keeps the choice`() {
        assertEquals(
            listOf(line(choose(CHOCO_CAKE, PhraseCatalogs.VANILLA_CAKE))),
            spanish("vendí torta grande").lines,
        )
    }

    @Test
    fun `a number that belongs to a name stays in it`() {
        assertEquals(
            listOf(line(found(SODA_SMALL), units(2))),
            spanish("vendí 2 gaseosas de 400").lines,
        )
        assertEquals(
            listOf(line(found(SODA_BIG), units(1))),
            spanish("vendí una gaseosa de 1,5 litros").lines,
        )
        assertEquals(
            listOf(line(found(SCREW), units(10))),
            spanish("vendí 10 tornillos de un cuarto").lines,
        )
    }

    @Test
    fun `a number after the product that is in no name is the line total`() {
        assertEquals(
            listOf(line(found(BREAD), units(3), lineTotal = money(400))),
            spanish("vendí 3 panes 400").lines,
        )
    }

    @Test
    fun `a unit price, a line total and each`() {
        assertEquals(
            listOf(
                line(found(BREAD), units(3), unitPrice = money(500)),
                line(found(EMPANADA), units(2), lineTotal = money(3000)),
                line(found(EGG), units(4), unitPrice = money(800)),
                line(found(MILK), units(2), unitPrice = money(8000)),
            ),
            spanish(
                "vendí 3 panes a 500, 2 empanadas por 3000, 4 huevos c/u 800 " +
                    "y 2 leches en 8000 cada una",
            ).lines,
        )
    }

    @Test
    fun `each before the price makes it a unit price`() {
        assertEquals(
            listOf(line(found(EGG), units(4), unitPrice = money(800))),
            spanish("vendí 4 huevos cada uno a 800").lines,
        )
    }

    @Test
    fun `a price per unit says the unit when the line did not`() {
        assertEquals(
            listOf(line(found(FARMER_CHEESE), unit = KILOGRAM, unitPrice = money(20000))),
            spanish("vendí queso campesino a 20000 el kilo").lines,
        )
        assertEquals(
            listOf(line(found(FARMER_CHEESE), units(500), GRAM, unitPrice = money(20000))),
            spanish("vendí 500 gramos de queso campesino por 20000 el kilo").lines,
        )
    }

    @Test
    fun `an amount paid before the quantity is the line total`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(
                    line(
                        choose(WHEAT_FLOUR, CORN_FLOUR),
                        units(10),
                        KILOGRAM,
                        lineTotal = money(20000),
                    ),
                ),
            ),
            spanish("pagué 20000 por 10 kilos de harina"),
        )
        assertEquals(
            listOf(line(found(CORN_FLOUR), lineTotal = money(5000))),
            spanish("gasté 5000 en la harina de maíz").lines,
        )
    }

    @Test
    fun `a number with a currency or money slang is an amount, not a quantity`() {
        assertEquals(
            listOf(line(found(BREAD), lineTotal = money(2500))),
            spanish("vendí dos mil quinientos pesos de pan").lines,
        )
        assertEquals(
            listOf(line(found(FARMER_CHEESE), lineTotal = money(20000))),
            spanish("vendí 20 lucas de queso campesino").lines,
        )
    }

    @Test
    fun `the quantity can come after the product`() {
        assertEquals(
            listOf(line(found(WHEAT_FLOUR), units(10), KILOGRAM)),
            spanish("compré harina de trigo 10 kilos").lines,
        )
    }

    @Test
    fun `a dangling connector after a number is not a line total`() {
        assertEquals(listOf<PhraseLine>(), spanish("vendí 3 por").lines)
    }

    @Test
    fun `an amount without a product is the total of a sale`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(line(found(BREAD), units(3))),
                total = money(5000),
            ),
            spanish("vendí 3 panes, 5000"),
        )
    }

    @Test
    fun `a total for the whole operation before or after the amount`() {
        assertEquals(money(30000), spanish("todo por 30000 vendí 3 panes y 2 empanadas").total)
        assertEquals(money(12000), spanish("vendí 3 panes y 2 empanadas, 12000 en total").total)
        assertEquals(null, spanish("vendí 3 panes en total").total)
    }

    @Test
    fun `a customer by first name lets choose, and by full name finds one`() {
        assertEquals(
            NameMatch.Choose(listOf(MARIA, MARIA_T)),
            spanish("vendí 3 panes a María").party,
        )
        assertEquals(NameMatch.Found(MARIA_T), spanish("vendí 3 panes a María Torres").party)
        assertEquals(NameMatch.Found(ROSA), spanish("vendí 2 huevos a doña Rosa").party)
        assertEquals(NameMatch.Found(CARLOS), spanish("vendí un pan a carlos ruis").party)
    }

    @Test
    fun `someone who is not a customer is a new one`() {
        assertEquals(NameMatch.Unknown("Pedro Pérez"), spanish("vendí 2 panes a Pedro Pérez").party)
        assertEquals(NameMatch.Unknown("Don Luis"), spanish("vendí 2 panes a don Luis").party)
        assertEquals(NameMatch.Unknown("Don Ramiro"), spanish("vendí 2 panes a Don Ramiro").party)
        assertEquals(NameMatch.Unknown("Juan"), spanish("vendí 2 panes para juan").party)
    }

    @Test
    fun `an honorific alone is not a name`() {
        val reading = spanish("vendí 2 panes a don")
        assertEquals(null, reading.party)
        assertEquals(listOf(line(found(BREAD), units(2))), reading.lines)
    }

    @Test
    fun `a lowercase word after a preposition is not a name`() {
        assertEquals(null, spanish("vendí 2 panes a domicilio 3").party)
    }

    @Test
    fun `a purchase from a supplier, on credit`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(found(WHEAT_FLOUR), units(2), SACK)),
                payment = NameMatch.Found(ON_ACCOUNT),
                party = NameMatch.Found(RAMIRO),
                credit = true,
            ),
            spanish("me llegaron 2 bultos de harina de trigo donde Don Ramiro a crédito"),
        )
    }

    @Test
    fun `credit without an on account method keeps the payment empty`() {
        val catalog = PhraseCatalogs.spanish.copy(
            paymentMethods = PhraseCatalogs.spanish.paymentMethods.filter {
                it.id !=
                    ON_ACCOUNT
            },
        )
        val reading = spanish("vendí 2 panes fiado", catalog)
        assertEquals(true, reading.credit)
        assertEquals(null, reading.payment)
    }

    @Test
    fun `a payment method said keeps its own over credit`() {
        assertEquals(
            NameMatch.Found(CARD),
            spanish("vendí 2 panes fiado con tarjeta de crédito").payment,
        )
    }

    @Test
    fun `a payment method by its catalog name, by a synonym or not at all`() {
        assertEquals(NameMatch.Found(OWN_WALLET), spanish("vendí 2 panes por nequi").payment)
        assertEquals(NameMatch.Found(TRANSFER), spanish("vendí 2 panes transferencia").payment)
        assertEquals(null, spanish("vendí 2 panes por favor").payment)
    }

    @Test
    fun `a supplier store after en`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.PURCHASE,
                lines = listOf(line(found(CABLE), units(20), METER, lineTotal = money(50000))),
                party = NameMatch.Found(HARDWARE),
            ),
            spanish("compré 20 metros de cable en Ferretería Moderna por 50.000"),
        )
        assertEquals(
            NameMatch.Found(CENTRAL),
            spanish("compré 2 cajas de gaseosa de 400 a Distribuidora Central").party,
        )
    }

    @Test
    fun `a unit the product does not admit is flagged`() {
        val reading = spanish("vendí 2 litros de huevo")
        assertEquals(
            listOf(line(found(EGG), units(2)).copy(unfit = UnfitQuantity(units(2), LITER))),
            reading.lines,
        )
        // En la tarjeta, la cantidad queda por escribir en una unidad del huevo.
        val card = requireNotNull(reading.toDraft()).lines.single()
        assertEquals(null, card.quantity)
        assertEquals(UnfitQuantity(units(2), LITER), card.unfit)
        assertEquals(setOf(PhraseIssue.UNIT_NOT_FOR_PRODUCT), reading.issues)
    }

    @Test
    fun `a measured unit converts to the base of the product, a counted one does not`() {
        assertEquals(emptySet<PhraseIssue>(), spanish("vendí 500 gramos de queso campesino").issues)
        assertEquals(emptySet<PhraseIssue>(), spanish("vendí una docena de huevos").issues)
        // El pan no se lleva por docena: una docena son 12 panes, no una unidad que no admite.
        val dozen = spanish("vendí una docena de panes")
        assertEquals(emptySet<PhraseIssue>(), dozen.issues)
        assertEquals(listOf(line(found(BREAD), units(12))), dozen.lines)
        assertEquals(
            setOf(PhraseIssue.UNIT_NOT_FOR_PRODUCT),
            spanish("vendí 2 kilos de pan").issues,
        )
        assertEquals(emptySet<PhraseIssue>(), spanish("vendí 2 cajas de gaseosa de 400").issues)
        assertEquals(
            listOf(line(found(EGG), units(1), DOZEN)),
            spanish("vendí una docena de huevos").lines,
        )
        assertEquals(emptySet<PhraseIssue>(), spanish("vendí 2 kilos de queso").issues)
    }

    @Test
    fun `a vague quantity cannot be read`() {
        val reading = spanish("vendí unos panes")
        assertEquals(listOf(line(found(BREAD))), reading.lines)
        assertEquals(setOf(PhraseIssue.UNREADABLE_QUANTITY), reading.issues)
        assertEquals(listOf(line(found(BREAD), units(3))), spanish("vendí unos 3 panes").lines)
        assertEquals(setOf(PhraseIssue.UNREADABLE_QUANTITY), spanish("vendí 1.2.3 panes").issues)
    }

    @Test
    fun `a pair, a fraction after a unit and a unit without a number`() {
        assertEquals(
            listOf(
                line(found(EGG), units(2)),
                line(found(FARMER_CHEESE), QuantityMilli(1_500), KILOGRAM),
                line(found(MOZZARELLA), QuantityMilli(2_500), KILOGRAM),
                line(found(BREAD)).copy(unfit = UnfitQuantity(null, BOX)),
            ),
            spanish(
                "vendí un par de huevos, kilo y medio de queso campesino, " +
                    "2 kilos y medio de queso mozarela y caja de pan",
            ).lines,
        )
    }

    @Test
    fun `a separator inside a product name does not split the line`() {
        assertEquals(
            listOf(line(found(SALT_PEPPER), units(2))),
            spanish("vendí 2 sal y pimienta").lines,
        )
        assertEquals(
            listOf(line(found(BREAD), units(2)), line(found(EGG), units(3))),
            spanish("vendí 2 panes y 3 huevos y").lines,
        )
    }

    @Test
    fun `a container that is not a unit leaves the product inside`() {
        assertEquals(
            listOf(line(found(MILK), units(3))),
            spanish("vendí 3 botellas de leche").lines,
        )
        assertEquals(
            listOf(line(ProductMatch.Unknown("Botella de vino"), units(3))),
            spanish("vendí 3 botellas de vino").lines,
        )
        assertEquals(
            listOf(line(ProductMatch.Unknown("Botella"), units(3))),
            spanish("vendí 3 botellas de la").lines,
        )
        assertEquals(
            listOf(line(ProductMatch.Unknown("Botella leche"), units(3))),
            spanish("vendí 3 botellas leche").lines,
        )
        assertEquals(
            listOf(line(ProductMatch.Unknown("Mesa de leche"), units(3))),
            spanish("vendí 3 mesas de leche").lines,
        )
        assertEquals(
            listOf(line(ProductMatch.Unknown("Botella"), units(3))),
            spanish("vendí 3 botellas").lines,
        )
    }

    @Test
    fun `fillers and pronouns are ignored`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SALE,
                lines = listOf(line(found(BREAD), units(35))),
            ),
            spanish("eh bueno, pues hoy vendí treinta y cinco panes"),
        )
    }

    @Test
    fun `a misheard verb at the start is still read`() {
        assertEquals(PhraseIntent.SALE, spanish("bendí 3 panes").intent)
        assertEquals(PhraseIntent.SALE, spanish("queso campesino").intent)
        assertEquals(PhraseIssue.NOTHING_UNDERSTOOD, spanish("zzzzzz").issues.single())
        assertEquals(PhraseIssue.NOTHING_UNDERSTOOD, spanish("hola").issues.single())
        assertEquals(PhraseIssue.NOTHING_UNDERSTOOD, spanish("").issues.single())
    }
}
