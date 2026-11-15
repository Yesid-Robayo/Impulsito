package io.github.yesidrobayo.shopledger.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Cada idioma de la app está completo: cada texto traducible de `values/` existe en cada
 * carpeta de idioma, con el mismo tipo, los mismos marcadores y los plurales y listas enteros,
 * y cada artículo de ayuda tiene su par en el idioma, con las mismas líneas.
 *
 * Las carpetas de idioma se descubren en el disco, sin ninguna lista escrita aquí: agregar un
 * idioma es agregar `values-xx/` y `raw-xx/`, y esta prueba lo revisa igual que a los de hoy,
 * sin tocar código. Complementa a `MissingTranslation` de lint, que no compara marcadores ni la
 * ayuda. Lee los archivos del módulo, sin dispositivo.
 */
class TranslationCompletenessTest {
    private val res = File("src/main/res")
    private val base = texts(res.resolve("values"))

    /** Sin esto, una carpeta mal leída dejaría pasar las demás pruebas sin revisar nada. */
    @Test
    fun theLanguageFoldersAreFoundOnDisk() {
        val folders = languageFolders()
        assertTrue(folders.toString(), folders.size >= MIN_LANGUAGE_FOLDERS)
        assertTrue(base.size > MIN_TEXTS)
        folders.forEach { assertTrue(it.name, texts(it).size > MIN_TEXTS) }
    }

    @Test
    fun everyTranslatableTextExistsInEveryLanguageWithTheSameKind() {
        val problems = languageFolders().flatMap { folder ->
            val texts = texts(folder)
            base.mapNotNull { (key, text) ->
                val other = texts[key]
                when {
                    other == null -> "${folder.name}: falta $key"
                    other.kind != text.kind -> "${folder.name}: $key es ${other.kind}"
                    else -> null
                }
            } + texts.keys.filterNot(base::containsKey).map { "${folder.name}: sobra $it" }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test
    fun everyLanguageKeepsTheSameMarkers() {
        val problems = languageFolders().flatMap { folder ->
            val texts = texts(folder)
            base.mapNotNull { (key, text) ->
                val other = texts[key] ?: return@mapNotNull null
                if (markers(other) == markers(text)) {
                    null
                } else {
                    "${folder.name}: $key lleva ${markers(other)} y no ${markers(text)}"
                }
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test
    fun pluralsHaveOneAndOtherAndListsKeepTheirLength() {
        val folders = listOf(res.resolve("values")) + languageFolders()
        val problems = folders.flatMap { folder ->
            texts(folder).mapNotNull { (key, text) ->
                when {
                    text.kind == PLURALS && !text.items.keys.containsAll(REQUIRED_QUANTITIES) ->
                        "${folder.name}: $key solo tiene ${text.items.keys}"

                    text.kind == ARRAY && text.items.size != base[key]?.items?.size ->
                        "${folder.name}: $key tiene ${text.items.size} elementos"

                    else -> null
                }
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test
    fun everyHelpArticleExistsInEveryLanguageWithTheSameLines() {
        val articles = res.resolve("raw").listFiles { file -> file.name.startsWith(HELP_PREFIX) }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue(articles.isNotEmpty())
        val problems = languageFolders().flatMap { folder ->
            val raw = res.resolve(folder.name.replaceFirst("values", "raw"))
            articles.mapNotNull { article ->
                val twin = raw.resolve(article.name)
                when {
                    !twin.isFile -> "${raw.name}: falta ${article.name}"

                    lines(twin) != lines(article) ->
                        "${raw.name}: ${article.name} tiene ${lines(twin)} líneas"

                    else -> null
                }
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    /** Las carpetas `values-xx`, `values-xx-rYY` o `values-b+…` que nombran un idioma. */
    private fun languageFolders(): List<File> = res
        .listFiles { file -> file.isDirectory && LANGUAGE_FOLDER.matches(file.name) }
        .orEmpty()
        .sortedBy { it.name }

    private fun lines(file: File) = file.readLines().size

    /** Los marcadores de un texto, sin repetir: `%1$s` puede salir dos veces en un idioma. */
    private fun markers(text: Text): Set<String> = text.items.values
        .flatMap { item -> MARKER.findAll(item).map { it.value }.toList() }
        .toSet()

    /** Los textos traducibles de una carpeta, de todos sus archivos `.xml`. */
    private fun texts(folder: File): Map<String, Text> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val files = folder.listFiles { file -> file.extension == "xml" }.orEmpty()
        return files
            .sortedBy { it.name }
            .flatMap { file ->
                val nodes = factory.newDocumentBuilder().parse(file).documentElement.childNodes
                (0 until nodes.length)
                    .mapNotNull { nodes.item(it) as? Element }
                    .filter { it.tagName in KINDS && it.getAttribute("translatable") != "false" }
                    .map { it.getAttribute("name") to text(it) }
            }.toMap()
    }

    private fun text(element: Element): Text {
        if (element.tagName == STRING) return Text(STRING, mapOf(STRING to element.textContent))
        val nodes = element.getElementsByTagName("item")
        val items = (0 until nodes.length).map { nodes.item(it) as Element }
        val named = items.mapIndexed { index, item ->
            val name = if (element.tagName == PLURALS) item.getAttribute("quantity") else "$index"
            name to item.textContent
        }
        return Text(element.tagName, named.toMap())
    }

    private data class Text(val kind: String, val items: Map<String, String>)

    private companion object {
        const val STRING = "string"
        const val PLURALS = "plurals"
        const val ARRAY = "string-array"
        const val HELP_PREFIX = "help_"

        /** Español y portugués hoy, además del inglés de `values/`. */
        const val MIN_LANGUAGE_FOLDERS = 2

        /** Muy por debajo de los textos de hoy: solo descarta una carpeta vacía o mal leída. */
        const val MIN_TEXTS = 1000
        val KINDS = setOf(STRING, PLURALS, ARRAY)
        val REQUIRED_QUANTITIES = setOf("one", "other")
        val LANGUAGE_FOLDER = Regex("""^values-(b\+[A-Za-z0-9+]+|[a-z]{2,3}(-r[A-Z]{2})?)$""")
        val MARKER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc]""")
    }
}
