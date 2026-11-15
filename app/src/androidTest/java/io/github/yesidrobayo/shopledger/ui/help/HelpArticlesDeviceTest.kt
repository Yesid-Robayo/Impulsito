package io.github.yesidrobayo.shopledger.ui.help

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono, sin red: cada artículo de la ayuda abre en inglés, español y portugués desde
 * `raw/`, `raw-es/` y `raw-pt/`, tiene texto, cada idioma tiene su propia versión, y ninguno lleva
 * una dirección de internet ni deja el nombre de la app sin cambiar.
 */
@RunWith(AndroidJUnit4::class)
class HelpArticlesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun resourcesIn(language: String): Resources {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration).resources
    }

    private fun read(resources: Resources, id: HelpArticleId): String =
        resources.openRawResource(id.body).bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun everyArticleOpensInEachLanguageWithText() {
        for (language in LANGUAGES) {
            val resources = resourcesIn(language)
            val app = resources.getString(R.string.app_name)
            for (id in HelpArticleId.entries) {
                val where = "$language ${id.key}"
                val blocks = HelpMarkdown.parse(read(resources, id), app)
                val text = HelpMarkdown.plainText(blocks)
                assertTrue(where, blocks.isNotEmpty())
                assertFalse(where, "{app}" in text)
                assertFalse(where, Regex("https?://|www\\.").containsMatchIn(read(resources, id)))
                assertTrue(where, resources.getString(id.title, app).isNotBlank())
            }
        }
    }

    @Test
    fun eachLanguageHasItsOwnVersion() {
        val english = resourcesIn("en")
        val spanish = resourcesIn("es")
        val portuguese = resourcesIn("pt")
        for (id in HelpArticleId.entries) {
            assertNotEquals(id.key, read(english, id), read(spanish, id))
            assertNotEquals(id.key, read(english, id), read(portuguese, id))
            assertNotEquals(id.key, read(spanish, id), read(portuguese, id))
        }
    }

    @Test
    fun aRegionalVariantFallsInItsLanguage() {
        // Un teléfono en una variante regional lee la carpeta de su idioma, sin una propia.
        for (id in HelpArticleId.entries) {
            assertTrue(id.key, read(resourcesIn("es-AR"), id) == read(resourcesIn("es"), id))
            assertTrue(id.key, read(resourcesIn("pt-PT"), id) == read(resourcesIn("pt"), id))
        }
    }

    private companion object {
        val LANGUAGES = listOf("en", "es", "pt")
    }
}
