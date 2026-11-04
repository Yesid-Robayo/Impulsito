package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitConversionResult.AboveLineLimit
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitConversionResult.Converted
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitConversionResult.InvalidFactor
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitConversionResult.NotPositive
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitConversionResult.RoundsToZero
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Cantidades en milésimas: 3 cajas son 3000, y 250 g son 250000 en la unidad gramo. Cada valor
// esperado está calculado a mano, nunca con el código que se prueba.
class UnitConversionTest {
    private fun convert(entered: Long, factorMilli: Long): UnitConversionResult =
        UnitConversion.toBaseQuantity(QuantityMilli(entered), factorMilli)

    @Test
    fun `three boxes of twenty four are seventy two units`() {
        // Producto en UNIT con caja de 24: factor 24000. 3000 × 24000 / 1000 = 72000.
        assertEquals(Converted(QuantityMilli(72_000L)), convert(3_000L, 24_000L))
        // Una sola caja, y media caja de un producto que la vende partida: 12 unidades.
        assertEquals(Converted(QuantityMilli(24_000L)), convert(1_000L, 24_000L))
        assertEquals(Converted(QuantityMilli(12_000L)), convert(500L, 24_000L))
    }

    @Test
    fun `two hundred fifty grams are a quarter kilogram`() {
        // Producto en KILOGRAM, línea en GRAM: factor 1. 250000 × 1 / 1000 = 250, es decir 0,250 kg.
        assertEquals(Converted(QuantityMilli(250L)), convert(250_000L, 1L))
        // Un kilo justo, en gramos.
        assertEquals(Converted(QuantityMilli(1_000L)), convert(1_000_000L, 1L))
    }

    @Test
    fun `the base unit itself does not change the quantity`() {
        assertEquals(1_000L, UnitConversion.BASE_UNIT_FACTOR_MILLI)
        assertEquals(
            Converted(QuantityMilli(1L)),
            convert(1L, UnitConversion.BASE_UNIT_FACTOR_MILLI),
        )
        assertEquals(
            Converted(QuantityMilli(2_500L)),
            convert(2_500L, UnitConversion.BASE_UNIT_FACTOR_MILLI),
        )
    }

    @Test
    fun `a dozen and a pound convert with their own factor`() {
        // Docena de un producto en UNIT: factor 12000. Dos docenas son 24 unidades.
        assertEquals(Converted(QuantityMilli(24_000L)), convert(2_000L, 12_000L))
        // Libra de un producto en KILOGRAM, con el factor 454 que escribió la persona.
        // 1500 × 454 / 1000 = 681.
        assertEquals(Converted(QuantityMilli(681L)), convert(1_500L, 454L))
    }

    @Test
    fun `the conversion rounds half away from zero`() {
        // 1,5 g en kilogramos: 1500 × 1 / 1000 = 1,5, que sube a 2. 1,499 g baja a 1.
        assertEquals(Converted(QuantityMilli(2L)), convert(1_500L, 1L))
        assertEquals(Converted(QuantityMilli(1L)), convert(1_499L, 1L))
        // El empate más chico: 0,5 g da una milésima de kilogramo.
        assertEquals(Converted(QuantityMilli(1L)), convert(500L, 1L))
        // 7 × 333 / 1000 = 2,331, que baja a 2.
        assertEquals(Converted(QuantityMilli(2L)), convert(7L, 333L))
    }

    @Test
    fun `a quantity that rounds to zero is rejected`() {
        // 0,499 g en kilogramos: 499 × 1 / 1000 = 0,499, que redondea a cero.
        assertEquals(RoundsToZero, convert(499L, 1L))
        assertEquals(RoundsToZero, convert(1L, 1L))
    }

    @Test
    fun `the factor goes from one thousandth to the line limit`() {
        assertTrue(UnitConversion.isValidFactor(1L))
        assertTrue(UnitConversion.isValidFactor(1_000_000_000L))
        assertFalse(UnitConversion.isValidFactor(0L))
        assertFalse(UnitConversion.isValidFactor(-24_000L))
        assertFalse(UnitConversion.isValidFactor(1_000_000_001L))

        assertEquals(InvalidFactor, convert(3_000L, 0L))
        assertEquals(InvalidFactor, convert(3_000L, -1L))
        assertEquals(InvalidFactor, convert(3_000L, 1_000_000_001L))
        // El factor manda aunque la cantidad tampoco sirva.
        assertEquals(InvalidFactor, convert(0L, 0L))
        // El factor máximo: una unidad entera trae un millón de unidades base, el tope de la línea.
        assertEquals(Converted(QuantityMilli(1_000_000_000L)), convert(1_000L, 1_000_000_000L))
    }

    @Test
    fun `a line always moves something`() {
        assertEquals(NotPositive, convert(0L, 24_000L))
        assertEquals(NotPositive, convert(-3_000L, 24_000L))
        assertEquals(NotPositive, convert(Long.MIN_VALUE, 24_000L))
    }

    @Test
    fun `entered and converted quantities stay within the line limit`() {
        // La cantidad escrita llega hasta un millón de unidades.
        assertEquals(Converted(QuantityMilli(1_000_000_000L)), convert(1_000_000_000L, 1_000L))
        assertEquals(AboveLineLimit, convert(1_000_000_001L, 1_000L))
        // Aunque convertida quepa: un millón de gramos y uno más siguen siendo demasiados.
        assertEquals(AboveLineLimit, convert(1_000_000_001L, 1L))
        assertEquals(AboveLineLimit, convert(Long.MAX_VALUE, 1L))
        // Convertida pasa del tope: un millón de cajas de 24.
        assertEquals(AboveLineLimit, convert(1_000_000_000L, 24_000L))
        // El redondeo también cuenta: con factor 1500, 666.666.666 da 999.999.999, y 666.666.667 da
        // 1.000.000.000,5, que sube a 1.000.000.001 y pasa del tope.
        assertEquals(Converted(QuantityMilli(999_999_999L)), convert(666_666_666L, 1_500L))
        assertEquals(AboveLineLimit, convert(666_666_667L, 1_500L))
        // Los dos al máximo: 10^9 × 10^9 / 1000 = 10^15, que cabe en Long pero no en la línea.
        assertEquals(AboveLineLimit, convert(1_000_000_000L, 1_000_000_000L))
    }
}
