package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.english
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.portuguese
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que nunca se registra desde una frase, aunque nombre productos o montos: preguntas,
 * devoluciones, anular, retiros y préstamos, deudas dichas como hecho, cotizaciones, listas de
 * precios, porcentajes, negaciones y lo que todavía no pasó.
 */
class PhraseRefusalTest {
    private val nothing = PhraseReading(
        intent = null,
        issues = setOf(PhraseIssue.NOTHING_UNDERSTOOD),
    )

    @Test
    fun `questions are not registered`() {
        assertEquals(nothing, spanish("¿a cómo está el pan?"))
        assertEquals(nothing, spanish("a cómo está el pan"))
        assertEquals(nothing, spanish("cuántas empanadas me quedan"))
        assertEquals(nothing, english("how much is the bread roll"))
        assertEquals(nothing, portuguese("quanto custa o pão"))
    }

    @Test
    fun `returns, voids, withdrawals and loans are not registered`() {
        assertEquals(nothing, spanish("me devolvieron 2 panes"))
        assertEquals(nothing, spanish("anula la última venta"))
        assertEquals(nothing, spanish("saqué 50 mil de la caja para mí"))
        assertEquals(nothing, spanish("me prestaron 500 mil"))
        assertEquals(nothing, spanish("Carlos me debe 20 mil"))
        assertEquals(nothing, english("a customer returned 2 rolls"))
        assertEquals(nothing, english("I got a loan of 500"))
        assertEquals(nothing, portuguese("me devolveram 2 pães"))
    }

    @Test
    fun `quotes, price lists and changes of every price are not registered`() {
        assertEquals(nothing, spanish("cotiza 20 empanadas para María Gómez"))
        assertEquals(nothing, spanish("en la lista mayorista el pan vale 500"))
        assertEquals(nothing, spanish("sube todos los precios un 10 %"))
        assertEquals(nothing, spanish("sube el pan un 10 por ciento"))
        assertEquals(nothing, english("raise the bread roll by 10 percent"))
        assertEquals(nothing, portuguese("aumenta o pão em 10 por cento"))
    }

    @Test
    fun `a percent word that follows no number is not a percentage`() {
        // "por ciento veinte" es "por 120": sin una cifra delante no es un porcentaje.
        assertEquals(PhraseIntent.SALE, spanish("vendí 2 panes por ciento veinte").intent)
    }

    @Test
    fun `a negated verb is not registered, a correction lead is`() {
        assertEquals(nothing, spanish("no vendí nada hoy"))
        assertEquals(nothing, spanish("todavía no me paga doña Rosa"))
        assertEquals(nothing, english("I didn't sell anything today"))
        assertEquals(nothing, portuguese("não vendi nada hoje"))
        // Con una coma, el "no" no niega el verbo: corrige.
        assertEquals(PhraseIntent.SALE, spanish("no, vendí 3 panes").intent)
    }

    @Test
    fun `what has not happened yet is not registered`() {
        assertEquals(nothing, spanish("mañana compro 3 bultos de harina de trigo"))
        assertEquals(nothing, spanish("tengo que pagar el arriendo"))
        assertEquals(nothing, spanish("la semana que viene compro arroz"))
        assertEquals(nothing, english("I need to pay the rent"))
        assertEquals(nothing, portuguese("amanhã eu compro 3 sacos de farinha de trigo"))
    }

    @Test
    fun `a verb that holds the whole refusal phrase still counts`() {
        // "voy a vender" crea un producto, y "hice una venta de" es una venta.
        assertEquals(
            PhraseIntent.NEW_PRODUCT,
            spanish("voy a vender empanadas de pollo a 3000").intent,
        )
        assertEquals(nothing, spanish("hice una venta grande hoy"))
        assertEquals(PhraseIntent.SALE, spanish("hice una venta de 3 panes").intent)
    }

    @Test
    fun `an operation of goods without goods is not registered`() {
        assertEquals(nothing, spanish("me robaron 50 mil"))
        assertEquals(nothing, english("sold nothing today"))
        // Una venta con su total sí: la persona elige los productos en la tarjeta.
        assertEquals(
            PhraseReading(intent = PhraseIntent.SALE, total = PhraseCatalogs.money(50_000)),
            spanish("vendí 50 mil"),
        )
    }

    @Test
    fun `a price change of an expense category is not registered`() {
        assertEquals(nothing, spanish("el arriendo sube a 900 mil"))
    }
}
