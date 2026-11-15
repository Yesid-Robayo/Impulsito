package io.github.yesidrobayo.shopledger.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El Markdown propio de la ayuda: lo que usan los artículos se entiende, y lo demás queda como
 * texto, nunca como un error ni como un enlace a internet. Los textos son inventados.
 */
class HelpMarkdownTest {
    private fun parse(source: String) = HelpMarkdown.parse(source, APP)

    @Test
    fun `headings take their level and paragraphs join their lines`() {
        val blocks = parse("## Cada día\n\nPrimera línea\nsegunda línea.\n\n### Detalle")

        assertEquals(
            listOf(
                HelpBlock.Heading(2, listOf(HelpSpan.Plain("Cada día"))),
                HelpBlock.Paragraph(listOf(HelpSpan.Plain("Primera línea segunda línea."))),
                HelpBlock.Heading(3, listOf(HelpSpan.Plain("Detalle"))),
            ),
            blocks,
        )
    }

    @Test
    fun `bullets and numbers make separate lists and indented lines continue an item`() {
        val blocks = parse("- Uno\n  sigue\n* Dos\n1. Primero\n2) Segundo")

        assertEquals(
            listOf(
                HelpBlock.ListBlock(
                    numbered = false,
                    items =
                        listOf(listOf(HelpSpan.Plain("Uno sigue")), listOf(HelpSpan.Plain("Dos"))),
                ),
                HelpBlock.ListBlock(
                    numbered = true,
                    items =
                        listOf(
                            listOf(HelpSpan.Plain("Primero")),
                            listOf(HelpSpan.Plain("Segundo")),
                        ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `a paragraph after a list closes it`() {
        val blocks = parse("- Uno\nDespués")

        assertEquals(
            listOf(
                HelpBlock.ListBlock(false, listOf(listOf(HelpSpan.Plain("Uno")))),
                HelpBlock.Paragraph(listOf(HelpSpan.Plain("Después"))),
            ),
            blocks,
        )
    }

    @Test
    fun `bold and links to articles are spans, and a link elsewhere stays plain text`() {
        val blocks =
            parse(
                "Tocar **Respaldar ahora**. Ver [Restaurar](help_restore) y " +
                    "[otra página](https://example.com) o [nada](help_missing).",
            )

        assertEquals(
            listOf(
                HelpBlock.Paragraph(
                    listOf(
                        HelpSpan.Plain("Tocar "),
                        HelpSpan.Bold("Respaldar ahora"),
                        HelpSpan.Plain(". Ver "),
                        HelpSpan.Link("Restaurar", HelpArticleId.RESTORE),
                        HelpSpan.Plain(" y otra página o nada."),
                    ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `the app name replaces its token and code marks disappear`() {
        val blocks = parse("{app} guarda `Todo guardado`.\r\n")

        assertEquals(
            listOf(HelpBlock.Paragraph(listOf(HelpSpan.Plain("$APP guarda Todo guardado.")))),
            blocks,
        )
    }

    @Test
    fun `an empty article has no blocks`() {
        assertTrue(parse("\n\n").isEmpty())
    }

    @Test
    fun `plain text joins every block without marks`() {
        val blocks = parse("## Título\n\nTexto **fuerte**.\n\n- Uno\n- Dos")

        assertEquals("Título Texto fuerte. Uno Dos", HelpMarkdown.plainText(blocks))
    }

    private companion object {
        const val APP = "Tienda"
    }
}
