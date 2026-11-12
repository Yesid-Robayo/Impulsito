package io.github.yesidrobayo.shopledger.ui.theme

import android.content.res.Resources
import androidx.compose.ui.graphics.toArgb
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La pantalla de inicio de cada paleta: su fondo es el héroe de la paleta en claro y en oscuro, cada
 * paleta tiene su tema con el cohete desde API 31, el manifiesto arranca con la predeterminada, y la
 * predeterminada se pide con `ID_NULL`.
 *
 * Los colores viven en recursos, porque Android los lee antes de que exista Compose. Si una paleta
 * cambia su héroe y el recurso no, el arranque saldría de otro color: esta prueba lo impide. Lee
 * los archivos del módulo, sin dispositivo.
 */
class SplashThemesTest {
    @Test
    fun eachSplashBackgroundIsThePaletteHeroInLightMode() {
        val colors = colors("values")
        for (palette in ColorPalette.entries) {
            assertEquals(
                palette.name,
                hex(paletteOf(palette).lightColors.heroContainer.toArgb()),
                colors[resourceKey(palette)],
            )
        }
    }

    @Test
    fun eachSplashBackgroundIsThePaletteHeroInDarkMode() {
        val colors = colors("values-night")
        for (palette in ColorPalette.entries) {
            assertEquals(
                palette.name,
                hex(paletteOf(palette).darkColors.heroContainer.toArgb()),
                colors[resourceKey(palette)],
            )
        }
    }

    @Test
    fun everyPaletteHasItsStartingThemeWithTheRocketFromApi31() {
        val v31 = File("src/main/res/values-v31/themes.xml").readText()
        val base = File("src/main/res/values/themes.xml").readText()
        for (palette in ColorPalette.entries) {
            val name = "Theme.App.Starting.${themeSuffix(palette)}"
            assertTrue(name, base.contains("<style name=\"$name\""))
            val block = v31.substringAfter("<style name=\"$name\"").substringBefore("</style>")
            assertTrue(name, block.contains("@color/splash_${resourceKey(palette)}"))
            assertTrue(name, block.contains("@drawable/ic_launcher_foreground"))
        }
    }

    @Test
    fun theManifestStartsWithTheDefaultPalette() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("@style/Theme.App.Starting.IndigoMustard"))
    }

    @Test
    fun theDefaultPaletteIsRequestedWithNull() {
        assertEquals(Resources.ID_NULL, SplashThemes.requestFor(DefaultColorPalette))
        for (palette in ColorPalette.entries - DefaultColorPalette) {
            assertNotEquals(palette.name, Resources.ID_NULL, SplashThemes.requestFor(palette))
        }
    }

    private fun colors(folder: String): Map<String, String> {
        val xml = File("src/main/res/$folder/splash_colors.xml").readText()
        return SPLASH.findAll(xml).associate { it.groupValues[1] to it.groupValues[2].uppercase() }
    }

    private fun resourceKey(palette: ColorPalette) = palette.name.lowercase()

    private fun themeSuffix(palette: ColorPalette) =
        palette.name.lowercase().split('_').joinToString("") { part ->
            part.replaceFirstChar { it.uppercase() }
        }

    private fun hex(argb: Int) = "#%08X".format(argb)

    private companion object {
        val SPLASH = Regex("""<color name="splash_([a-z_]+)">(#[0-9A-Fa-f]{8})</color>""")
    }
}
