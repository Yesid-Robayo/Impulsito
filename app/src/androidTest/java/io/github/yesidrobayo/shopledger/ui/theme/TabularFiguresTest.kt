package io.github.yesidrobayo.shopledger.ui.theme

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono, con la fuente empaquetada: las cifras de todo estilo de monto miden lo mismo, así
 * que una columna alinea, y sin cifras tabulares no medirían lo mismo, así que la prueba mide algo.
 *
 * Mide con el mismo motor de texto de las pantallas, sin herramientas de interfaz.
 */
@RunWith(AndroidJUnit4::class)
class TabularFiguresTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val measurer =
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = Density(context),
            defaultLayoutDirection = LayoutDirection.Ltr,
            cacheSize = 0,
        )

    @Test
    fun everyAmountStyleAlignsItsDigits() {
        for ((name, style) in AppTheme.amounts.all) {
            assertEquals(name, width(NARROW, style), width(WIDE, style))
        }
    }

    @Test
    fun withoutTabularFiguresTheSameDigitsWouldNotAlign() {
        val proportional = AppTheme.amounts.row.copy(fontFeatureSettings = null)

        assertNotEquals(width(NARROW, proportional), width(WIDE, proportional))
    }

    @Test
    fun theAmountsAreDrawnWithFigtreeAndNotTheSystemFont() {
        val row = AppTheme.amounts.row
        val system = row.copy(fontFamily = FontFamily.Default)

        assertNotEquals(width(WIDE, row), width(WIDE, system))
    }

    @Test
    fun theVariableFileHonorsTheRequestedWeight() {
        // Sin el ajuste de peso, el archivo variable dibujaría todo en su peso por defecto, el 300.
        val regular = AppTypography.bodyLarge
        val semiBold = regular.copy(fontWeight = AppTypography.titleSmall.fontWeight)

        assertNotEquals(width(WORD, regular), width(WORD, semiBold))
    }

    private fun width(text: String, style: TextStyle): Int =
        measurer.measure(text, style).size.width

    private companion object {
        // El separador de miles no es tabular: los dos textos usan los mismos, en el mismo lugar.
        const val NARROW = "111,111.11"
        const val WIDE = "888,888.88"
        const val WORD = "Numbers"
    }
}
