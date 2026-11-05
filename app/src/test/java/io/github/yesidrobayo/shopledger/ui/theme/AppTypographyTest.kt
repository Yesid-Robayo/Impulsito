package io.github.yesidrobayo.shopledger.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La tipografía no cae en la fuente del sistema, ninguna cifra queda sin cifras tabulares, y el
 * archivo variable de Figtree se pide en el peso correcto.
 */
class AppTypographyTest {
    private val roles: List<Pair<String, TextStyle>> =
        with(AppTypography) {
            listOf(
                "displayLarge" to displayLarge,
                "displayMedium" to displayMedium,
                "displaySmall" to displaySmall,
                "headlineLarge" to headlineLarge,
                "headlineMedium" to headlineMedium,
                "headlineSmall" to headlineSmall,
                "titleLarge" to titleLarge,
                "titleMedium" to titleMedium,
                "titleSmall" to titleSmall,
                "bodyLarge" to bodyLarge,
                "bodyMedium" to bodyMedium,
                "bodySmall" to bodySmall,
                "labelLarge" to labelLarge,
                "labelMedium" to labelMedium,
                "labelSmall" to labelSmall,
            )
        }

    @Test
    fun theFifteenRolesUseFigtreeAndNeverGoBelowTwelve() {
        assertEquals(15, roles.size)
        for ((name, style) in roles) {
            assertEquals(name, AppFontFamily, style.fontFamily)
            assertTrue("$name mide ${style.fontSize}", style.fontSize.value >= MIN_SIZE)
        }
    }

    @Test
    fun everyAmountStyleHasTabularFigures() {
        val amounts = AppTheme.amounts.all
        assertEquals(7, amounts.size)
        for ((name, style) in amounts) {
            assertEquals(name, TABULAR_FIGURES, style.fontFeatureSettings)
            assertEquals(name, AppFontFamily, style.fontFamily)
            assertTrue("$name mide ${style.fontSize}", style.fontSize.value >= MIN_SIZE)
        }
        // Ningún texto corriente lleva cifras tabulares: en prosa, las cifras proporcionales se leen mejor.
        for ((name, style) in roles) assertEquals(name, null, style.fontFeatureSettings)
    }

    @OptIn(ExperimentalTextApi::class)
    @Test
    fun eachFigtreeWeightAsksTheVariableFileForThatWeight() {
        val fonts = (AppFontFamily as FontListFontFamily).fonts.map { it as ResourceFont }
        assertEquals(listOf(400, 500, 600), fonts.map { it.weight.weight })
        for (font in fonts) {
            val setting = font.variationSettings.settings.single()
            assertEquals("wght", setting.axisName)
            assertEquals(font.weight.weight.toFloat(), setting.toVariationValue(null))
        }
    }

    @Test
    fun currencySymbolsComeInTheTwoAmountWeights() {
        val weights = (CurrencySymbolFamily as FontListFontFamily).fonts.map { it.weight.weight }
        assertEquals(listOf(500, 600), weights)
        val amountWeights = AppTheme.amounts.all.map { (_, style) ->
            style.fontWeight?.weight
        }.toSet()
        assertEquals(setOf(500, 600), amountWeights)
    }

    private companion object {
        val MIN_SIZE = 12.sp.value
    }
}
