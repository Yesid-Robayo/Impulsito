package io.github.yesidrobayo.shopledger.domain.rule.sale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Los enlaces de los extras: un extra apunta a una línea principal anterior; al quitar líneas, los
 * extras de una principal quitada se van con ella y los demás enlaces se mueven.
 */
class ExtraLinesTest {
    @Test
    fun `links to an earlier main line are valid`() {
        assertNull(ExtraLines.firstInvalid(listOf(null, 0, 0, null, 3)))
        assertNull(ExtraLines.firstInvalid(emptyList()))
    }

    @Test
    fun `a link to itself, to a later line, outside the sale or to an extra is invalid`() {
        assertEquals(1, ExtraLines.firstInvalid(listOf(null, 1)))
        assertEquals(0, ExtraLines.firstInvalid(listOf(1, null)))
        assertEquals(1, ExtraLines.firstInvalid(listOf(null, -1)))
        assertEquals(2, ExtraLines.firstInvalid(listOf(null, 0, 1)))
    }

    @Test
    fun `dropping a main line takes its extras and moves the other links`() {
        val lines = listOf("arepa" to null, "queso" to 0, "té" to null, "miel" to 2)

        val kept = ExtraLines.retain(lines, { it.second }) { it.first != "arepa" }

        assertEquals(listOf("té", "miel"), kept.map { it.line.first })
        assertEquals(listOf(null, 0), kept.map { it.parentIndex })
    }

    @Test
    fun `dropping an extra keeps its main line, and a broken link is dropped`() {
        val lines = listOf("arepa" to null, "queso" to 0, "salsa" to 0, "miel" to 7, "té" to 1)

        val kept = ExtraLines.retain(lines, { it.second }) { it.first != "queso" }

        assertEquals(listOf("arepa", "salsa"), kept.map { it.line.first })
        assertEquals(listOf(null, 0), kept.map { it.parentIndex })
    }
}
