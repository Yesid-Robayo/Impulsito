package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Búsqueda por nombre en los tres idiomas de la app: lo que la persona escribe en el buscador
 * encuentra, o no, un nombre guardado. La base compara por contenido el nombre normalizado y el
 * texto buscado, normalizado con la misma regla, y aquí se reproduce esa comparación con
 * `contains`.
 *
 * Cada prueba corre con el teléfono en cada idioma de la app, con sus variantes regionales, y con
 * los idiomas ajenos que atrapan una regla que lea el del equipo. Lo que se encuentra no cambia con
 * ninguno. Los resultados esperados están escritos a mano. Las letras compuestas van escritas tal
 * cual, para que la tabla se lea, y las marcas sueltas van con su código.
 */
@RunWith(Parameterized::class)
class TextNormalizerSearchTest(private val defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    /** Un nombre guardado, lo que se escribe en el buscador y si debe encontrarlo. */
    private data class Search(val storedName: String, val typed: String, val finds: Boolean)

    @Test
    fun `the test really runs with the phone in the given language`() {
        assertEquals(Locale.forLanguageTag(defaults.languageTag), Locale.getDefault())
    }

    @Test
    fun `the documented folding examples give the documented normalized names`() {
        val examples =
            listOf(
                "Maçã" to "maca",
                "AÇAÍ" to "acai",
                "Piña" to "piña",
            )
        for ((name, expected) in examples) {
            assertEquals(name, expected, TextNormalizer.normalize(name))
        }
    }

    @Test
    fun `portuguese names are found without cedilla, tilde, circumflex or grave accent`() {
        assertSearches(
            listOf(
                Search("Maçã verde", "maca", finds = true),
                Search("Açúcar mascavo", "ACUCAR", finds = true),
                Search("Pão de queijo", "pao de", finds = true),
                Search("Feijão preto", "feijao", finds = true),
                Search("Limões sicilianos", "limoes", finds = true),
                Search("Pêssego em calda", "pessego", finds = true),
                Search("Bolo de avô", "avo", finds = true),
                Search("Frango à passarinho", "a passarinho", finds = true),
                Search("Água de coco", "agua", finds = true),
                // Escrito con la marca, encuentra un nombre guardado sin ella.
                Search("Pao de queijo", "Pão", finds = true),
                Search("Coracao de frango", "CORAÇÃO", finds = true),
                // La cedilla y la virgulilla escritas aparte, como las deja una tecla muerta.
                Search("Maçã verde", "mac\u0327a\u0303", finds = true),
                // Plegar no inventa letras: otra palabra no se encuentra.
                Search("Pão de queijo", "pan", finds = false),
            ),
        )
    }

    @Test
    fun `spanish names are found without accents or diaeresis, but enie stays its own letter`() {
        assertSearches(
            listOf(
                Search("Jabón líquido", "jabon", finds = true),
                Search("Anís estrellado", "ANIS", finds = true),
                Search("Pingüino de peluche", "pinguino", finds = true),
                Search("Champú de romero", "champu", finds = true),
                Search("Limon", "Limón", finds = true),
                Search("Piña colada", "PIÑA", finds = true),
                Search("Piña colada", "piña col", finds = true),
                Search("Año nuevo", "año", finds = true),
                Search("Cigüeña de cerámica", "cigueña", finds = true),
                // La virgulilla escrita aparte también forma la ñ.
                Search("Piña colada", "pin\u0303a", finds = true),
                // Sin la ñ no se encuentra: Piña y Pina son productos distintos.
                Search("Piña colada", "pina", finds = false),
                Search("Año nuevo", "ano", finds = false),
                Search("Pina", "piña", finds = false),
            ),
        )
    }

    @Test
    fun `english names are found in any case and without the accents of borrowed words`() {
        assertSearches(
            listOf(
                Search("Liquid Soap", "liquid soap", finds = true),
                Search("liquid soap", "LIQUID", finds = true),
                Search("Café latte", "cafe", finds = true),
                Search("Crème brûlée", "creme brulee", finds = true),
                Search("Rosé wine", "rose", finds = true),
                Search("Naïve art print", "naive", finds = true),
                Search("Façade paint", "facade", finds = true),
                Search("Cafe latte", "CAFÉ", finds = true),
                // Blancos raros y de más, como llegan al pegar texto.
                Search("Liquid Soap", "\u00A0liquid\t soap ", finds = true),
                // Una palabra prestada del español conserva su ñ, también en inglés.
                Search("Jalapeño peppers", "jalapeño", finds = true),
                Search("Jalapeño peppers", "jalapeno", finds = false),
            ),
        )
    }

    @Test
    fun `capital I searches find the name with the phone in any language`() {
        // Con el teléfono en turco o azerí, una minúscula que leyera el idioma daría una ı sin
        // punto, y en lituano una i con el punto aparte: la búsqueda dejaría de encontrar.
        assertSearches(
            listOf(
                Search("Limón", "LIMON", finds = true),
                Search("Açaí", "AÇAÍ", finds = true),
                Search("Índigo en polvo", "ÍNDIGO", finds = true),
                Search("INDIO", "indio", finds = true),
            ),
        )
    }

    private fun assertSearches(searches: List<Search>) {
        for (search in searches) {
            val found =
                TextNormalizer.normalize(search.storedName)
                    .contains(TextNormalizer.normalize(search.typed))
            assertEquals("${search.typed} en ${search.storedName}", search.finds, found)
        }
    }

    companion object {
        /**
         * El teléfono en los tres idiomas de la app, con variantes regionales que la app reúne en
         * una sola carpeta de textos. La zona es cualquiera distinta de UTC: aquí no importa.
         */
        private val APP_LANGUAGES: List<ForeignSystemDefaults> =
            listOf(
                ForeignSystemDefaults("Etc/GMT+3", "es"),
                ForeignSystemDefaults("Etc/GMT+5", "es-419"),
                ForeignSystemDefaults("Etc/GMT-1", "es-ES"),
                ForeignSystemDefaults("Etc/GMT+4", "en-US"),
                ForeignSystemDefaults("Etc/GMT-2", "en-GB"),
                ForeignSystemDefaults("Etc/GMT+2", "pt-BR"),
                ForeignSystemDefaults("Etc/GMT-6", "pt-PT"),
            )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun defaults(): List<ForeignSystemDefaults> =
            APP_LANGUAGES + ForeignSystemDefaults.ALL + ForeignSystemDefaults.SPECIAL_CASING
    }
}
