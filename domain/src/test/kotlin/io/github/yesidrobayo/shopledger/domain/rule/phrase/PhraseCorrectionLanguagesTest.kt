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
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.BREAD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CASH
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COMMA_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COOKIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.DOZEN
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.KILOGRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ON_ACCOUNT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ORANGE_JUICE
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POINT_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POUND
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RAMIRO
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ROSA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_BIG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.SODA_SMALL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La corrección con una segunda frase, en inglés y en portugués. */
class PhraseCorrectionLanguagesTest {
    private fun PhraseDraft.english(text: String): PhraseDraft? =
        correctedBy(text, PhraseLanguage.EN, POINT_DECIMAL, 2, PhraseCatalogs.english)

    private fun PhraseDraft.portuguese(
        text: String,
        catalog: PhraseCatalog = PhraseCatalogs.portuguese,
    ): PhraseDraft? = correctedBy(text, PhraseLanguage.PT, COMMA_DECIMAL, 2, catalog)

    private fun item(id: Long) = DraftTarget.Existing(id)

    private fun line(
        product: DraftTarget?,
        quantity: QuantityMilli? = null,
        unit: Long? = null,
        unitPrice: Money? = null,
    ) = DraftLine(product, quantity, unit, unitPrice)

    private fun sale(vararg lines: DraftLine) =
        PhraseDraft(PhraseIntent.SALE, lines = lines.toList())

    private fun purchase(vararg lines: DraftLine) =
        PhraseDraft(PhraseIntent.PURCHASE, lines = lines.toList())

    private val eggs = line(item(EGG), units(2))
    private val smallSoda = line(item(SODA_SMALL), units(2))
    private val bread = line(item(BREAD), units(1))

    // Inglés.

    @Test
    fun `english corrects the quantity and the unit`() {
        assertEquals(sale(line(item(EGG), units(4))), sale(eggs).english("actually 4"))
        assertEquals(sale(line(item(EGG), units(3), DOZEN)), sale(eggs).english("make it 3 dozen"))
        assertEquals(sale(line(item(EGG), QuantityMilli(1_500))), sale(eggs).english("its 1.5"))
        assertEquals(
            purchase(line(item(WHEAT_FLOUR), units(5), POUND)),
            purchase(line(item(WHEAT_FLOUR), units(2), KILOGRAM)).english("no, it was 5 pounds"),
        )
    }

    @Test
    fun `english corrects the product`() {
        assertEquals(
            sale(line(item(SODA_BIG), units(2))),
            sale(smallSoda).english("it was the big one"),
        )
        assertEquals(
            sale(eggs, line(item(SODA_BIG), units(2))),
            sale(eggs, smallSoda).english("the soda was the big one"),
        )
    }

    @Test
    fun `english removes and adds lines`() {
        assertEquals(sale(eggs), sale(eggs, line(null, units(2))).english("remove the soda"))
        assertEquals(sale(bread, line(item(EGG), units(2))), sale(bread).english("add 2 eggs"))
        assertEquals(
            sale(bread, line(item(COOKIES), units(1))),
            sale(bread).english("and also a cookie"),
        )
    }

    @Test
    fun `english corrects the price, the payment and the person`() {
        assertEquals(
            sale(line(item(EGG), units(2), unitPrice = money(6))),
            sale(eggs).english("at 6 each"),
        )
        assertEquals(sale(eggs).copy(paymentMethodId = CASH), sale(eggs).english("it was cash"))
        assertEquals(
            sale(eggs).copy(paymentMethodId = ON_ACCOUNT, credit = true),
            sale(eggs).english("on credit"),
        )
        assertEquals(sale(eggs).copy(partyId = MARIA), sale(eggs).english("to Mary"))
    }

    @Test
    fun `an english new phrase is not a correction`() {
        assertNull(sale(eggs).english("i sold 3 eggs"))
    }

    @Test
    fun `english forgets the prices of the old product or unit`() {
        assertEquals(
            sale(line(item(SODA_BIG), units(2))),
            sale(line(item(SODA_SMALL), units(2), unitPrice = money(3)))
                .english("it was the big one"),
        )
        assertEquals(
            purchase(line(item(WHEAT_FLOUR), units(5), POUND)),
            purchase(line(item(WHEAT_FLOUR), units(2), KILOGRAM, money(4)))
                .english("no, it was 5 pounds"),
        )
    }

    // Portugués.

    @Test
    fun `portuguese corrects the quantity and the product`() {
        assertEquals(sale(line(item(EGG), units(3))), sale(eggs).portuguese("na verdade 3"))
        assertEquals(
            sale(line(item(SODA_BIG), units(2))),
            sale(smallSoda).portuguese("era o grande"),
        )
    }

    @Test
    fun `portuguese forgets the prices of the old product`() {
        assertEquals(
            sale(line(item(SODA_BIG), units(2))),
            sale(line(item(SODA_SMALL), units(2), unitPrice = money(3)))
                .portuguese("era o grande"),
        )
        assertEquals(
            sale(line(item(SODA_BIG), units(2), unitPrice = money(5))),
            sale(line(item(SODA_SMALL), units(2), unitPrice = money(3)))
                .portuguese("era o grande a 5"),
        )
    }

    @Test
    fun `portuguese removes and adds lines`() {
        assertEquals(sale(eggs), sale(bread, eggs).portuguese("tira o pão"))
        assertEquals(sale(bread, line(item(EGG), units(2))), sale(bread).portuguese("mais 2 ovos"))
        assertEquals(
            sale(bread, line(item(ORANGE_JUICE), units(1))),
            sale(bread).portuguese("adiciona 1 suco de laranja"),
        )
    }

    @Test
    fun `portuguese corrects the price, the payment and the person`() {
        assertEquals(
            sale(line(item(EGG), units(2), unitPrice = money(6))),
            sale(eggs).portuguese("a 6 cada"),
        )
        assertEquals(sale(eggs).copy(paymentMethodId = CASH), sale(eggs).portuguese("em dinheiro"))
        assertEquals(sale(eggs).copy(partyId = ROSA), sale(eggs).portuguese("para Dona Rosa"))
        assertEquals(
            purchase(bread).copy(partyId = RAMIRO),
            purchase(bread).portuguese("era do Seu Ramiro"),
        )
    }

    @Test
    fun `a payment method is understood only if the catalog has it`() {
        val pix = 405L
        val catalog = PhraseCatalogs.portuguese.copy(
            paymentMethods = PhraseCatalogs.portuguese.paymentMethods + PhraseName(
                pix,
                null,
                "Pix",
            ),
        )
        assertEquals(
            sale(eggs).copy(paymentMethodId = pix),
            sale(eggs).portuguese("foi no pix", catalog),
        )
        assertNull(sale(eggs).portuguese("foi no pix"))
    }

    @Test
    fun `a portuguese new phrase is not a correction`() {
        assertNull(sale(eggs).portuguese("vendi 3 pães"))
    }
}
