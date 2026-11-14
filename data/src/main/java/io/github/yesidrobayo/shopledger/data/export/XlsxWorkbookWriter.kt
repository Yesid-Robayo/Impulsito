package io.github.yesidrobayo.shopledger.data.export

import io.github.yesidrobayo.shopledger.domain.rule.export.SheetValue
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Escribe un `.xlsx` con `java.util.zip`, sin bibliotecas: el paquete mínimo que abren las hojas de
 * cálculo, con una hoja por archivo de la exportación, en el orden dado.
 *
 * - **En flujo.** Cada hoja se escribe fila por fila directo al ZIP, sin armarla antes en memoria.
 * - **Texto como texto.** Cada texto va como cadena en línea (`inlineStr`), nunca como fórmula, y
 *   con los caracteres que XML no admite quitados: un nombre con un carácter de control no rompe el
 *   archivo.
 * - **Números como números.** Van con punto decimal, tal como los escribió el dominio desde el
 *   entero guardado; la hoja los muestra con la región de quien la abre.
 *
 * @param sheetNames los nombres de las hojas, ya en el idioma activo; se acortan a 31 caracteres y
 *   pierden los que una hoja de cálculo no admite en un nombre.
 */
internal class XlsxWorkbookWriter(private val zip: ZipOutputStream, sheetNames: List<String>) {
    private val names = uniqueNames(sheetNames)
    private val writer: Writer = zip.writer(Charsets.UTF_8).buffered(BUFFER_CHARS)
    private var sheetIndex = 0
    private var rowNumber = 0

    /** Escribe las partes fijas del paquete. Va antes de la primera hoja. */
    fun start() {
        entry("[Content_Types].xml", contentTypes())
        entry("_rels/.rels", ROOT_RELATIONS)
        entry("xl/workbook.xml", workbook())
        entry("xl/_rels/workbook.xml.rels", workbookRelations())
        entry("xl/styles.xml", STYLES)
    }

    /** Empieza la hoja siguiente, con [header] como su primera fila. */
    fun startSheet(header: List<String>) {
        sheetIndex++
        rowNumber = 0
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet$sheetIndex.xml"))
        writer.write(XML_DECLARATION)
        writer.write("<worksheet xmlns=\"$MAIN\"><sheetData>")
        row(header.map(SheetValue::Text))
    }

    /** Una fila de datos de la hoja en curso. */
    fun row(cells: List<SheetValue>) {
        rowNumber++
        writer.write("<row r=\"$rowNumber\">")
        cells.forEachIndexed { index, value ->
            val reference = columnName(index) + rowNumber
            when (value) {
                is SheetValue.Text -> {
                    writer.write(
                        "<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">",
                    )
                    writer.write(escaped(value.value))
                    writer.write("</t></is></c>")
                }

                is SheetValue.Number -> {
                    writer.write("<c r=\"$reference\"><v>")
                    writer.write(value.value)
                    writer.write("</v></c>")
                }

                // Una celda vacía no se escribe: la hoja la deja en blanco.
                SheetValue.Empty -> Unit
            }
        }
        writer.write("</row>")
    }

    /** Cierra la hoja en curso. */
    fun endSheet() {
        writer.write("</sheetData></worksheet>")
        writer.flush()
        zip.closeEntry()
    }

    private fun entry(name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        writer.write(content)
        writer.flush()
        zip.closeEntry()
    }

