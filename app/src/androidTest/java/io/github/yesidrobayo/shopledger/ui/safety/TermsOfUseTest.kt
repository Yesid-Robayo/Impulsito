package io.github.yesidrobayo.shopledger.ui.safety

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los términos de uso de Ajustes, desde los recursos: están enteros en los tres idiomas, cada uno
 * traducido, sin un número de ley ni un país, y la hoja muestra cada párrafo. Lo que dicen es
 * neutro: qué hace la app, que se entrega tal cual, quién responde de los datos y de las
 * obligaciones locales, y qué pasa con el código de recuperación.
 */
@RunWith(AndroidJUnit4::class)
class TermsOfUseTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun resources(language: String): Resources {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration).resources
    }

    private fun paragraphs(resources: Resources): List<String> {
        val app = resources.getString(R.string.app_name)
        return PARAGRAPHS.map { id -> resources.getString(id, app) }
    }

    @Test
    fun everyParagraphIsWrittenAndTranslatedInTheThreeLanguages() {
        val english = paragraphs(resources("en"))
        for (language in listOf("es", "pt")) {
            val translated = paragraphs(resources(language))
            for ((index, paragraph) in translated.withIndex()) {
                assertTrue("$language $index", paragraph.isNotBlank())
                assertNotEquals("$language $index", english[index], paragraph)
            }
        }
        assertTrue(english.all { it.isNotBlank() })
    }

    @Test
    fun noParagraphCitesALawByNumber() {
        for (language in listOf("es", "en", "pt")) {
            for (paragraph in paragraphs(resources(language))) {
                assertFalse("$language: $paragraph", paragraph.any(Char::isDigit))
            }
        }
    }

    @Test
    fun theSheetShowsEveryParagraph() {
        compose.setContent {
            PreviewTheme {
                PolicyQuestions(
                    state = PolicyPromptState(terms = true),
                    onAccept = {},
                    onLater = {},
                    onOpenTerms = {},
                    onCloseTerms = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.legal_terms_title)).assertIsDisplayed()
        for (paragraph in paragraphs(context.resources)) {
            compose.onNodeWithText(paragraph).performScrollTo().assertIsDisplayed()
        }
    }

    private companion object {
        /** Los párrafos en el orden de la hoja. Los que no nombran la app ignoran el argumento. */
        val PARAGRAPHS =
            listOf(
                R.string.legal_terms_what,
                R.string.legal_terms_as_is,
                R.string.legal_terms_responsibility,
                R.string.legal_terms_no_substitute,
                R.string.legal_terms_third_party,
                R.string.legal_terms_recovery,
                R.string.legal_terms_changes,
            )
    }
}
