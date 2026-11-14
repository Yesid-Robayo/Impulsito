package io.github.yesidrobayo.shopledger.domain.rule.imports

import io.github.yesidrobayo.shopledger.domain.model.imports.ImportCharSource
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportLimits
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRow
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator

/**
 * Lee un CSV para importar como entrada no confiable: en flujo, con los topes de [limits]
 * comprobados mientras lee, y sin interpretar nada. Cada celda sale como el texto que trae el
 * archivo.
 *
 * - **Formato.** Comillas dobles con las internas duplicadas, celdas con saltos de línea adentro y
 *   fin de línea CRLF, LF o CR: lo que escriben las hojas de cálculo y la exportación de la app.
 *   La marca de orden de bytes del principio se descarta.
 * - **Separador.** El que elige la persona, o el que más aparece fuera de comillas en la primera
 *   fila con texto. En un empate, o sin ninguno, manda el de los ajustes: depende de la región.
 * - **Filas en blanco.** Se saltan, pero cuentan para el número de fila, como en la hoja de
 *   cálculo.
 * - **Rechazo.** Superar un tope, una comilla que no cierra, texto pegado a una comilla final o un
 *   carácter nulo, propio de un binario, rechazan el archivo entero: no se devuelve una parte. Un
 *   tope se comprueba al llegar a él, sin guardar antes lo que sobra.
 */
class CsvSheetReader(private val limits: ImportLimits = ImportLimits()) {
    /**
     * Lee [source] con [separator], o con el que detecte si es `null`, y [preferred] para los
     * empates. [onRows] recibe cuántas filas de datos van leídas, de a [PROGRESS_STEP] y al final.
     * Lo leído no lleva nombre de archivo: lo agrega quien abrió el documento.
     */
    fun read(
        source: ImportCharSource,
        separator: CsvSeparator?,
        preferred: CsvSeparator,
        onRows: (Int) -> Unit = {},
    ): ImportFileRead = try {
        val first = source.read()
        val start = if (first == BYTE_ORDER_MARK) source else Replay(charsOf(first), source)
        if (separator != null) {
            parse(start, separator, onRows)
        } else {
            val line = firstLine(start)
            parse(Replay(line, start), chooseSeparator(line, preferred), onRows)
        }
    } catch (rejected: Rejected) {
        ImportFileRead.Rejected(rejected.reason)
    }

    /**
     * El texto del principio hasta el fin de la primera línea con algo escrito, sin partir una
     * celda entre comillas. Se corta en un largo acotado: con eso alcanza para elegir el
     * separador, y el análisis completo comprueba los topes.
     */
    private fun firstLine(source: ImportCharSource): String {
        val line = StringBuilder()
        val bound = limits.maxColumns.toLong() * limits.maxCellChars
        var quoted = false
        var written = false
        while (line.length < bound) {
            val code = source.read()
            if (code == ImportCharSource.END) break
            val char = code.toChar()
            line.append(char)
            if (char == QUOTE) {
                quoted = !quoted
            } else if (!quoted && (char == '\n' || char == '\r')) {
                if (written) break
            } else if (!char.isWhitespace()) {
                written = true
            }
        }
        return line.toString()
    }

    private fun parse(
        input: ImportCharSource,
        separator: CsvSeparator,
        onRows: (Int) -> Unit,
    ): ImportFileRead {
        val sep = separator.char.code
        var header: List<String>? = null
        val rows = ArrayList<ImportRow>()
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var number = 0
        var code = input.read()
        while (code != ImportCharSource.END) {
            number++
            cells.clear()
            do {
                cell.setLength(0)
                if (code == QUOTE.code) {
                    code = readQuoted(input, cell)
                    if (!endsCell(code, sep)) throw Rejected(ImportRejection.MALFORMED)
                } else {
                    while (!endsCell(code, sep)) {
                        append(cell, code)
                        code = input.read()
                    }
                }
                cells += cell.toString()
                if (cells.size > limits.maxColumns) {
                    throw Rejected(ImportRejection.TOO_MANY_COLUMNS)
                }
                val more = code == sep
                code =
                    when (code) {
                        ImportCharSource.END -> ImportCharSource.END
                        CR -> input.read().let { next -> if (next == LF) input.read() else next }
                        else -> input.read()
                    }
            } while (more)
            if (cells.any { it.isNotBlank() }) {
                if (header == null) {
                    header = cells.toList()
                } else {
                    if (rows.size == limits.maxRows) throw Rejected(ImportRejection.TOO_MANY_ROWS)
                    rows += ImportRow(number, cells.toList())
                    if (rows.size % PROGRESS_STEP == 0) onRows(rows.size)
                }
            }
        }
        if (header == null || rows.isEmpty()) return ImportFileRead.Rejected(ImportRejection.EMPTY)
        onRows(rows.size)
        return ImportFileRead.Read(ImportSheet(header, rows, separator), fileName = null)
    }

    /**
     * Lee una celda entre comillas, ya pasada la que abre, y devuelve el carácter que sigue a la
     * que cierra. Dos comillas seguidas son una comilla del texto.
     */
    private fun readQuoted(input: ImportCharSource, cell: StringBuilder): Int {
        while (true) {
            when (val code = input.read()) {
                ImportCharSource.END -> throw Rejected(ImportRejection.MALFORMED)

                QUOTE.code -> {
                    val next = input.read()
                    if (next != QUOTE.code) return next
                    append(cell, next)
                }

                else -> append(cell, code)
            }
        }
    }

    private fun endsCell(code: Int, separator: Int): Boolean =
        code == separator || code == CR || code == LF || code == ImportCharSource.END

    private fun append(cell: StringBuilder, code: Int) {
        if (code == NUL) throw Rejected(ImportRejection.UNSUPPORTED_TYPE)
        if (cell.length == limits.maxCellChars) throw Rejected(ImportRejection.CELL_TOO_LARGE)
        cell.append(code.toChar())
    }

    /** Lo ya leído de nuevo, delante del resto del archivo. */
    private class Replay(private val prefix: String, private val rest: ImportCharSource) :
        ImportCharSource {
        private var index = 0

        override fun read(): Int = if (index < prefix.length) prefix[index++].code else rest.read()
    }

    /** Un rechazo que corta la lectura donde ocurre: se convierte en resultado en [read]. */
    private class Rejected(val reason: ImportRejection) : RuntimeException()

    companion object {
        /** Cada cuántas filas se avisa el progreso de la lectura. */
        const val PROGRESS_STEP: Int = 1_000

        private const val QUOTE = '"'
        private const val CR = '\r'.code
        private const val LF = '\n'.code
        private const val NUL = 0
        private const val BYTE_ORDER_MARK = 0xFEFF

        private fun charsOf(code: Int): String =
            if (code == ImportCharSource.END) "" else code.toChar().toString()

        /**
         * El separador que más aparece fuera de comillas en [line]. En un empate, o si no aparece
         * ninguno, gana [preferred] si está entre los que más aparecen; si no, el primero de la
         * lista de separadores.
         */
        internal fun chooseSeparator(line: String, preferred: CsvSeparator): CsvSeparator {
            val counts = IntArray(CsvSeparator.entries.size)
            var quoted = false
            for (char in line) {
                if (char == QUOTE) {
                    quoted = !quoted
                } else if (!quoted) {
                    CsvSeparator.entries.firstOrNull {
                        it.char == char
                    }?.let { counts[it.ordinal]++ }
                }
            }
            val most = counts.max()
            return if (counts[preferred.ordinal] == most) {
                preferred
            } else {
                CsvSeparator.entries.first { counts[it.ordinal] == most }
            }
        }
    }
}
