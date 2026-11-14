package io.github.yesidrobayo.shopledger.data.imports

import android.util.Xml
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportLimits
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRow
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Lee la primera hoja de un `.xlsx` para importar, como entrada no confiable, con `java.util.zip`
 * y el analizador XML del sistema, sin ninguna biblioteca.
 *
 * - **Descompresión acotada.** Un `.xlsx` es un ZIP: cada entrada se cuenta al descomprimir, y se
 *   corta al pasar [ImportLimits.maxEntryBytes] por entrada o [ImportLimits.maxTotalBytes] en
 *   total, sin creer el tamaño que declara la entrada. Una entrada que es a su vez un ZIP rechaza
 *   el archivo: una hoja de datos no trae archivos adentro.
 * - **Nombres de entrada.** Solo se comparan con los que se esperan, como `xl/workbook.xml`. Nunca
 *   se usan como ruta: nada se escribe en el disco.
 * - **XML sin DTD.** Una declaración de tipo de documento, o una referencia a una entidad que no
 *   es de las cinco del XML, rechaza el archivo antes de expandir nada: así no hay entidades
 *   externas ni expansión de entidades.
 * - **Fórmulas.** Nunca se evalúan: una celda con fórmula vale su último resultado guardado, o
 *   queda vacía si no lo trae.
 * - **Topes.** Filas, columnas y caracteres por celda, como en un CSV, más un tope de textos
 *   compartidos, que una hoja armada podría inflar sin filas.
 *
 * Cada celda sale como texto: un número sale como lo guarda la hoja, con punto decimal, y una
 * fecha como su número de serie. Interpretarlas es de quien importa.
 */
internal class XlsxSheetReader(private val limits: ImportLimits = ImportLimits()) {
    /** Lee [file], el `.xlsx` entero ya leído con su tope de tamaño. */
    fun read(file: ByteArray, onRows: (Int) -> Unit, checkActive: () -> Unit): ImportFileRead =
        try {
            val parts = readParts(file, checkActive)
            val sheetPath = firstSheetPath(parts)
            val sheet =
                entryStream(file, sheetPath) { input ->
                    readSheet(input, parts.sharedStrings, onRows, checkActive)
                }
                    ?: throw Rejected(ImportRejection.UNSUPPORTED_TYPE)
            sheet
        } catch (rejected: Rejected) {
            ImportFileRead.Rejected(rejected.reason)
        } catch (broken: ZipException) {
            ImportFileRead.Rejected(ImportRejection.UNSUPPORTED_TYPE)
        } catch (broken: XmlPullParserException) {
            ImportFileRead.Rejected(ImportRejection.UNSUPPORTED_TYPE)
        } catch (broken: IOException) {
            ImportFileRead.Rejected(ImportRejection.UNSUPPORTED_TYPE)
        } catch (broken: IllegalArgumentException) {
            ImportFileRead.Rejected(ImportRejection.UNSUPPORTED_TYPE)
        }

