package io.github.yesidrobayo.shopledger.domain.rule.label

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.label.LabelTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Qué productos entran en cada plantilla de etiqueta, y si su código cabe legible. */
class LabelSelectionTest {
    private val priced = item(1L, price = 2_500L, code = "2001234567890", copies = 3)
    private val noPrice = item(2L, price = 0L, code = "P00000002", copies = 1)
    private val noCode = item(3L, price = 1_200L, code = null, copies = 2)
    private val blankCode = item(4L, price = 900L, code = " ", copies = 1)
    private val nothing = item(5L, price = 0L, code = null, copies = 1)
    private val items = listOf(priced, noPrice, noCode, blankCode, nothing)

    @Test
    fun `name and price leaves out only the products without a price`() {
        val plan = LabelSelection.plan(items, LabelTemplate.NAME_PRICE)

        assertEquals(listOf(priced, noCode, blankCode), plan.printable)
        assertEquals(listOf(noPrice, nothing), plan.withoutPrice)
        assertEquals(emptyList<LabelItem>(), plan.withoutCode)
        assertEquals(6, plan.labels)
    }

    @Test
    fun `name, price and code also leaves out those without a code`() {
        val plan = LabelSelection.plan(items, LabelTemplate.NAME_PRICE_CODE)

        assertEquals(listOf(priced), plan.printable)
        // Sin precio y sin código cuenta como sin precio: generar el código no lo haría
        // imprimible.
        assertEquals(listOf(noPrice, nothing), plan.withoutPrice)
        assertEquals(listOf(noCode, blankCode), plan.withoutCode)
        assertEquals(3, plan.labels)
    }

    @Test
    fun `code only prints products without a price`() {
        val plan = LabelSelection.plan(items, LabelTemplate.CODE_ONLY)

        assertEquals(listOf(priced, noPrice), plan.printable)
        assertEquals(emptyList<LabelItem>(), plan.withoutPrice)
        assertEquals(listOf(noCode, blankCode, nothing), plan.withoutCode)
        assertEquals(4, plan.labels)
    }

    @Test
    fun `an own code fits at 3 dots on 58 mm and a factory code only on 80 mm`() {
        val thermal = LabelBarcodeFit.MIN_THERMAL_MODULE
        // P00000123: inicio, P, cambio a C, cuatro pares, control y parada, 101 módulos.
        val own = requireNotNull(Code128.encode("P00000123")).width
        assertEquals(101, own)
        // 121 módulos con la zona de silencio: 3 puntos dan 363, dentro de 384.
        assertEquals(3, LabelBarcodeFit.moduleWidth(own, 384, thermal))
        assertEquals(4, LabelBarcodeFit.moduleWidth(own, 576, thermal))

        // Trece cifras: 123 módulos, 143 con la zona; a 3 puntos son 429, más que 384.
        val factory = requireNotNull(Code128.encode("2001234567890")).width
        assertEquals(123, factory)
        assertNull(LabelBarcodeFit.moduleWidth(factory, 384, thermal))
        assertEquals(4, LabelBarcodeFit.moduleWidth(factory, 576, thermal))
        // En la hoja, una etiqueta de 480 puntos da 3 por módulo, y 280 ya no alcanzan para 2.
        val sheet = LabelBarcodeFit.MIN_SHEET_MODULE
        assertEquals(3, LabelBarcodeFit.moduleWidth(factory, 480, sheet))
        assertNull(LabelBarcodeFit.moduleWidth(factory, 280, sheet))
    }

    @Test
    fun `a module never grows past four and a bad request is rejected`() {
        assertEquals(4, LabelBarcodeFit.moduleWidth(11, 10_000, 1))
        assertThrows(IllegalArgumentException::class.java) {
            LabelBarcodeFit.moduleWidth(0, 384, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LabelBarcodeFit.moduleWidth(11, 384, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LabelBarcodeFit.moduleWidth(11, 384, 5)
        }
    }

    private fun item(id: Long, price: Long, code: String?, copies: Int) =
        LabelItem(id, "Producto $id", Money(price), code, copies)
}
