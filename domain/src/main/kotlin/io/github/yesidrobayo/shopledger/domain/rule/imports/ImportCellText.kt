package io.github.yesidrobayo.shopledger.domain.rule.imports

import io.github.yesidrobayo.shopledger.domain.rule.export.CsvText

/**
 * Cómo se limpia el texto de una celda importada antes de leerlo con las reglas de su campo.
 *
 * - **El apóstrofo de la exportación.** Al exportar, una celda que empieza como fórmula, o un
 *   código hecho solo de cifras, lleva un apóstrofo delante para que la hoja de cálculo la deje
 *   como texto. Al importar se quita, pero solo en esos dos casos: el nombre `'Clásico'` conserva
 *   su apóstrofo, porque sin él no sería ni fórmula ni cifras. Nada de la celda se ejecuta: el
 *   texto que queda se guarda como texto.
 * - **Los números de una hoja de cálculo.** Un `.xlsx` guarda los números con punto decimal y a
 *   veces con exponente, y el cálculo binario de la hoja deja arrastres como
 *   `0.30000000000000004`. Se redondean a las 15 cifras significativas que la hoja muestra y se
 *   escriben sin exponente, para que una celda que la persona ve como `0,3` no dé un error de
 *   decimales.
 * - **Los meses.** `AAAA-MM`, o una fecha `AAAA-MM-DD` de la que se toma el mes. En una hoja de
 *   cálculo, una fecha llega como número de serie de días, y se pasa a su mes.
 */
object ImportCellText {
    private const val NEUTRALIZER = '\''

    /** Las cifras significativas que una hoja de cálculo guarda de un número. */
    private const val SIGNIFICANT_DIGITS = 15

    /** Más allá, un exponente no es un dato de negocio: el número queda como vino y se rechaza. */
    private const val MAX_EXPONENT = 40

    private val SPREADSHEET_NUMBER = Regex("""(-?)([0-9]+)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?""")
    private val MONTH = Regex("""([0-9]{4})-([0-9]{1,2})(?:-[0-9]{1,2})?""")

    /** El primer y el último número de serie de una fecha de hoja de cálculo: 1900 a 9999. */
    private const val FIRST_SERIAL = 1L
    private const val LAST_SERIAL = 2_958_465L

    /** Días entre el día cero de las hojas de cálculo, 1899-12-30, y 1970-01-01. */
    private const val SERIAL_EPOCH_OFFSET = 25_569L

    private const val MONTHS_IN_YEAR = 12

    /** [cell] recortado y sin el apóstrofo que lo protegía en la hoja, si lo protegía. */
    fun text(cell: String): String = restored(cell).trim()

    /**
     * [cell] sin el apóstrofo de la exportación, si sin él sería una fórmula o solo cifras. Si no,
     * tal cual.
     */
    fun restored(cell: String): String {
        if (cell.firstOrNull() != NEUTRALIZER) return cell
        val rest = cell.substring(1)
        val wasProtected = CsvText.neutralized(rest) != rest || CsvText.keptAsText(rest) != rest
        return if (wasProtected) rest else cell
    }

    /**
     * El número de una celda de hoja de cálculo, sin exponente y con 15 cifras significativas como
     * máximo: `1.5E-3` da `0.0015` y `0.30000000000000004` da `0.3`. Si [cell] no es un número de
     * hoja de cálculo, sale recortado y sin cambios, y la regla de su campo lo rechaza.
     */
    fun spreadsheetNumber(cell: String): String {
        val text = cell.trim()
        val match = SPREADSHEET_NUMBER.matchEntire(text) ?: return text
        val (sign, whole, fraction, exponentText) = match.destructured
        val exponent = exponentText.ifEmpty { "0" }.removePrefix("+").toIntOrNull() ?: return text
        if (exponent > MAX_EXPONENT || exponent < -MAX_EXPONENT) return text
        var digits = whole + fraction
        var point = whole.length + exponent
        val leading = digits.indexOfFirst { it != '0' }
        if (leading < 0) return "0"
        digits = digits.substring(leading)
        point -= leading
        if (digits.length > SIGNIFICANT_DIGITS) {
            val kept = digits.substring(0, SIGNIFICANT_DIGITS)
            val rounded = roundedUp(kept, digits[SIGNIFICANT_DIGITS])
            if (rounded.length > SIGNIFICANT_DIGITS) point++
            digits = rounded
        }
        // La primera cifra nunca es cero, así que siempre queda al menos una.
        digits = digits.trimEnd('0')
        val plain = when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
        return sign + plain
    }

    /**
     * El mes `AAAA-MM` de [cell], o `null` si no es un mes. Con [fromSpreadsheet], un número
     * entero es el número de serie de una fecha y se pasa a su mes.
     */
    fun month(cell: String, fromSpreadsheet: Boolean): String? {
        val text = cell.trim()
        MONTH.matchEntire(text)?.let { match ->
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            return if (month in 1..MONTHS_IN_YEAR) format(year, month) else null
        }
        if (!fromSpreadsheet) return null
        val number = spreadsheetNumber(text)
        val serial = number.substringBefore('.').toLongOrNull() ?: return null
        if (serial !in FIRST_SERIAL..LAST_SERIAL) return null
        return monthOfEpochDay(serial - SERIAL_EPOCH_OFFSET)
    }

    /** [digits] más uno en su última cifra si [next] es 5 o más, con su acarreo. */
    private fun roundedUp(digits: String, next: Char): String {
        if (next < '5') return digits
        val chars = digits.toCharArray()
        var index = chars.size - 1
        while (index >= 0) {
            if (chars[index] == '9') {
                chars[index] = '0'
                index--
            } else {
                chars[index] = chars[index] + 1
                return String(chars)
            }
        }
        return "1" + String(chars)
    }

    /**
     * El mes del día [epochDay], contado desde 1970-01-01, con el calendario civil. Es el cálculo
     * de días a fecha de Howard Hinnant, en enteros, sin depender de ninguna biblioteca de fechas.
     */
    private fun monthOfEpochDay(epochDay: Long): String {
        val shifted = epochDay + DAYS_TO_ERA_START
        // Las fechas de una hoja de cálculo empiezan en 1900: el ciclo nunca es negativo.
        val era = shifted / DAYS_PER_ERA
        val dayOfEra = shifted - era * DAYS_PER_ERA
        val yearOfEra =
            (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / (DAYS_PER_ERA - 1)) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val shiftedMonth = (5 * dayOfYear + 2) / 153
        val month = if (shiftedMonth < 10) shiftedMonth + 3 else shiftedMonth - 9
        val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
        return format(year.toInt(), month.toInt())
    }

    private fun format(year: Int, month: Int): String =
        year.toString().padStart(4, '0') + "-" + month.toString().padStart(2, '0')

    /** Días de un ciclo de 400 años del calendario civil. */
    private const val DAYS_PER_ERA = 146_097L

    /** Días de 0000-03-01 a 1970-01-01, el comienzo del ciclo que usa el cálculo. */
    private const val DAYS_TO_ERA_START = 719_468L
}
