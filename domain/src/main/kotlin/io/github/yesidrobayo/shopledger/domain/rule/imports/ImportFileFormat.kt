package io.github.yesidrobayo.shopledger.domain.rule.imports

import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvText

/** Lo que se deduce del formato de un archivo para importar, y cómo se devuelven sus filas. */
object ImportFileFormat {
    private val NUMBER = Regex("""[-+]?[0-9][0-9.,]*""")
    private const val GROUP_DIGITS = 3

    /**
     * El separador decimal que usa un CSV, deducido de sus números, o [fallback], el del formato
     * regional del negocio, si los números no lo dicen.
     *
     * Cada celda que parece un número vota: si trae punto y coma, el último es el decimal; si
     * trae uno solo de ellos, una sola vez y sin tres cifras detrás, ese es el decimal. `1.234` no
     * vota, porque puede ser mil doscientos o uno con decimales. Gana el que más votos tenga. Un
     * `.xlsx` siempre usa el punto.
     */
    fun decimalOf(sheet: ImportSheet, fallback: DecimalSeparator): DecimalSeparator {
        if (sheet.separator == null) return DecimalSeparator.POINT
        var point = 0
        var comma = 0
        for (row in sheet.rows) {
            for (cell in row.cells) {
                when (vote(cell.trim())) {
                    DecimalSeparator.POINT -> point++
                    DecimalSeparator.COMMA -> comma++
                    null -> Unit
                }
            }
        }
        return when {
            point > comma -> DecimalSeparator.POINT
            comma > point -> DecimalSeparator.COMMA
            else -> fallback
        }
    }

    private fun vote(cell: String): DecimalSeparator? {
        if (!NUMBER.matches(cell)) return null
        val lastPoint = cell.lastIndexOf('.')
        val lastComma = cell.lastIndexOf(',')
        return when {
            lastPoint >= 0 && lastComma >= 0 ->
                if (lastPoint > lastComma) DecimalSeparator.POINT else DecimalSeparator.COMMA

            lastPoint >= 0 -> single(cell, '.', DecimalSeparator.POINT)

            lastComma >= 0 -> single(cell, ',', DecimalSeparator.COMMA)

            else -> null
        }
    }

    private fun single(cell: String, char: Char, separator: DecimalSeparator): DecimalSeparator? {
        if (cell.count { it == char } != 1) return null
        return separator.takeIf { cell.length - cell.indexOf(char) - 1 != GROUP_DIGITS }
    }

    /**
     * Un CSV con el encabezado de [sheet] y solo las filas de [rows], tal como venían, para que la
     * persona las corrija en su hoja de cálculo y las vuelva a importar. Cada celda va como texto
     * neutralizado: lo que trajo el archivo nunca vuelve a la hoja como fórmula. Va con
     * [separator] y la marca de orden de bytes, como cualquier exportación.
     */
    fun errorRowsCsv(sheet: ImportSheet, rows: Set<Int>, separator: CsvSeparator): String {
        val glue = separator.char.toString()
        val lines = listOf(sheet.header) + sheet.rows.filter { it.number in rows }.map { it.cells }
        return CsvRowWriter.BYTE_ORDER_MARK + lines.joinToString("") { cells ->
            cells.joinToString(glue) { CsvText.quoted(ImportCellText.restored(it)) } +
                CsvRowWriter.LINE_END
        }
    }
}
