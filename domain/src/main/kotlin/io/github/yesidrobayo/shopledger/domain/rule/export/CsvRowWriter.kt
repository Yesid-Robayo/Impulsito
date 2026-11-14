package io.github.yesidrobayo.shopledger.domain.rule.export

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Escribe las líneas de un CSV exportado: el encabezado y cada fila, con el separador de columnas del
 * negocio y el fin de línea de Windows, que es el que espera una hoja de cálculo.
 *
 * - **Texto:** siempre entre comillas dobles, con las internas duplicadas, y con la neutralización de
 *   fórmulas de [CsvText]. Así un nombre con el separador, comillas o saltos de línea no parte la
 *   fila, y uno que empieza con `=` no se ejecuta como fórmula.
 * - **Números:** montos, cantidades y porcentajes salen de [CsvFormat], desde el entero guardado, y
 *   nunca se neutralizan: un `-5000` en una columna de monto sigue siendo un número.
 * - **Fechas y horas:** `AAAA-MM-DD` y `HH:mm:ss` en la zona del negocio, iguales en cualquier idioma.
 *   Una fecha guardada que no tiene esa forma, algo que solo trae una base dañada, sale como texto.
 */
class CsvRowWriter(private val format: CsvFormat, private val zone: BusinessTimeZone) {
    private val separator = format.separator.char.toString()

    /** La línea de encabezado con [names], los nombres de columna ya en el idioma del archivo. */
    fun header(names: List<String>): String = line(names.map(CsvText::quoted))

    /** La línea de una fila con [cells], en el orden de las columnas. */
    fun row(cells: List<CsvCell>): String = line(cells.map(::cell))

    /** El texto de una celda, sin separador ni fin de línea. */
    fun cell(cell: CsvCell): String = when (cell) {
        is CsvCell.Text -> CsvText.quoted(cell.value)

        is CsvCell.Code -> CsvText.quoted(CsvText.keptAsText(cell.value))

        is CsvCell.Amount -> format.moneyCell(cell.value)

        is CsvCell.Quantity -> format.quantityCell(cell.value)

        is CsvCell.Count -> cell.value.toString()

        is CsvCell.Percent -> format.percentCell(cell.value)

        is CsvCell.Scaled -> format.scaledCell(cell.value, cell.decimals)

        is CsvCell.Day -> if (STORED_DAY.matches(
                cell.value,
            )
        ) {
            cell.value
        } else {
            CsvText.quoted(cell.value)
        }

        is CsvCell.Moment -> local(cell.epochMillis).let { "${day(it)} ${time(it)}" }

        is CsvCell.TimeOfDay -> time(local(cell.epochMillis))

        CsvCell.Empty -> ""
    }

    private fun line(cells: List<String>): String = cells.joinToString(separator) + LINE_END

    private fun local(epochMillis: Long): LocalDateTime =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone.timeZone)

    private fun day(at: LocalDateTime): String =
        "${at.year.toString().padStart(YEAR_DIGITS, ZERO)}-${two(at.month.number)}-${two(at.day)}"

    private fun time(at: LocalDateTime): String =
        "${two(at.hour)}:${two(at.minute)}:${two(at.second)}"

    private fun two(value: Int): String = value.toString().padStart(2, ZERO)

    companion object {
        /**
         * La marca de orden de bytes de UTF-8 con que empieza cada archivo: sin ella, Excel abre el
         * archivo con la página de códigos del equipo y rompe las tildes, la `ñ`, la `ç` y la `ã`.
         */
        val BYTE_ORDER_MARK: String = Char(BYTE_ORDER_MARK_CODE).toString()

        /** El fin de línea de cada fila: retorno y salto, como Windows. */
        const val LINE_END: String = "\r\n"

        /** El punto de código de la marca, escrito como número: un editor lo borraría sin avisar. */
        private const val BYTE_ORDER_MARK_CODE = 0xFEFF

        private val STORED_DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private const val YEAR_DIGITS = 4
        private const val ZERO = '0'
    }
}

/**
 * Las celdas de texto de un CSV sin fórmulas.
 *
 * Una hoja de cálculo ejecuta como fórmula la celda que empieza con `=`, `+`, `-` o `@`, y algunas
 * también con un tabulador, un retorno o un salto de línea. Con esos caracteres al principio, tras
 * los espacios, la celda lleva un apóstrofo delante, y la hoja la muestra como texto. Las variantes
 * de ancho completo `＝ ＋ － ＠` cuentan igual, porque algunas hojas las convierten.
 */
object CsvText {
    private const val QUOTE = "\""
    private const val ESCAPED_QUOTE = "\"\""
    private const val NEUTRALIZER = "'"
    private const val SPACE = ' '
    private val DIGITS = '0'..'9'

    private val FORMULA_STARTS =
        setOf('=', '+', '-', '@', '\t', '\r', '\n', '＝', '＋', '－', '＠')

    /** [value] con el apóstrofo delante si empieza como una fórmula, o tal cual. */
    fun neutralized(value: String): String {
        val first = value.firstOrNull { it != SPACE } ?: return value
        return if (first in FORMULA_STARTS) NEUTRALIZER + value else value
    }

    /**
     * [value] con el apóstrofo delante si son solo cifras, para que la hoja de cálculo lo deje como
     * texto: es la misma marca que neutraliza las fórmulas, y la importación la quita igual.
     */
    fun keptAsText(value: String): String =
        if (value.isNotEmpty() && value.all { it in DIGITS }) NEUTRALIZER + value else value

    /** [value] neutralizado, entre comillas dobles y con sus comillas internas duplicadas. */
    fun quoted(value: String): String =
        QUOTE + neutralized(value).replace(QUOTE, ESCAPED_QUOTE) + QUOTE
}