    private fun contentTypes(): String = buildString {
        append(XML_DECLARATION)
        append("<Types xmlns=\"$PACKAGE/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"$PACKAGE_TYPE.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append(
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"$SHEET_TYPE.sheet.main+xml\"/>",
        )
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"$SHEET_TYPE.styles+xml\"/>")
        names.indices.forEach { index ->
            append("<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" ")
            append("ContentType=\"$SHEET_TYPE.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private fun workbook(): String = buildString {
        append(XML_DECLARATION)
        append("<workbook xmlns=\"$MAIN\" xmlns:r=\"$OFFICE/relationships\"><sheets>")
        names.forEachIndexed { index, name ->
            val id = index + 1
            append("<sheet name=\"${escaped(name)}\" sheetId=\"$id\" r:id=\"rId$id\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRelations(): String = buildString {
        append(XML_DECLARATION)
        append("<Relationships xmlns=\"$PACKAGE/relationships\">")
        names.indices.forEach { index ->
            val id = index + 1
            append("<Relationship Id=\"rId$id\" Type=\"$OFFICE/relationships/worksheet\" ")
            append("Target=\"worksheets/sheet$id.xml\"/>")
        }
        val styles = names.size + 1
        append("<Relationship Id=\"rId$styles\" Type=\"$OFFICE/relationships/styles\" ")
        append("Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    companion object {
        private const val BUFFER_CHARS = 64 * 1024
        private const val MAX_SHEET_NAME = 31
        private const val LETTERS = 26
        private const val NON_CHARACTER_FFFE = 0xFFFE
        private const val NON_CHARACTER_FFFF = 0xFFFF
        private const val XML_DECLARATION =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006"
        private const val PACKAGE = "http://schemas.openxmlformats.org/package/2006"
        private const val PACKAGE_TYPE = "application/vnd.openxmlformats-package"
        private const val SHEET_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml"

        /** Lo que una hoja de cálculo no admite en el nombre de una hoja. */
        private val FORBIDDEN_IN_NAME = setOf('[', ']', ':', '*', '?', '/', '\\')

        private val ROOT_RELATIONS =
            XML_DECLARATION +
                "<Relationships xmlns=\"$PACKAGE/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$OFFICE/relationships/officeDocument\" " +
                "Target=\"xl/workbook.xml\"/></Relationships>"

        /** Los estilos mínimos que piden algunas hojas de cálculo para abrir el archivo. */
        private val STYLES =
            XML_DECLARATION +
                "<styleSheet xmlns=\"$MAIN\">" +
                "<fonts count=\"1\"><font><sz val=\"11\"/></font></fonts>" +
                "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
                "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
                "</borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" " +
                "borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" " +
                "borderId=\"0\" " +
                "xfId=\"0\"/></cellXfs></styleSheet>"

        /** La letra de la columna [index], desde cero: 0 es `A`, 25 es `Z` y 26 es `AA`. */
        fun columnName(index: Int): String {
            var rest = index + 1
            val letters = StringBuilder()
            while (rest > 0) {
                val digit = (rest - 1) % LETTERS
                letters.append('A' + digit)
                rest = (rest - 1) / LETTERS
            }
            return letters.reverse().toString()
        }

        /** [text] escapado para XML, sin los caracteres que XML 1.0 no admite. */
        fun escaped(text: String): String = buildString(text.length) {
            var index = 0
            while (index < text.length) {
                val char = text[index]
                when {
                    char.isHighSurrogate() && index + 1 < text.length &&
                        text[index + 1].isLowSurrogate() -> {
                        append(char).append(text[index + 1])
                        index++
                    }

                    char == '&' -> append("&amp;")

                    char == '<' -> append("&lt;")

                    char == '>' -> append("&gt;")

                    char == '"' -> append("&quot;")

                    isAllowed(char) -> append(char)

                    // Un carácter de control o un sustituto suelto no cabe en XML: se quita.
                    else -> Unit
                }
                index++
            }
        }

        private fun isAllowed(char: Char): Boolean = when {
            char == '\t' || char == '\n' || char == '\r' -> true
            char < ' ' -> false
            char.isSurrogate() -> false
            char.code == NON_CHARACTER_FFFE || char.code == NON_CHARACTER_FFFF -> false
            else -> true
        }

        /**
         * Los nombres de las hojas: sin los caracteres prohibidos, de 31 caracteres como máximo y
         * distintos entre sí, porque una hoja de cálculo rechaza un libro con dos hojas iguales.
         */
        fun uniqueNames(names: List<String>): List<String> {
            val used = mutableSetOf<String>()
            return names.mapIndexed { index, name ->
                val clean = name.filterNot { it in FORBIDDEN_IN_NAME }.trim().ifEmpty {
                    "${index + 1}"
                }.take(MAX_SHEET_NAME)
                var candidate = clean
                var copy = 2
                while (!used.add(candidate.lowercase())) {
                    val suffix = " ($copy)"
                    candidate = clean.take(MAX_SHEET_NAME - suffix.length) + suffix
                    copy++
                }
                candidate
            }
        }
    }
}
