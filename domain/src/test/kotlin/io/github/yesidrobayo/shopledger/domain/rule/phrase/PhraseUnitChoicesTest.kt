package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseConversion
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.phrase.SaidConversion
import io.github.yesidrobayo.shopledger.domain.model.phrase.UnfitQuantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Las unidades que se eligen en una línea de la tarjeta y la medida que se pregunta de una unidad
 * sin medida fija, como cuánto pesa una libra: los valores comunes, la línea convertida y la
 * conversión que queda guardada. Los productos son inventados.
 */
class PhraseUnitChoicesTest {
    private fun unit(id: Long, key: String?, kind: UnitKind, amount: Long?) =
        PhraseUnit(id, key, key ?: "Propia $id", key, kind, amount)

    private val units = listOf(
        unit(UNIT, "unit", UnitKind.COUNT, null),
        unit(KILOGRAM, "kilogram", UnitKind.MASS, 1_000_000L),
        unit(GRAM, "gram", UnitKind.MASS, 1_000L),
        unit(POUND, "pound", UnitKind.MASS, null),
        unit(OUNCE, "ounce", UnitKind.MASS, null),
        unit(LITER, "liter", UnitKind.VOLUME, 1_000_000L),
        unit(MILLILITER, "milliliter", UnitKind.VOLUME, 1_000L),
        unit(GALLON, "gallon", UnitKind.VOLUME, null),
        unit(METER, "meter", UnitKind.LENGTH, 1_000L),
        unit(YARD, null, UnitKind.LENGTH, null),
        unit(BOX, "box", UnitKind.COUNT, null),
    )

    private fun product(id: Long, base: Long, vararg conversions: PhraseConversion) = PhraseProduct(
        id = id,
        name = "Producto $id",
        baseUnitId = base,
        conversions = conversions.toList(),
        forSale = true,
        forPurchase = true,
        hasRecipe = false,
        stockMode = ProductStockMode.TRACKED,
    )

    private val catalog =
        PhraseCatalog(emptyList(), units, emptyList(), emptyList(), emptyList(), emptyList())

    private val byPound = product(1L, POUND)
    private val byKilo = product(2L, KILOGRAM, PhraseConversion(BOX, 12_000L))
    private val byUnit = product(3L, UNIT, PhraseConversion(BOX, 6_000L), PhraseConversion(99L, 1L))
    private val byGallon = product(4L, GALLON)
    private val byYard = product(5L, YARD)

    private fun said(milli: Long?, unitId: Long, price: Long? = null) = DraftLine(
        product = DraftTarget.Existing(1L),
        quantity = null,
        unitId = null,
        unitPrice = price?.let(::Money),
        unfit = UnfitQuantity(milli?.let(::QuantityMilli), unitId),
    )

    @Test
    fun `a product offers its own units and the measured ones of its class`() {
        assertEquals(
            listOf(KILOGRAM, BOX, GRAM),
            catalog.unitsFor(byKilo).map { it.id },
        )
        // Sin medida fija, solo las suyas; una conversión que ya no está en el catálogo no sale.
        assertEquals(listOf(UNIT, BOX), catalog.unitsFor(byUnit).map { it.id })
        assertEquals(listOf(POUND), catalog.unitsFor(byPound).map { it.id })
    }

    @Test
    fun `a unit without a fixed measure of the same class asks how much it measures`() {
        // 200 g de algo que se lleva por libra: cuánto pesa la libra, en gramos.
        assertEquals(
            MeasureQuestion(POUND, GRAM, listOf(500_000L, 453_590L)),
            catalog.measureQuestion(byPound, GRAM),
        )
        // 2 libras de algo que se lleva por kilo: lo mismo.
        assertEquals(
            MeasureQuestion(POUND, GRAM, listOf(500_000L, 453_590L)),
            catalog.measureQuestion(byKilo, POUND),
        )
        assertEquals(
            MeasureQuestion(OUNCE, GRAM, listOf(28_350L)),
            catalog.measureQuestion(byKilo, OUNCE),
        )
        assertEquals(
            MeasureQuestion(GALLON, LITER, listOf(3_785_000L, 4_546_000L)),
            catalog.measureQuestion(byGallon, MILLILITER),
        )
        // Una propia no trae valores, y se pregunta en la métrica de su clase.
        assertEquals(
            MeasureQuestion(YARD, METER, emptyList()),
            catalog.measureQuestion(byYard, METER),
        )
        // Sin el gramo en el catálogo, se pregunta en la unidad métrica de la línea.
        val noGram = catalog.copy(units = units.filterNot { it.id == GRAM })
        assertEquals(
            MeasureQuestion(POUND, KILOGRAM, listOf(500_000L, 453_590L)),
            noGram.measureQuestion(byPound, KILOGRAM),
        )
    }

