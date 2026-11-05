package io.github.yesidrobayo.shopledger.domain.matrix

import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La matriz misma: que tenga todas las filas de la estrategia de pruebas y que entre ellas cubran
 * lo que la matriz promete atacar. Si alguien borra una fila, o la cambia y deja de cubrir un caso,
 * falla aquí y no en silencio.
 */
class RegionalMatrixRowsTest {
    private val rows = RegionalMatrix.ROWS

    @Test
    fun `the matrix has its eighteen rows and each one has its own name`() {
        assertEquals(18, rows.size)
        assertEquals(rows.size, rows.map { it.toString() }.toSet().size)
    }

    @Test
    fun `only the unconfirmed currency takes its region from the phone`() {
        val fromDevice = rows.filter { it.region == null }
        assertEquals(listOf("XXX"), fromDevice.map { it.currencyCode.code })
        assertEquals(listOf(null), fromDevice.map { it.uiLanguageTag })
    }

    @Test
    fun `the rows cover every number of decimals, tax mode and interface language`() {
        assertEquals(setOf(0, 2, 3, 4), rows.map { it.currencyDecimals }.toSet())
        assertEquals(TaxMode.entries.toSet(), rows.map { it.taxMode }.toSet())
        assertEquals(setOf("en", "es", "pt", null), rows.map { it.uiLanguageTag }.toSet())
        assertEquals(setOf(0L, 5L, 50L, 1_000L), rows.map { it.cashRounding.minor }.toSet())
    }

    @Test
    fun `the rows cover both decimal separators, both group sizes and three week starts`() {
        val regions = rows.mapNotNull { it.region }
        assertEquals(2, regions.map { it.decimalSeparator }.toSet().size)
        assertEquals(setOf(2, 3), regions.map { it.grouping.secondarySize }.toSet())
        assertEquals(3, regions.map { it.weekStartDay }.toSet().size)
    }
}
