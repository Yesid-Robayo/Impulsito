package io.github.yesidrobayo.shopledger.localization

import android.app.LocaleConfig
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.ui.i18n.LanguageSettingsScreen
import io.github.yesidrobayo.shopledger.ui.i18n.LanguageSettingsUiState
import io.github.yesidrobayo.shopledger.ui.i18n.languageFacts
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los idiomas del selector en el teléfono: salen de la configuración generada, son los mismos que
 * Android muestra desde Android 13, un idioma sin carpeta cae en inglés, y el idioma nunca cambia
 * cómo se escriben los montos.
 */
@RunWith(AndroidJUnit4::class)
class InstalledLanguagesTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theListComesFromTheGeneratedConfigWithoutPseudoLocales() {
        val tags = InstalledLanguages.tags(context.resources)

        assertTrue(tags.containsAll(listOf("en", "es", "pt")))
        assertTrue(tags.none { it.endsWith("-XA") || it.endsWith("-XB") })
    }

    @Test
    fun fromAndroid13TheListIsTheSameAndroidShows() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val android = LocaleConfig(context).supportedLocales
        val shown =
            (0 until requireNotNull(android).size())
                .map { android[it].toLanguageTag() }
                .filterNot(InstalledLanguages::isPseudoLocale)

        // Android no promete un orden: se comparan los idiomas, sin repetidos.
        val tags = InstalledLanguages.tags(context.resources)
        assertEquals(shown.toSet(), tags.toSet())
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun aLanguageWithoutResourcesFallsBackToEnglish() {
        val french =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.FRENCH) },
            )

        assertEquals("en", french.getString(R.string.resources_language))
        assertFalse("fr" in InstalledLanguages.tags(context.resources))
    }

    @Test
    fun theLanguageNeverChangesHowAmountsAreWritten() {
        val format = requireNotNull(FormatLocale.of("es"))
        val code = requireNotNull(CurrencyCode.of("XTS"))
        val written =
            listOf(Locale.ENGLISH, Locale.forLanguageTag("es"), Locale.forLanguageTag("pt")).map {
                MoneyFormatter(format, code, 2, null, it).format(Money(-123_456))
            }

        assertEquals(1, written.toSet().size)
    }

    @Test
    fun theChosenLanguageIsTheSelectedOption() {
        val installed = InstalledLanguages.tags(context.resources)
        val facts = languageFacts(installed, "en")
        val spanish = requireNotNull(facts.options.firstOrNull { it.tag == "es" }).name
        val portuguese = requireNotNull(facts.options.firstOrNull { it.tag == "pt" }).name
        compose.setContent {
            PreviewTheme {
                LanguageSettingsScreen(
                    uiState = LanguageSettingsUiState(selected = "es", installed = installed),
                    facts = facts,
                    onAction = {},
                    onBack = {},
                    onOpenFormat = null,
                )
            }
        }

        compose.onNodeWithText(spanish).assertIsSelected()
        compose.onNodeWithText(portuguese).assertIsNotSelected()
    }
}
