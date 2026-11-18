package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.UnfitQuantity
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COMMA_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.COOKIES
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EGG
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.EMPANADA
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.GRAM
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.LITER
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.POINT_DECIMAL
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.WHEAT_FLOUR
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.spanish
import io.github.yesidrobayo.shopledger.domain.rule.phrase.PhraseCatalogs.units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que se dice que llevó una producción, con el catálogo de prueba en español: en la misma frase
 * después de "con", o con la tarjeta abierta. Las galletas no tienen receta; la empanada sí.
 */
class PhraseProductionInputsTest {
    private fun PhraseDraft.fix(text: String): PhraseDraft? =
        correctedBy(text, PhraseLanguage.ES, COMMA_DECIMAL, 2, spanish)

    private fun found(id: Long, milli: Long?, unitId: Long? = null) =
        PhraseLine(ProductMatch.Found(id), milli?.let(::QuantityMilli), unitId, null, null)

    private fun line(id: Long?, milli: Long?, unitId: Long? = null) = DraftLine(
        id?.let { DraftTarget.Existing(it) },
        milli?.let(::QuantityMilli),
        unitId,
    )

    private fun production(made: DraftLine, vararg inputs: DraftLine) = PhraseDraft(
        PhraseIntent.PRODUCTION,
        lines = listOf(made),
        inputs = inputs.toList(),
    )

    private val cookies = production(line(COOKIES, 2_000))
    private val empanadas = production(line(EMPANADA, 10_000))

    // En la misma frase.

    @Test
    fun `the inputs said after con are read without prices and no recipe is missing`() {
        val reading =
            spanish(
                "hice 2 paquetes de galletas con 400 gramos de harina de trigo a 5000 y 3 huevos",
            )

        assertEquals(PhraseIntent.PRODUCTION, reading.intent)
        assertEquals(ProductMatch.Found(COOKIES), reading.lines.single().product)
        assertEquals(units(2), reading.lines.single().quantity)
        assertEquals(
            listOf(found(WHEAT_FLOUR, 400_000, GRAM), found(EGG, 3_000)),
            reading.inputs,
        )
        assertTrue(PhraseIssue.NO_RECIPE !in reading.issues)
    }

    @Test
    fun `without inputs a product without recipe is still warned about`() {
        val reading = spanish("hice 2 paquetes de galletas")

        assertEquals(emptyList<PhraseLine>(), reading.inputs)
        assertTrue(PhraseIssue.NO_RECIPE in reading.issues)
    }

    @Test
    fun `an input in another class of unit keeps its quantity as a gap`() {
        val reading = spanish("hice una empanada con 2 litros de harina de trigo")

        assertEquals(
            listOf(found(WHEAT_FLOUR, null).copy(unfit = UnfitQuantity(units(2), LITER))),
            reading.inputs,
        )
        assertTrue(PhraseIssue.UNIT_NOT_FOR_PRODUCT in reading.issues)
    }

    @Test
    fun `an input that does not exist is offered as a new input`() {
        val reading = spanish("hice una empanada con 200 g de mantequilla")
        val input = reading.inputs.single()

        val unknown = input.product as ProductMatch.Unknown
        assertEquals(NewProductTraits(usage = ProductUsage.INPUT), unknown.traits)
        assertEquals(QuantityMilli(200_000), input.quantity)
        val draft = requireNotNull(reading.toDraft())
        val created = draft.inputs.single().product as DraftTarget.New
        assertEquals(ProductUsage.INPUT, created.traits.usage)
        // Se crea al registrar la producción, en la misma escritura.
        assertEquals(emptySet<DraftGap>(), draft.gaps())
    }

    // Lo que falta.

    @Test
    fun `a production without recipe or inputs lacks the inputs, and each input needs its data`() {
        val noRecipe: (Long) -> Boolean = { it != COOKIES }

        assertEquals(setOf(DraftGap.INPUTS), cookies.gaps(noRecipe))
        assertEquals(emptySet<DraftGap>(), cookies.gaps())
        assertEquals(
            emptySet<DraftGap>(),
            production(line(COOKIES, 2_000), line(EGG, 3_000)).gaps(noRecipe),
        )
        assertEquals(
            setOf(DraftGap.PRODUCT, DraftGap.QUANTITY),
            production(line(COOKIES, 2_000), line(null, 1_000), line(EGG, null)).gaps(noRecipe),
        )
        assertEquals(setOf(DraftGap.PRODUCT), production(line(null, 1_000)).gaps(noRecipe))
    }

    // Con la tarjeta abierta.

    @Test
    fun `with the card open, what it takes is added as inputs`() {
        val expected = listOf(line(WHEAT_FLOUR, 200_000, GRAM), line(EGG, 3_000))

        assertEquals(expected, cookies.fix("lleva 200 g de harina de trigo y 3 huevos")?.inputs)
        assertEquals(expected, cookies.fix("200 g de harina de trigo y 3 huevos")?.inputs)
        assertEquals(
            expected,
            cookies.fix("las galletas llevan 200 g de harina de trigo y 3 huevos")?.inputs,
        )
        assertEquals(
            expected,
            cookies.fix("lleva 200 g de harina de trigo y lleva 3 huevos")?.inputs,
        )
        assertEquals(
            expected,
            cookies.fix("las galletas, llevan 200 g de harina de trigo y 3 huevos")?.inputs,
        )
        // Un insumo que no existe, dicho con su cantidad, se ofrece crear como insumo.
        val butter = cookies.fix("lleva 200 g de mantequilla")?.inputs?.single()
        assertEquals(QuantityMilli(200_000), butter?.quantity)
        assertEquals(ProductUsage.INPUT, (butter?.product as DraftTarget.New).traits.usage)
    }

