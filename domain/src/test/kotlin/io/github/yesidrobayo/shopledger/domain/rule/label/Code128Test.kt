package io.github.yesidrobayo.shopledger.domain.rule.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Code 128 escrito a mano: los valores de cada símbolo y el control salen de la tabla de la norma,
 * calculados aquí sin el código que se prueba.
 */
class Code128Test {
    @Test
    fun `an own code uses set B for the letter and set C for the digits`() {
        // Inicio B (104), P (48), cambio a C (99) y 00 00 00 42.
        assertEquals(listOf(104, 48, 99, 0, 0, 0, 42), Code128.symbolValues("P00000042"))
    }

    @Test
    fun `the check of P00000042 is 87`() {
        // 104 + 1×48 + 2×99 + 3×0 + 4×0 + 5×0 + 6×42 = 602, y 602 mod 103 = 87.
        val symbol = requireNotNull(Code128.encode("P00000042"))
        // Siete símbolos más el control, de 11 módulos, y el de parada, de 13.
        assertEquals(8 * 11 + 13, symbol.width)
        // El control, 87, es 421112: barra 4, espacio 2, barra 1, espacio 1, barra 1, espacio 2.
        val check = symbol.modules.subList(7 * 11, 8 * 11)
        assertEquals(bars("421112"), check)
    }

    @Test
    fun `start, first symbol and stop follow the table`() {
        val symbol = requireNotNull(Code128.encode("A"))
        // Inicio B 211214, A es 33 y 111323, control (104 + 33) mod 103 = 34, que es 131123.
        assertEquals(
            bars("211214") + bars("111323") + bars("131123") + bars("2331112"),
            symbol.modules,
        )
    }

    @Test
    fun `an even run of digits alone starts in set C`() {
        assertEquals(listOf(105, 12, 34), Code128.symbolValues("1234"))
    }

    @Test
    fun `an odd run leaves its first digit in set B`() {
        // 1 en B (17), luego C con 23 y 45.
        assertEquals(listOf(104, 17, 99, 23, 45), Code128.symbolValues("12345"))
    }

    @Test
    fun `a long run in the middle switches to C and back to B`() {
        // A (33), C, 12 34 56, vuelta a B (100) y B (34).
        assertEquals(
            listOf(104, 33, 99, 12, 34, 56, 100, 34),
            Code128.symbolValues("A123456B"),
        )
    }

    @Test
    fun `a single digit stays in set B`() {
        // 7 es 55 en ASCII, y 23 en el juego B.
        assertEquals(listOf(104, 23), Code128.symbolValues("7"))
    }

    @Test
    fun `four digits in the middle stay in set B`() {
        // A (33), 1 2 3 4 (17 a 20) y B (34): cuatro cifras solo cambian de juego al final.
        assertEquals(listOf(104, 33, 17, 18, 19, 20, 34), Code128.symbolValues("A1234B"))
    }

    @Test
    fun `short runs stay in set B`() {
        // A1B2: ninguna tira llega a 4 cifras.
        assertEquals(listOf(104, 33, 17, 34, 18), Code128.symbolValues("A1B2"))
        // Tres cifras al final tampoco.
        assertEquals(listOf(104, 48, 17, 18, 19), Code128.symbolValues("P123"))
        // Un guion corta la tira: P (48), - (13) y 1 (17).
        assertEquals(listOf(104, 48, 13, 17), Code128.symbolValues("P-1"))
    }

    @Test
    fun `empty text or characters outside visible ASCII are not encoded`() {
        assertNull(Code128.encode(""))
        assertNull(Code128.encode("Ñ1"))
        assertNull(Code128.encode("A\tB"))
    }

    private fun bars(widths: String): List<Boolean> =
        widths.flatMapIndexed { index, width -> List(width.digitToInt()) { index % 2 == 0 } }
}
