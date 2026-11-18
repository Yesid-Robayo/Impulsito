package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COMMA_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POINT_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.english
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.portuguese
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Quién se nombra con lo que se llevó decide la operación: un cliente que pagó lo que se llevó hizo
 * una venta, y lo nombrado de un proveedor sin verbo es una compra. Con el catálogo de prueba.
 */
class PhrasePartyGoodsTest {
    private fun pt(text: String): PhraseReading =
        PhraseInterpreter.read(text, PhraseLanguage.PT, COMMA_DECIMAL, 2, portuguese)

    @Test
    fun `a customer who paid for what they took made a sale`() {
        assertEquals(PhraseIntent.SALE, english("Mary Smith paid 3 for a chocolate cake").intent)
        assertEquals(PhraseIntent.SALE, english("Miss Rose paid for 2 bread rolls").intent)
        assertEquals(PhraseIntent.SALE, pt("a Dona Rosa pagou 3 por um suco de laranja").intent)
        // Sin lo que se llevó, es un abono; sin cliente, lo de siempre.
        assertEquals(PhraseIntent.CUSTOMER_PAYMENT, english("Miss Rose paid 12").intent)
        assertEquals(PhraseIntent.EXPENSE, english("paid for the rent, 800").intent)
    }

    @Test
    fun `what a supplier brought, said without a verb, is a purchase`() {
        assertEquals(
            PhraseIntent.PURCHASE,
            english("3 kilos of wheat flour from Mr. Ramiro, 90").intent,
        )
        assertEquals(
            PhraseIntent.PURCHASE,
            spanish("3 kilos de harina de trigo de Don Ramiro").intent,
        )
        // A un cliente, o sin nadie, sigue siendo una venta.
        assertEquals(PhraseIntent.SALE, english("2 bread rolls for Mary Smith").intent)
        assertEquals(PhraseIntent.SALE, english("2 bread rolls").intent)
        // Si el nombre también es de un cliente, no se sabe de qué lado está: queda la venta.
        val both = english.copy(customers = english.customers + PhraseName(99L, null, "Mr. Ramiro"))
        val shared = PhraseInterpreter.read(
            "3 kilos of wheat flour from Mr. Ramiro",
            PhraseLanguage.EN,
            POINT_DECIMAL,
            2,
            both,
        )
        assertEquals(PhraseIntent.SALE, shared.intent)
        // Un proveedor solo, sin productos, no dice qué pasó.
        assertNull("Mr. Ramiro, 90", english("Mr. Ramiro, 90").intent)
    }

    @Test
    fun `how something sells or what it costs elsewhere is not an operation`() {
        assertNull(
            "the bread rolls are selling well",
            english("the bread rolls are selling well").intent,
        )
        assertNull("eggs are expensive now", english("eggs are expensive now").intent)
        assertNull(
            "took back 2 eggs from a customer",
            english("took back 2 eggs from a customer").intent,
        )
        assertNull("o ovo subiu no mercado", pt("o ovo subiu no mercado").intent)
        assertNull(
            "a Maria Souza trouxe de volta 3 ovos",
            pt("a Maria Souza trouxe de volta 3 ovos").intent,
        )
        // "passei no cartão" es cobrar, no hornear.
        assertEquals(PhraseIntent.SALE, pt("passei 3 ovos no cartão").intent)
    }
}
