package io.github.yesidrobayo.shopledger

import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import io.github.yesidrobayo.shopledger.localization.UiLanguage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * El arnés de la matriz de configuraciones en las pruebas de JVM de `:app`, donde corren los
 * `ViewModel`: las mismas filas que prueba `:domain`, con la zona y el idioma de la JVM cambiados.
 *
 * Un `ViewModel` se prueba con [RegionalMatrix.withForeignDefaults], los ajustes de
 * [RegionalMatrixRow.regionalSettings] y el idioma de la fila. Esta prueba comprueba que cada fila
 * llega completa: unos ajustes con los que el calendario del negocio abre, y un idioma de la
 * interfaz que la app sabe guardar y que tiene sus textos.
 */
@RunWith(Parameterized::class)
class RegionalMatrixHarnessTest(
    private val row: RegionalMatrixRow,
    defaults: ForeignSystemDefaults,
) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    @Test
    fun `each row reaches the app tests with settings the business calendar opens`() {
        val settings = row.regionalSettings()

        assertTrue("$row", BusinessCalendar.of(settings) is BusinessCalendarResult.Ready)
        assertEquals(row.currencyCode, settings.currency.code)
        assertEquals(row.cashRounding, settings.cashRounding)
    }

    @Test
    fun `the interface language of each row is one the app keeps and translates`() {
        val language = UiLanguage.of(row.uiLanguageTag ?: UiLanguage.SYSTEM_VALUE)

        when (language) {
            UiLanguage.System -> assertEquals(null, row.uiLanguageTag)

            is UiLanguage.Chosen -> {
                assertEquals(row.uiLanguageTag, language.tag)
                // El inglés es la carpeta sin sufijo; los demás idiomas tienen la suya.
                val folder = if (language.tag == "en") "values" else "values-${language.tag}"
                assertTrue("$row", File("src/main/res/$folder/strings.xml").isFile)
            }

            null -> error("La fila $row tiene un idioma que la app no sabe guardar")
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun parameters(): List<Array<Any>> = RegionalMatrix.withForeignDefaults()
    }
}
