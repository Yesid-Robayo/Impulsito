package io.github.yesidrobayo.shopledger.domain.rule.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Limpiar una celda importada: el apóstrofo de la exportación, los números de hoja y los meses. */
class ImportCellTextTest {
    @Test
    fun `the apostrophe that protected a formula or a number is removed`() {
        assertEquals("=1+1", ImportCellText.text("'=1+1"))
        assertEquals("@SUM(A1:A9)", ImportCellText.text("'@SUM(A1:A9)"))
        assertEquals("-2+3", ImportCellText.text("'-2+3"))
        assertEquals("＝1+1", ImportCellText.text("'＝1+1"))
        assertEquals("0012345", ImportCellText.text("'0012345"))
    }

    @Test
    fun `an apostrophe that protected nothing is part of the text`() {
        assertEquals("'Clasico'", ImportCellText.text("'Clasico'"))
        assertEquals("'", ImportCellText.text("'"))
        assertEquals("Arroz", ImportCellText.text("  Arroz  "))
    }

    @Test
    fun `spreadsheet numbers lose the binary drift and the exponent`() {
        assertEquals("0.3", ImportCellText.spreadsheetNumber("0.30000000000000004"))
        assertEquals("0.0015", ImportCellText.spreadsheetNumber("1.5E-3"))
        assertEquals("12000000", ImportCellText.spreadsheetNumber("1.2E+7"))
        assertEquals("-4.25", ImportCellText.spreadsheetNumber("-4.2500"))
        assertEquals("12", ImportCellText.spreadsheetNumber("12"))
        assertEquals("0", ImportCellText.spreadsheetNumber("0.000"))
        assertEquals("1000", ImportCellText.spreadsheetNumber("999.9999999999999"))
        assertEquals("0.1", ImportCellText.spreadsheetNumber("0.09999999999999999"))
        // La cifra 16 es un 4: se descarta sin subir la anterior.
        assertEquals("0.123456789012345", ImportCellText.spreadsheetNumber("0.1234567890123454"))
    }

    @Test
    fun `text that is not a spreadsheet number stays as it came`() {
        assertEquals("12,50", ImportCellText.spreadsheetNumber("12,50"))
        assertEquals("$ 4", ImportCellText.spreadsheetNumber(" $ 4 "))
        assertEquals("1E999", ImportCellText.spreadsheetNumber("1E999"))
        assertEquals("1E-999", ImportCellText.spreadsheetNumber("1E-999"))
        assertEquals("1E99999999999", ImportCellText.spreadsheetNumber("1E99999999999"))
    }

    @Test
    fun `months are read from text and from spreadsheet dates`() {
        assertEquals("2026-03", ImportCellText.month("2026-03", fromSpreadsheet = false))
        assertEquals("2026-03", ImportCellText.month("2026-3", fromSpreadsheet = false))
        assertEquals("2026-03", ImportCellText.month("2026-03-01", fromSpreadsheet = false))
        // 46082 es el 2026-03-01 en una hoja de cálculo; 45658, el 2025-01-01.
        assertEquals("2026-03", ImportCellText.month("46082", fromSpreadsheet = true))
        assertEquals("2025-01", ImportCellText.month("45658", fromSpreadsheet = true))
        assertEquals("2024-02", ImportCellText.month("45351.5", fromSpreadsheet = true))
    }

    @Test
    fun `what is not a month gives nothing`() {
        assertNull(ImportCellText.month("2026-13", fromSpreadsheet = false))
        assertNull(ImportCellText.month("2026-00", fromSpreadsheet = false))
        assertNull(ImportCellText.month("marzo", fromSpreadsheet = true))
        assertNull(ImportCellText.month("46082", fromSpreadsheet = false))
        assertNull(ImportCellText.month("0", fromSpreadsheet = true))
        assertNull(ImportCellText.month("2958466", fromSpreadsheet = true))
    }
}