    @Test
    fun `nothing is asked across classes, between two measured or two loose units`() {
        assertNull(catalog.measureQuestion(byPound, LITER))
        assertNull(catalog.measureQuestion(byKilo, GRAM))
        assertNull(catalog.measureQuestion(byPound, OUNCE))
        assertNull(catalog.measureQuestion(byPound, POUND))
        assertNull(catalog.measureQuestion(byPound, 99L))
        assertNull(catalog.measureQuestion(product(6L, 99L), GRAM))
    }

    @Test
    fun `a measured line goes to the base unit and keeps its conversion`() {
        // 200 g con la libra de 500 g son 0,400 libras; el kilo trae 2 libras.
        val measured = said(200_000L, GRAM, price = 10).measuredWith(byPound, catalog, 500_000L)
        assertEquals(QuantityMilli(400), measured?.line?.quantity)
        assertNull(measured?.line?.unfit)
        assertNull(measured?.line?.unitId)
        // 10 el gramo son 5000 la libra.
        assertEquals(Money(5_000), measured?.line?.unitPrice)
        assertEquals(SaidConversion(1L, KILOGRAM, 2_000L), measured?.conversion)
        // Con la libra de 453,59 g, el kilo trae 2,205 libras.
        assertEquals(
            SaidConversion(1L, KILOGRAM, 2_205L),
            said(200_000L, GRAM).measuredWith(byPound, catalog, 453_590L)?.conversion,
        )
        // 2 libras de lo que se lleva por kilo son 0,907 kg, y la libra trae 0,454 kg.
        val pounds = said(2_000L, POUND).copy(product = DraftTarget.Existing(2L))
            .measuredWith(byKilo, catalog, 453_590L)
        assertEquals(QuantityMilli(907), pounds?.line?.quantity)
        assertEquals(SaidConversion(2L, POUND, 454L), pounds?.conversion)
        // La unidad sola, sin número, queda sin cantidad.
        val bare = said(null, GRAM).measuredWith(byPound, catalog, 500_000L)
        assertNull(bare?.line?.quantity)
    }

    @Test
    fun `a measure that does not apply or does not fit is refused`() {
        val line = said(200_000L, GRAM)
        assertNull(line.copy(unfit = null).measuredWith(byPound, catalog, 500_000L))
        assertNull(said(2_000L, LITER).measuredWith(byPound, catalog, 500_000L))
        assertNull(line.measuredWith(byPound, catalog, 0L))
        // Una cantidad que redondea a cero, un precio que pasa su tope.
        assertNull(said(1L, GRAM).measuredWith(byPound, catalog, 500_000_000L))
        assertNull(
            said(null, GRAM, price = 999_999_999_999L).measuredWith(byPound, catalog, 500_000L),
        )
        // Una libra enorme deja el kilo en cero libras; una diminuta, un factor sin tope.
        assertNull(said(null, GRAM).measuredWith(byPound, catalog, 999_999_999_999L))
        assertNull(
            said(null, POUND).copy(product = DraftTarget.Existing(2L))
                .measuredWith(byKilo, catalog, 1L),
        )
        assertNull(
            said(null, POUND).copy(product = DraftTarget.Existing(2L))
                .measuredWith(byKilo, catalog, 999_999_999_999_999L),
        )
    }

    @Test
    fun `a stored metric conversion converts a base without a fixed measure`() {
        val units = catalog.units.associateBy { it.id }
        // El kilo guardado en 2,205 libras: 200 g son 0,441 libras.
        val kept =
            product(7L, POUND, PhraseConversion(BOX, 3_000L), PhraseConversion(KILOGRAM, 2_205L))
        assertEquals(QuantityMilli(441), baseQuantity(kept, GRAM, QuantityMilli(200_000), units))
        // Sin una conversión métrica de su clase no se convierte.
        val loose = product(8L, POUND, PhraseConversion(OUNCE, 62L), PhraseConversion(99L, 1L))
        assertNull(baseQuantity(loose, GRAM, QuantityMilli(200_000), units))
        assertNull(baseQuantity(product(9L, POUND), GRAM, QuantityMilli(200_000), units))
        // Ni una unidad de otra clase, ni una sin medida.
        assertNull(baseQuantity(kept, LITER, QuantityMilli(200_000), units))
        assertNull(baseQuantity(kept, YARD, QuantityMilli(200_000), units))
    }

    private companion object {
        const val UNIT = 1L
        const val KILOGRAM = 2L
        const val GRAM = 3L
        const val POUND = 4L
        const val OUNCE = 5L
        const val LITER = 6L
        const val MILLILITER = 7L
        const val GALLON = 8L
        const val METER = 9L
        const val YARD = 10L
        const val BOX = 11L
    }
}
