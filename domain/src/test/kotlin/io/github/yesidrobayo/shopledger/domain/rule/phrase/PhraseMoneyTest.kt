package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CARD
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CARLOS
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.CENTRAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COMMA_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.MARIA_T
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.PETS
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RAMIRO
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.RENT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.ROSA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.TRANSFER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.TRANSPORT
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.UTILITIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.money
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhraseMoneyTest {
    @Test
    fun `paying a category is an expense, with or without an amount`() {
        assertEquals(
            PhraseReading(intent = PhraseIntent.EXPENSE, category = NameMatch.Found(RENT)),
            spanish("pagué el arriendo"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(TRANSPORT),
                total = money(20000),
            ),
            spanish("gasté 20000 en taxi"),
        )
    }

    @Test
    fun `a category without a verb is an expense`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.EXPENSE,
                category = NameMatch.Found(UTILITIES),
                total = money(80000),
            ),
            spanish("la luz 80000"),
        )
    }

    @Test
    fun `a category by its own name, renamed or created by the person`() {
        assertEquals(NameMatch.Found(PETS), spanish("pagué 5000 de mascotas").category)
        assertEquals(NameMatch.Found(UTILITIES), spanish("pagué servicios 9000").category)
    }

    @Test
    fun `a word after a quantity is not a category`() {
        val reading = spanish("pagué 6 taxis")
        assertEquals(PhraseIntent.EXPENSE, reading.intent)
        assertEquals(null, reading.category)
        assertEquals(money(6), reading.total)
    }

    @Test
    fun `paying a product that is bought is a purchase`() {
        assertEquals(PhraseIntent.PURCHASE, spanish("pagué 20000 por 10 kilos de harina").intent)
    }

    @Test
    fun `paying something that is not a product is an expense without category`() {
        assertEquals(
            PhraseReading(intent = PhraseIntent.EXPENSE, total = money(20000)),
            spanish("pagué 20000 de almuerzo"),
        )
    }

    @Test
    fun `paying a supplier with only an amount is a supplier payment`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SUPPLIER_PAYMENT,
                party = NameMatch.Found(RAMIRO),
                total = money(50000),
            ),
            spanish("le pagué 50000 a Don Ramiro"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.SUPPLIER_PAYMENT,
                party = NameMatch.Found(CENTRAL),
                total = money(100000),
                payment = NameMatch.Found(TRANSFER),
            ),
            spanish("aboné 100.000 a Distribuidora Central por transferencia"),
        )
    }

    @Test
    fun `settling with products named is a purchase`() {
        assertEquals(PhraseIntent.PURCHASE, spanish("aboné 3 huevos").intent)
        assertEquals(PhraseIntent.SUPPLIER_PAYMENT, spanish("aboné 5000").intent)
    }

    @Test
    fun `a customer paying is a customer payment`() {
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.CUSTOMER_PAYMENT,
                party = NameMatch.Choose(listOf(MARIA, MARIA_T)),
                total = money(20000),
            ),
            spanish("María me pagó 20000"),
        )
        assertEquals(
            PhraseReading(
                intent = PhraseIntent.CUSTOMER_PAYMENT,
                party = NameMatch.Found(ROSA),
                total = money(5000),
            ),
            spanish("doña Rosa abonó 5000"),
        )
        assertEquals(PhraseIntent.CUSTOMER_PAYMENT, spanish("me pagaron 3000").intent)
    }

    @Test
    fun `an unclear payment decides by who is named`() {
        assertEquals(PhraseIntent.CUSTOMER_PAYMENT, spanish("Carlos pagó 2000").intent)
        assertEquals(NameMatch.Found(CARLOS), spanish("Carlos pagó 2000").party)
        assertEquals(PhraseIntent.SUPPLIER_PAYMENT, spanish("pago a Don Ramiro 2000").intent)
        assertEquals(PhraseIntent.EXPENSE, spanish("pago 2000").intent)
        assertEquals(PhraseIntent.EXPENSE, spanish("pago la luz").intent)
        assertEquals(PhraseIntent.PURCHASE, spanish("pago 3 huevos").intent)
    }

    @Test
    fun `being paid for products is a sale`() {
        assertEquals(PhraseIntent.SALE, spanish("me pagaron 3 panes").intent)
    }

    @Test
    fun `a payment verb joins the operation of another verb`() {
        val reading = spanish("vendí 3 panes y me pagó con tarjeta")
        assertEquals(PhraseIntent.SALE, reading.intent)
        assertEquals(NameMatch.Found(CARD), reading.payment)
        assertEquals(PhraseIntent.SALE, spanish("vendí 3 panes, pagó en efectivo").intent)
        assertEquals(PhraseIntent.PURCHASE, spanish("compré 3 huevos y pagué con tarjeta").intent)
    }

    @Test
    fun `two operations in one phrase are mixed`() {
        val mixed = PhraseReading(intent = null, issues = setOf(PhraseIssue.MIXED_OPERATIONS))
        assertEquals(mixed, spanish("vendí 2 panes y compré harina"))
        assertEquals(mixed, spanish("vendí 2 panes y pagué la luz"))
        assertEquals(mixed, spanish("vendí 3 panes y se dañaron 2"))
    }

    @Test
    fun `an expense has no party and a payment no category`() {
        assertEquals(null, spanish("le pagué la luz a María").party)
        assertEquals(null, spanish("aboné 5000 del arriendo a Don Ramiro").category)
    }

    @Test
    fun `the money decimals turn the amount into minor units`() {
        fun read(decimals: Int) = PhraseInterpreter.read(
            "gasté 12,5 en taxi",
            PhraseLanguage.ES,
            COMMA_DECIMAL,
            decimals,
            PhraseCatalogs.spanish,
        ).total
        assertEquals(Money(13), read(0))
        assertEquals(Money(1250), read(2))
        assertEquals(Money(12500), read(3))
    }

    @Test
    fun `money decimals outside the supported range are rejected`() {
        for (decimals in listOf(-1, 4)) {
            assertThrows(IllegalArgumentException::class.java) {
                PhraseInterpreter.read(
                    "pan",
                    PhraseLanguage.ES,
                    COMMA_DECIMAL,
                    decimals,
                    PhraseCatalogs.spanish,
                )
            }
        }
    }
}
