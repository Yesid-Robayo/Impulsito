package io.github.yesidrobayo.shopledger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Escribir el código de recuperación: cada casilla guarda hasta 4 caracteres en mayúsculas, un código
 * pegado se reparte entre las siguientes sin espacios ni guiones, y el foco avanza solo al completar
 * una casilla. Códigos inventados.
 */
class RecoveryCodeInputTest {
    private val empty = List(RECOVERY_CODE_GROUPS) { "" }

    @Test
    fun `a group keeps up to four uppercase characters`() {
        val groups = RecoveryCodeInput.spread(empty, 0, "7kq")

        assertEquals(listOf("7KQ", "", "", "", "", "", ""), groups)
        assertEquals(0, RecoveryCodeInput.focusAfter(groups, 0, "7kq"))
    }

    @Test
    fun `completing a group moves to the next one`() {
        val groups = RecoveryCodeInput.spread(empty, 2, "R4TB")

        assertEquals("R4TB", groups[2])
        assertEquals(3, RecoveryCodeInput.focusAfter(groups, 2, "R4TB"))
    }

    @Test
    fun `a pasted code is cleaned and spread over the next groups`() {
        val pasted = "7KQ2-M9XA R4TB-W8ZC-1PQ0-HJ5N-D3"
        val groups = RecoveryCodeInput.spread(empty, 0, pasted)

        assertEquals(listOf("7KQ2", "M9XA", "R4TB", "W8ZC", "1PQ0", "HJ5N", "D3"), groups)
        assertEquals(6, RecoveryCodeInput.focusAfter(groups, 0, pasted))
    }

    @Test
    fun `what does not fit after the last group is dropped and focus stays there`() {
        val pasted = "AAAABBBBCCCC"
        val groups = RecoveryCodeInput.spread(empty, 5, pasted)

        assertEquals(listOf("", "", "", "", "", "AAAA", "BBBB"), groups)
        assertEquals(6, RecoveryCodeInput.focusAfter(groups, 5, pasted))
    }

    @Test
    fun `symbols and letters outside the basic alphabet are ignored`() {
        val groups = RecoveryCodeInput.spread(empty, 0, "ñ7 é_K!")

        assertEquals("7K", groups[0])
    }

    @Test
    fun `erasing keeps the focus and the other groups`() {
        val full = listOf("7KQ2", "M9XA", "", "", "", "", "")
        val groups = RecoveryCodeInput.spread(full, 1, "M9")

        assertEquals(listOf("7KQ2", "M9", "", "", "", "", ""), groups)
        assertEquals(1, RecoveryCodeInput.focusAfter(groups, 1, "M9"))
    }
}
