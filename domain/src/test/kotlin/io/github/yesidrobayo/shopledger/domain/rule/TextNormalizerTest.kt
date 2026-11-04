package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Nombre normalizado, el que decide si dos nombres chocan y si una búsqueda encuentra un producto.
 * Cada prueba corre con idiomas por defecto de la JVM elegidos para que, si la regla leyera el del
 * equipo, el resultado cambiara: dos con cifras y calendario de otro sistema, y tres con reglas
 * propias de minúsculas. Los resultados esperados están escritos a mano. Las marcas sueltas, los
 * blancos raros y las letras de la tabla van con su código, para que se vean y para que ningún
 * editor las cambie por otra forma.
 */
@RunWith(Parameterized::class)
class TextNormalizerTest(private val defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    @Test
    fun `the test really runs with a foreign default language`() {
        assertEquals(Locale.forLanguageTag(defaults.languageTag), Locale.getDefault())
    }

    @Test
    fun `a lowercase that read the default language would change with the special casing ones`() {
        // Demuestra que estos idiomas por defecto sí atrapan una regla que lea el del equipo: con
        // ellos, INDIOÌ no da indioì.
        val readsTheDefault = "INDIO\u00CC".lowercase(Locale.getDefault())

        assertEquals(
            defaults in ForeignSystemDefaults.SPECIAL_CASING,
            readsTheDefault != "indio\u00EC",
        )
    }

    @Test
    fun `the documented examples give the documented normalized names`() {
        val examples =
            listOf(
                "Jabón Líquido" to "jabon liquido",
                "  JABÓN   LÍQUIDO " to "jabon liquido",
                "Maçã" to "maca",
                "Piña" to "piña",
                "PIÑA" to "piña",
                "Pina" to "pina",
                "Pingüino" to "pinguino",
                "Jabo\u0301n" to "jabon",
                "Pin\u0303a" to "piña",
                // Con el turco por defecto, una minúscula que leyera el idioma daría ı sin punto.
                "INDIO" to "indio",
            )
        for ((name, expected) in examples) {
            assertEquals(name, expected, normalize(name))
        }
    }

    @Test
    fun `names that differ only in capitals, accents or spaces collide`() {
        assertEquals(normalize("Jabón Líquido"), normalize("jabon liquido"))
        assertEquals(normalize("Jabón Líquido"), normalize(" JABON  LIQUIDO"))
    }

    @Test
    fun `enie is another letter, so the names with and without it do not collide`() {
        assertNotEquals(normalize("Piña"), normalize("Pina"))
    }

    @Test
    fun `searching jabon finds the accented name and searching pina does not find the enie one`() {
        // La búsqueda compara por contenido el texto buscado y el nombre, los dos normalizados.
        assertTrue(normalize("Jabón Líquido").contains(normalize("jabon")))
        assertTrue(normalize("Jabón Líquido").contains(normalize("LÍQUIDO")))
        assertTrue(normalize("Piña colada").contains(normalize("PIÑA")))
        assertFalse(normalize("Piña").contains(normalize("pina")))
    }

    @Test
    fun `enie in both cases is kept and stored composed`() {
        assertEquals("\u00F1", normalize("\u00F1"))
        assertEquals("\u00F1", normalize("\u00D1"))
        assertEquals("pi\u00F1a", normalize("PIN\u0303A"))
    }

    @Test
    fun `a name written with separate marks gives the same as the composed one`() {
        // Maçã y Pingüino con cada marca aparte.
        assertEquals("maca", normalize("Mac\u0327a\u0303"))
        assertEquals("pinguino", normalize("Pingu\u0308ino"))
        assertEquals("jabon liquido", normalize("JABO\u0301N LI\u0301QUIDO"))
    }

    @Test
    fun `only a tilde right after n forms enie`() {
        assertEquals("n\u00F1", normalize("nn\u0303"))
        // La marca que sigue a la ñ ya formada se quita.
        assertEquals("\u00F1", normalize("n\u0303\u0301"))
        // Con otra marca en medio, la secuencia no es la ñ: las dos marcas se quitan.
        assertEquals("n", normalize("n\u0301\u0303"))
        // Sobre otra letra, la virgulilla solo se quita.
        assertEquals("ao", normalize("a\u0303o\u0303"))
        // Al comienzo no hay letra antes.
        assertEquals("n", normalize("\u0303n"))
    }

    @Test
    fun `only the marks of the general diacritics block are dropped`() {
        // Justo antes del bloque, su primera y su última marca, y justo después.
        assertEquals("a\u02FFb", normalize("a\u02FFb"))
        assertEquals("ab", normalize("a\u0300b"))
        assertEquals("ab", normalize("a\u036Fb"))
        assertEquals("a\u0374b", normalize("a\u0374b"))
        // Una marca combinante de otro bloque queda igual: la tabla es acotada.
        assertEquals("a\u1DC4b", normalize("a\u1DC4b"))
    }

    @Test
    fun `every letter of the table folds to its plain letter in lowercase and in capitals`() {
        val table =
            listOf(
                // à á â ã ä À Á Â Ã Ä
                "\u00E0\u00E1\u00E2\u00E3\u00E4\u00C0\u00C1\u00C2\u00C3\u00C4" to "a",
                // è é ê ẽ ë È É Ê Ẽ Ë
                "\u00E8\u00E9\u00EA\u1EBD\u00EB\u00C8\u00C9\u00CA\u1EBC\u00CB" to "e",
                // ì í î ĩ ï Ì Í Î Ĩ Ï
                "\u00EC\u00ED\u00EE\u0129\u00EF\u00CC\u00CD\u00CE\u0128\u00CF" to "i",
                // ò ó ô õ ö Ò Ó Ô Õ Ö
                "\u00F2\u00F3\u00F4\u00F5\u00F6\u00D2\u00D3\u00D4\u00D5\u00D6" to "o",
                // ù ú û ũ ü Ù Ú Û Ũ Ü
                "\u00F9\u00FA\u00FB\u0169\u00FC\u00D9\u00DA\u00DB\u0168\u00DC" to "u",
                // ç Ç
                "\u00E7\u00C7" to "c",
            )
        for ((letters, plain) in table) {
            for (letter in letters) {
                assertEquals("U+${letter.code.toString(16)}", plain, normalize(letter.toString()))
            }
        }
    }

    @Test
    fun `a letter outside the table keeps its mark`() {
        // Å Ø Ÿ ß ı no son de los tres idiomas de la app. Cambiar esto obliga a migrar la columna.
        assertEquals(
            "\u00E5\u00F8\u00FF\u00DF\u0131",
            normalize("\u00C5\u00D8\u0178\u00DF\u0131"),
        )
    }

    @Test
    fun `a capital I with dot above gives a plain i`() {
        // Sin el idioma, su minúscula es una i con el punto aparte, y el paso de las marcas lo quita.
        assertEquals("indio", normalize("\u0130ND\u0130O"))
    }

    @Test
    fun `any run of blanks becomes one space and the ends are trimmed`() {
        // Tabulador, espacio duro, saltos de línea y espacio ancho, como llegan al pegar texto.
        assertEquals("jabon liquido", normalize("\tJabón\u00A0\u00A0Líquido\n"))
        assertEquals("a b c", normalize("a \t b\r\n\u2003c"))
        // La marca sola entre espacios se quita antes de juntar los espacios.
        assertEquals("a b", normalize("a \u0301 b"))
    }

    @Test
    fun `an empty, blank or marks only name normalizes to empty`() {
        assertEquals("", normalize(""))
        assertEquals("", normalize("   "))
        assertEquals("", normalize("\u0301\u0303 \u0308"))
    }

    @Test
    fun `digits, punctuation, like wildcards and emoji pass through`() {
        // Quien arma la búsqueda escapa los comodines, no el nombre guardado.
        assertEquals("50% off_x\\2", normalize("50% OFF_X\\2"))
        assertEquals("coco \uD83E\uDD65", normalize("Coco \uD83E\uDD65"))
    }

    @Test
    fun `normalizing twice gives the same as once`() {
        val names =
            listOf(
                "  JABÓN   LÍQUIDO ",
                "Maçã",
                "PIN\u0303A",
                "\u0130NDIO",
                "n\u0301\u0303",
                "Coco \uD83E\uDD65",
            )
        for (name in names) {
            val once = normalize(name)
            assertEquals(name, once, normalize(once))
        }
    }

    @Test
    fun `no character grows when normalized`() {
        // Si alguno creciera, un nombre dentro de su tope podría dar un nombre normalizado que la
        // base rechaza por largo. Se revisan todos los puntos de código, uno por uno.
        for (codePoint in 0..0x10FFFF) {
            val text = String(Character.toChars(codePoint))
            val normalized = normalize(text)
            if (normalized.length > text.length ||
                normalized.codePointCount(0, normalized.length) > 1
            ) {
                fail("U+${codePoint.toString(16)} crece al normalizarse")
            }
        }
        // La única letra cuya minúscula ocupa dos caracteres tampoco crece en un nombre largo.
        assertEquals("i".repeat(80), normalize("\u0130".repeat(80)))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun defaults(): List<ForeignSystemDefaults> =
            ForeignSystemDefaults.ALL + ForeignSystemDefaults.SPECIAL_CASING

        fun normalize(text: String): String = TextNormalizer.normalize(text)
    }
}
