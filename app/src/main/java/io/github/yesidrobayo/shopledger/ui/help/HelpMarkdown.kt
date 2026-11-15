package io.github.yesidrobayo.shopledger.ui.help

import androidx.compose.runtime.Immutable

/**
 * Un tramo de texto dentro de un bloque: normal, en negrita o un enlace a otro artículo. Un enlace
 * solo lleva a otro artículo de la ayuda, nunca a una dirección de internet.
 */
@Immutable
sealed interface HelpSpan {
    val text: String

    data class Plain(override val text: String) : HelpSpan

    data class Bold(override val text: String) : HelpSpan

    data class Link(override val text: String, val article: HelpArticleId) : HelpSpan
}

/** Un bloque de un artículo, en el orden en que se lee. */
@Immutable
sealed interface HelpBlock {
    /** Un encabezado. [level] 1 es el más grande; el título del artículo no va aquí. */
    data class Heading(val level: Int, val spans: List<HelpSpan>) : HelpBlock

    data class Paragraph(val spans: List<HelpSpan>) : HelpBlock

    /** Una lista. Con [numbered], cada elemento lleva su número; si no, una viñeta. */
    data class ListBlock(val numbered: Boolean, val items: List<List<HelpSpan>>) : HelpBlock
}

/**
 * El Markdown de la ayuda, en el subconjunto que usan sus artículos: encabezados con `#`,
 * párrafos, listas con `-` o `1.`, negrita con `**` y enlaces `[texto](help_clave)` entre
 * artículos. Es propio y pequeño a propósito: sin `WebView` ni dependencias, y lo que no entiende
 * queda como texto, nunca como un error.
 *
 * Un enlace a algo que no es un artículo, como una dirección, queda como texto sin enlace: la
 * ayuda no abre internet. Los acentos graves de código se quitan, y `{app}` se cambia por el
 * nombre de la app, que solo vive en sus recursos.
 */
object HelpMarkdown {
    /** Los bloques de [source], con `{app}` cambiado por [appName]. */
    fun parse(source: String, appName: String): List<HelpBlock> {
        val blocks = mutableListOf<HelpBlock>()
        val paragraph = mutableListOf<String>()
        var list: OpenList? = null

        fun closeParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += HelpBlock.Paragraph(spans(paragraph.joinToString(" "), appName))
                paragraph.clear()
            }
        }

        fun closeList() {
            list?.let { open ->
                blocks += HelpBlock.ListBlock(open.numbered, open.items.map { spans(it, appName) })
            }
            list = null
        }

        for (raw in source.replace("\r\n", "\n").split('\n')) {
            val line = raw.trim()
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val number = NUMBER.matchEntire(line)
            val open = list
            when {
                line.isEmpty() -> {
                    closeParagraph()
                    closeList()
                }

                heading != null -> {
                    closeParagraph()
                    closeList()
                    val level = heading.groupValues[1].length
                    blocks += HelpBlock.Heading(level, spans(heading.groupValues[2], appName))
                }

                bullet != null || number != null -> {
                    closeParagraph()
                    val numbered = number != null
                    val text = (number ?: bullet)?.groupValues?.get(1).orEmpty()
                    if (open == null || open.numbered != numbered) {
                        closeList()
                        list = OpenList(numbered, mutableListOf(text))
                    } else {
                        open.items += text
                    }
                }

                // Una línea con sangría sigue el elemento de la lista que la precede.
                open != null && raw.startsWith(" ") ->
                    open.items[open.items.lastIndex] = open.items.last() + " " + line

                else -> {
                    closeList()
                    paragraph += line
                }
            }
        }
        closeParagraph()
        closeList()
        return blocks
    }

    /** Todo el texto de [blocks], sin marcas, para buscar en él. */
    fun plainText(blocks: List<HelpBlock>): String = blocks.joinToString(" ") { block ->
        when (block) {
            is HelpBlock.Heading -> block.spans.joinToString("") { it.text }

            is HelpBlock.Paragraph -> block.spans.joinToString("") { it.text }

            is HelpBlock.ListBlock ->
                block.items.joinToString(" ") { item -> item.joinToString("") { it.text } }
        }
    }

    /** Los tramos de una línea: negrita y enlaces, con lo demás como texto. */
    private fun spans(line: String, appName: String): List<HelpSpan> {
        val text = line.replace(APP_TOKEN, appName).replace("`", "")
        val spans = mutableListOf<HelpSpan>()
        var cursor = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > cursor) {
                spans += HelpSpan.Plain(text.substring(cursor, match.range.first))
            }
            val bold = match.groups[1]?.value
            spans +=
                if (bold != null) {
                    HelpSpan.Bold(bold)
                } else {
                    val label = match.groupValues[2]
                    val article = HelpArticleId.ofKey(match.groupValues[3])
                    if (article == null) HelpSpan.Plain(label) else HelpSpan.Link(label, article)
                }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) spans += HelpSpan.Plain(text.substring(cursor))
        return merge(spans)
    }

    /** Une los tramos normales seguidos, que salen al ignorar un enlace que no es un artículo. */
    private fun merge(spans: List<HelpSpan>): List<HelpSpan> {
        val merged = mutableListOf<HelpSpan>()
        for (span in spans) {
            val last = merged.lastOrNull()
            if (span is HelpSpan.Plain && last is HelpSpan.Plain) {
                merged[merged.lastIndex] = HelpSpan.Plain(last.text + span.text)
            } else {
                merged += span
            }
        }
        return merged
    }

    private class OpenList(val numbered: Boolean, val items: MutableList<String>)

    private const val APP_TOKEN = "{app}"
    private val HEADING = Regex("""^(#{1,3})\s+(.+)$""")
    private val BULLET = Regex("""^[-*]\s+(.+)$""")
    private val NUMBER = Regex("""^\d{1,3}[.)]\s+(.+)$""")
    private val INLINE = Regex("""\*\*(.+?)\*\*|\[([^\]]+)]\(([^)\s]*)\)""")
}
