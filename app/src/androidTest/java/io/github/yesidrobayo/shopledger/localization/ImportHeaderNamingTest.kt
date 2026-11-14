package io.github.yesidrobayo.shopledger.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.rule.imports.ColumnMatch
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportColumnMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los encabezados de importar con los recursos reales de la app: un archivo exportado en
 * cualquiera de los tres idiomas se reconoce entero con la app en cualquiera de los tres.
 */
@RunWith(AndroidJUnit4::class)
class ImportHeaderNamingTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val languages = listOf("en", "es", "pt")

    @Test
    fun aProductsFileExportedInSpanishIsRecognizedWithTheAppInEnglish() {
        val spanish = exportedHeaders(CsvExportTable.PRODUCTS, "es")
        val matcher = ImportColumnMatcher(ImportHeaderNaming(contextIn("en")))

        val matches = matcher.match(spanish, ImportTarget.PRODUCTS)

        assertEquals(
            listOf(
                ColumnMatch.Field("name"),
                ColumnMatch.Field("category"),
                ColumnMatch.Field("default_supplier"),
                ColumnMatch.Field("base_unit"),
                ColumnMatch.Field("stock_mode"),
                ColumnMatch.Field("barcode"),
                ColumnMatch.Field("sale_price"),
                ColumnMatch.Field("average_cost"),
                ColumnMatch.Field("initial_cost"),
                ColumnMatch.Ignored("stock_value"),
                ColumnMatch.Field("on_hand"),
                ColumnMatch.Field("min_stock"),
                ColumnMatch.Ignored("archived"),
            ),
            matches,
        )
    }

    @Test
    fun everyExportedFileIsRecognizedInEveryLanguageWhateverTheAppLanguage() {
        val exported =
            mapOf(
                ImportTarget.PRODUCTS to CsvExportTable.PRODUCTS,
                ImportTarget.SUPPLIERS to CsvExportTable.SUPPLIERS,
            )
        for (appLanguage in languages) {
            val matcher = ImportColumnMatcher(ImportHeaderNaming(contextIn(appLanguage)))
            for ((target, table) in exported) {
                for (fileLanguage in languages) {
                    val matches = matcher.match(exportedHeaders(table, fileLanguage), target)
                    assertTrue(
                        "$table en $fileLanguage con la app en $appLanguage: $matches",
                        matches.none { it == ColumnMatch.Unmatched },
                    )
                }
            }
        }
    }

    @Test
    fun everyFieldOfEveryTargetHasAHeaderInEachLanguage() {
        val naming = ImportHeaderNaming(app)
        for (target in ImportTarget.entries) {
            for (field in target.fields) {
                val labels = naming.labels(field.key)
                assertTrue("${field.key}: $labels", labels.isNotEmpty())
                languages.forEach { language ->
                    val header = CsvExportNaming(resourcesIn(language)).header(field.key)
                    assertTrue("${field.key} en $language", header in labels)
                }
            }
        }
    }

    @Test
    fun aKeyWithoutHeaderOrAliasHasNoLabels() {
        // Toda columna exportada ya tiene encabezado: una clave inventada no tiene ninguno.
        assertEquals(emptyList<String>(), ImportHeaderNaming(app).labels("not_a_column"))
        assertEquals(null, ImportHeaderNaming.aliasRes("name"))
    }

    private fun exportedHeaders(table: CsvExportTable, language: String): List<String> {
        val naming = CsvExportNaming(resourcesIn(language))
        return table.columns.map(naming::header)
    }

    private fun resourcesIn(language: String): () -> android.content.res.Resources {
        val resources = contextIn(language).resources
        return { resources }
    }

    private fun contextIn(language: String): Context {
        val configuration =
            Configuration(app.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language))
            }
        return app.createConfigurationContext(configuration)
    }
}