    @Test
    fun `with a recipe, con opens the inputs and a bare line adds to the production`() {
        assertEquals(
            listOf(line(WHEAT_FLOUR, 250_000, GRAM)),
            empanadas.fix("con 250 g de harina de trigo")?.inputs,
        )
        val bare = empanadas.fix("2 huevos")
        assertEquals(emptyList<DraftLine>(), bare?.inputs.orEmpty())
    }

    @Test
    fun `once there are inputs, more inputs join them and a named one is corrected or summed`() {
        val withFlour = production(line(EMPANADA, 10_000), line(WHEAT_FLOUR, 250_000, GRAM))

        assertEquals(
            listOf(line(WHEAT_FLOUR, 250_000, GRAM), line(EGG, 3_000)),
            withFlour.fix("y 3 huevos")?.inputs,
        )
        assertEquals(
            listOf(line(WHEAT_FLOUR, 250_000, GRAM), line(EGG, 2_000)),
            withFlour.fix("agrega 2 huevos")?.inputs,
        )
        assertEquals(
            listOf(line(WHEAT_FLOUR, 300_000, GRAM)),
            withFlour.fix("no, eran 300 g de harina de trigo")?.inputs,
        )
        assertEquals(
            listOf(line(WHEAT_FLOUR, 350_000, GRAM)),
            withFlour.fix("otros 100 g de harina de trigo")?.inputs,
        )
        // Lo producido nombrado corrige lo producido, no los insumos.
        val made = withFlour.fix("eran 12 empanadas")
        assertEquals(units(12), made?.lines?.single()?.quantity)
        assertEquals(withFlour.inputs, made?.inputs)
    }

    @Test
    fun `what is not an input of this production is left to the other corrections`() {
        // Otra receta, otro verbo, o palabras que no son productos ni dicen cuánto.
        assertEquals(
            emptyList<DraftLine>(),
            cookies.fix("la empanada lleva 2 huevos")?.inputs.orEmpty(),
        )
        assertNull(cookies.fix("vendí 2 huevos"))
        assertEquals(emptyList<DraftLine>(), cookies.fix("gracias")?.inputs.orEmpty())
        assertEquals(emptyList<DraftLine>(), cookies.fix("no")?.inputs.orEmpty())
        // Una venta, una producción de dos productos o de uno por elegir no llevan insumos.
        val sale = PhraseDraft(PhraseIntent.SALE, lines = listOf(line(EGG, 1_000)))
        assertEquals(emptyList<DraftLine>(), sale.fix("200 g de harina de trigo")?.inputs.orEmpty())
        val two = cookies.copy(lines = cookies.lines + line(EMPANADA, 1_000))
        assertEquals(emptyList<DraftLine>(), two.fix("lleva 3 huevos")?.inputs.orEmpty())
        // Lo producido por elegir también lleva lo que se dice.
        val choosing = production(line(null, 1_000))
        assertEquals(listOf(line(EGG, 3_000)), choosing.fix("lleva 3 huevos")?.inputs)
        assertNull(choosing.fix("la empanada lleva 3 huevos"))
    }

    @Test
    fun `something new that is produced is created with what it takes`() {
        // Un turrón que no existe: se crea al registrar, y lo dicho queda como su receta.
        val draft = requireNotNull(spanish("hice un turrón con 200 g de harina de trigo").toDraft())
        assertEquals(DraftTarget.New("Turrón"), draft.lines.single().product)
        assertEquals(listOf(line(WHEAT_FLOUR, 200_000, GRAM)), draft.inputs)
        assertEquals(emptySet<DraftGap>(), draft.gaps())
    }

    @Test
    fun `what it takes is said for a product that left the catalog, as if it had no recipe`() {
        val gone = production(line(9_999L, 1_000))
        assertEquals(listOf(line(EGG, 3_000)), gone.fix("3 huevos")?.inputs)
    }

    @Test
    fun `what it takes is said after the card of something new`() {
        val nougat = production(DraftLine(DraftTarget.New("Turrón"), units(1), null))
        assertEquals(listOf(line(EGG, 3_000)), nougat.fix("lleva 3 huevos")?.inputs)
        // Sin verbo también: algo nuevo no tiene receta.
        assertEquals(listOf(line(EGG, 3_000)), nougat.fix("3 huevos")?.inputs)
        assertEquals(listOf(line(EGG, 3_000)), nougat.fix("el turrón lleva 3 huevos")?.inputs)
        // Otro producto antes de "lleva" es otra receta.
        assertNull(nougat.fix("la empanada lleva 3 huevos"))
    }

    @Test
    fun `a pronoun before the verb does not name another product`() {
        val english = production(line(COOKIES, 2_000))
            .correctedBy(
                "it takes 3 eggs",
                PhraseLanguage.EN,
                POINT_DECIMAL,
                2,
                PhraseCatalogs.english,
            )

        assertEquals(listOf(line(EGG, 3_000)), english?.inputs)
    }

    @Test
    fun `a product missing from the catalog has no recipe to rely on`() {
        val missing = production(line(GONE, 1_000))

        assertEquals(listOf(line(EGG, 3_000)), missing.fix("3 huevos")?.inputs)
    }

    private companion object {
        const val GONE = 999L
    }
}