    /**
     * Primera pasada: recorre todas las entradas contando lo que descomprimen, rechaza un ZIP
     * dentro de otro y lee el libro, sus relaciones y los textos compartidos.
     */
    private fun readParts(file: ByteArray, checkActive: () -> Unit): Parts {
        var total = 0L
        var workbook: Workbook? = null
        var relations: Map<String, String> = emptyMap()
        var sharedStrings: List<String> = emptyList()
        var entries = 0
        ZipInputStream(ByteArrayInputStream(file)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                checkActive()
                if (++entries > MAX_ENTRIES) throw Rejected(ImportRejection.UNSUPPORTED_TYPE)
                if (entry.isDirectory) continue
                val counted = CountedEntry(zip, limits.maxEntryBytes, limits.maxTotalBytes - total)
                val head = counted.peekHead()
                if (startsWithZip(head)) throw Rejected(ImportRejection.UNSUPPORTED_TYPE)
                when (entry.name) {
                    WORKBOOK -> workbook = parseWorkbook(counted)
                    WORKBOOK_RELATIONS -> relations = parseRelations(counted)
                    SHARED_STRINGS -> sharedStrings = parseSharedStrings(counted)
                    else -> Unit
                }
                counted.drain()
                total += counted.count
            }
        }
        if (entries == 0) throw Rejected(ImportRejection.UNSUPPORTED_TYPE)
        return Parts(
            workbook ?: throw Rejected(ImportRejection.UNSUPPORTED_TYPE),
            relations,
            sharedStrings,
        )
    }

    /** La ruta dentro del ZIP de la primera hoja del libro. */
    private fun firstSheetPath(parts: Parts): String {
        val target = parts.relations[parts.workbook.firstSheetRelation] ?: DEFAULT_SHEET
        val path = target.removePrefix("/")
        return if (path.startsWith("xl/")) path else "xl/$path"
    }

    /** Segunda pasada: abre solo la entrada [path] y la entrega a [read]. */
    private fun <T> entryStream(file: ByteArray, path: String, read: (InputStream) -> T): T? {
        ZipInputStream(ByteArrayInputStream(file)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == path) {
                    return read(CountedEntry(zip, limits.maxEntryBytes, limits.maxTotalBytes))
                }
            }
        }
    }

    private fun parseWorkbook(input: InputStream): Workbook {
        val parser = parserOf(input)
        while (true) {
            when (nextToken(parser)) {
                XmlPullParser.END_DOCUMENT -> return Workbook(firstSheetRelation = null)

                XmlPullParser.START_TAG ->
                    if (localName(parser) == "sheet") {
                        return Workbook(attribute(parser, "id"))
                    }

                else -> Unit
            }
        }
    }

    private fun parseRelations(input: InputStream): Map<String, String> {
        val parser = parserOf(input)
        val relations = HashMap<String, String>()
        while (nextToken(parser) != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG &&
                localName(parser) == "Relationship"
            ) {
                val id = attribute(parser, "Id")
                val target = attribute(parser, "Target")
                if (id != null && target != null && relations.size < MAX_RELATIONS) {
                    relations[id] = target
                }
            }
        }
        return relations
    }

    /** Los textos compartidos, cada uno con el tope de caracteres de una celda. */
    private fun parseSharedStrings(input: InputStream): List<String> {
        val parser = parserOf(input)
        val strings = ArrayList<String>()
        var chars = 0L
        while (nextToken(parser) != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && localName(parser) == "si") {
                val text = readRichText(parser, "si")
                chars += text.length
                if (strings.size == MAX_SHARED_STRINGS || chars > MAX_SHARED_CHARS) {
                    throw Rejected(ImportRejection.TOO_LARGE)
                }
                strings += text
            }
        }
        return strings
    }

    /**
     * El texto de un `<si>` o un `<is>`: la suma de sus `<t>`, también los de cada tramo con
     * formato, sin las guías fonéticas, que no son parte del texto.
     */
    private fun readRichText(parser: XmlPullParser, end: String): String {
        val text = StringBuilder()
        var inText = false
        var phonetic = 0
        while (true) {
            when (nextToken(parser)) {
                XmlPullParser.START_TAG ->
                    when (localName(parser)) {
                        "t" -> inText = phonetic == 0
                        "rPh" -> phonetic++
                    }

                XmlPullParser.END_TAG ->
                    when (localName(parser)) {
                        "t" -> inText = false
                        "rPh" -> phonetic--
                        end -> return text.toString()
                    }

                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.IGNORABLE_WHITESPACE,
                XmlPullParser.ENTITY_REF,
                ->
                    if (inText) append(text, parser.text.orEmpty())

                XmlPullParser.END_DOCUMENT -> throw Rejected(ImportRejection.MALFORMED)

                else -> Unit
            }
        }
    }

    private fun readSheet(
        input: InputStream,
        shared: List<String>,
        onRows: (Int) -> Unit,
        checkActive: () -> Unit,
    ): ImportFileRead {
        val parser = parserOf(input)
        var header: List<String>? = null
        val rows = ArrayList<ImportRow>()
        var rowNumber = 0
        while (nextToken(parser) != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || localName(parser) != "row") continue
            rowNumber = attribute(parser, "r")?.let(::rowOf) ?: (rowNumber + 1)
            val cells = readRow(parser, shared)
            if (cells.none { it.isNotBlank() }) continue
            if (header == null) {
                header = cells
            } else {
                if (rows.size == limits.maxRows) throw Rejected(ImportRejection.TOO_MANY_ROWS)
                rows += ImportRow(rowNumber, cells)
                if (rows.size % PROGRESS_STEP == 0) {
                    checkActive()
                    onRows(rows.size)
                }
            }
        }
        if (header == null || rows.isEmpty()) return ImportFileRead.Rejected(ImportRejection.EMPTY)
        onRows(rows.size)
        return ImportFileRead.Read(ImportSheet(header, rows, separator = null), fileName = null)
    }

    /** Las celdas de un `<row>`, cada una en su columna; las que faltan quedan vacías. */
    private fun readRow(parser: XmlPullParser, shared: List<String>): List<String> {
        val cells = ArrayList<String>()
        var next = 0
        while (true) {
            when (nextToken(parser)) {
                XmlPullParser.START_TAG ->
                    if (localName(parser) == "c") {
                        val column = attribute(parser, "r")?.let(::columnOf) ?: next
                        if (column >=
                            limits.maxColumns
                        ) {
                            throw Rejected(ImportRejection.TOO_MANY_COLUMNS)
                        }
                        if (column < next) throw Rejected(ImportRejection.MALFORMED)
                        val value = readCell(parser, attribute(parser, "t"), shared)
                        while (cells.size < column) cells += ""
                        cells += value
                        next = column + 1
                    }

                XmlPullParser.END_TAG -> if (localName(parser) == "row") return cells

                XmlPullParser.END_DOCUMENT -> throw Rejected(ImportRejection.MALFORMED)

                else -> Unit
            }
        }
    }

    /**
     * El texto de una celda según su tipo. Una fórmula `<f>` se ignora: vale el resultado guardado
     * en `<v>`. Un texto compartido sale de [shared] por su número.
     */
    private fun readCell(parser: XmlPullParser, type: String?, shared: List<String>): String {
        var value: String? = null
        var inline: String? = null
        while (true) {
            when (nextToken(parser)) {
                XmlPullParser.START_TAG ->
                    when (localName(parser)) {
                        "v" -> value = readElementText(parser, "v")
                        "is" -> inline = readRichText(parser, "is")
                        "f" -> readElementText(parser, "f")
                    }

                XmlPullParser.END_TAG ->
                    if (localName(parser) == "c") {
                        return when (type) {
                            "s" ->
                                value?.trim()?.toInt()?.let { index ->
                                    shared.getOrNull(index)
                                        ?: throw Rejected(ImportRejection.MALFORMED)
                                } ?: ""

                            "inlineStr" -> inline.orEmpty()

                            "b" -> if (value?.trim() == "1") BOOLEAN_TRUE else BOOLEAN_FALSE

                            else -> value.orEmpty()
                        }
                    }

                XmlPullParser.END_DOCUMENT -> throw Rejected(ImportRejection.MALFORMED)

                else -> Unit
            }
        }
    }

    /** El texto de un elemento sin hijos, con el tope de una celda. */
    private fun readElementText(parser: XmlPullParser, end: String): String {
        val text = StringBuilder()
        while (true) {
            when (nextToken(parser)) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.IGNORABLE_WHITESPACE,
                XmlPullParser.ENTITY_REF,
                -> append(text, parser.text.orEmpty())

                XmlPullParser.END_TAG -> if (localName(parser) == end) return text.toString()

                XmlPullParser.END_DOCUMENT -> throw Rejected(ImportRejection.MALFORMED)

                else -> Unit
            }
        }
    }

    private fun append(text: StringBuilder, piece: String) {
        if (text.length + piece.length > limits.maxCellChars) {
            throw Rejected(ImportRejection.CELL_TOO_LARGE)
        }
        text.append(piece)
    }

    /**
     * El siguiente evento, con la declaración de tipo de documento y las entidades desconocidas
     * rechazadas antes de que el analizador las use.
     */
    private fun nextToken(parser: XmlPullParser): Int {
        val event = parser.nextToken()
        if (event == XmlPullParser.DOCDECL) throw Rejected(ImportRejection.UNSUPPORTED_TYPE)
        if (event == XmlPullParser.ENTITY_REF && !isKnownReference(parser.name)) {
            throw Rejected(ImportRejection.UNSUPPORTED_TYPE)
        }
        return event
    }

    /** Una de las cinco entidades del XML, o una referencia a un carácter por su número. */
    private fun isKnownReference(name: String?): Boolean =
        name != null && (name in XML_ENTITIES || name.startsWith(CHARACTER_REFERENCE))

    private fun parserOf(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(input, null)
    }

    private fun localName(parser: XmlPullParser): String = parser.name.substringAfter(':')

    /** El atributo [name], con o sin prefijo de espacio de nombres, como `r:id`. */
    private fun attribute(parser: XmlPullParser, name: String): String? {
        for (index in 0 until parser.attributeCount) {
            if (parser.getAttributeName(index).substringAfter(':') == name) {
                return parser.getAttributeValue(index)
            }
        }
        return null
    }

    private fun startsWithZip(head: ByteArray): Boolean =
        head.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { head[it] == ZIP_MAGIC[it] }

    /** Lo que se lee de un libro en la primera pasada. */
    private class Parts(
        val workbook: Workbook,
        val relations: Map<String, String>,
        val sharedStrings: List<String>,
    )

    /** La relación de la primera hoja del libro, o `null` si el libro no lista hojas. */
    private class Workbook(val firstSheetRelation: String?)

    /**
     * La entrada actual de [zip], contando lo que descomprime. Pasar [maxEntry] o [maxRemaining]
     * rechaza el archivo. No cierra [zip]: la entrada siguiente sigue en el mismo flujo.
     */
    private class CountedEntry(
        zip: ZipInputStream,
        private val maxEntry: Long,
        private val maxRemaining: Long,
    ) : FilterInputStream(zip) {
        var count = 0L
            private set
        private var pending: ByteArray? = null
        private var pendingIndex = 0

        /** Los primeros bytes, que se vuelven a entregar al leer. */
        fun peekHead(): ByteArray {
            val head = ByteArray(ZIP_MAGIC.size)
            var size = 0
            while (size < head.size) {
                val read = readCounted(head, size, head.size - size)
                if (read < 0) break
                size += read
            }
            return head.copyOf(size).also { pending = it }
        }

        /** Descomprime lo que falta de la entrada, solo para contarlo. */
        fun drain() {
            val buffer = ByteArray(DRAIN_BYTES)
            while (read(buffer, 0, buffer.size) >= 0) Unit
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val head = pending
            if (head != null && pendingIndex < head.size) {
                val size = minOf(length, head.size - pendingIndex)
                head.copyInto(buffer, offset, pendingIndex, pendingIndex + size)
                pendingIndex += size
                return size
            }
            return readCounted(buffer, offset, length)
        }

        private fun readCounted(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                count += read
                if (count > maxEntry ||
                    count > maxRemaining
                ) {
                    throw Rejected(ImportRejection.TOO_LARGE)
                }
            }
            return read
        }

        override fun close() = Unit

        override fun skip(n: Long): Long = throw IOException("Saltar bytes no está permitido")
    }

    /** Un rechazo que corta la lectura donde ocurre. */
    private class Rejected(val reason: ImportRejection) : RuntimeException()

    companion object {
        /** Cada cuántas filas se avisa el progreso. */
        const val PROGRESS_STEP: Int = 1_000

        /**
         * Textos compartidos de un libro: más que celdas de texto razonables en el tope de filas.
         */
        const val MAX_SHARED_STRINGS: Int = 1_000_000

        /** Caracteres de todos los textos compartidos juntos. */
        const val MAX_SHARED_CHARS: Long = 16L * 1024 * 1024

        /** Entradas del ZIP: un `.xlsx` de datos trae una docena. */
        const val MAX_ENTRIES: Int = 1_000

        private const val MAX_RELATIONS = 1_000
        private const val WORKBOOK = "xl/workbook.xml"
        private const val WORKBOOK_RELATIONS = "xl/_rels/workbook.xml.rels"
        private const val SHARED_STRINGS = "xl/sharedStrings.xml"
        private const val DEFAULT_SHEET = "worksheets/sheet1.xml"
        private const val CHARACTER_REFERENCE = "#"
        private const val BOOLEAN_TRUE = "TRUE"
        private const val BOOLEAN_FALSE = "FALSE"
        private const val DRAIN_BYTES = 8 * 1024
        private const val BYTE_MASK = 0xFF
        private const val LETTERS = 26
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** Las cinco entidades que define el propio XML. */
        private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

        /** El índice, desde 0, de la columna de una referencia como `C5`. */
        internal fun columnOf(reference: String): Int {
            var column = 0
            for (char in reference) {
                if (char !in 'A'..'Z') break
                column = column * LETTERS + (char - 'A' + 1)
                if (column > MAX_COLUMN_LETTERS_VALUE) return MAX_COLUMN_LETTERS_VALUE
            }
            require(column > 0) { "Referencia de celda sin columna" }
            return column - 1
        }

        /** El número de fila de un atributo `r` de `<row>`. */
        internal fun rowOf(reference: String): Int = reference.trim().toInt().also {
            require(it > 0) { "Fila sin número" }
        }

        /**
         * Más que la última columna de cualquier hoja: corta una referencia larga sin desbordar.
         */
        private const val MAX_COLUMN_LETTERS_VALUE = 1_000_000
    }
}
