package io.github.yesidrobayo.shopledger.ui.theme

import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El fondo de la ventana, que se ve antes del primer cuadro de Compose, es el papel de la paleta
 * predeterminada en claro y en oscuro.
 *
 * Ese color vive en un recurso, porque Android lo lee antes de que exista Compose. Si la paleta
 * cambia su papel y el recurso no, la app destellaría al abrir: esta prueba lo impide. Lee los
 * archivos de recursos del módulo, sin dispositivo.
 */
class WindowPaperTest {
    @Test
    fun theWindowBackgroundIsTheDefaultPaperInLightMode() {
        assertEquals(
            hex(paletteOf(DefaultColorPalette).lightScheme.surface.toArgb()),
            windowPaper("values"),
        )
    }

    @Test
    fun theWindowBackgroundIsTheDefaultPaperInDarkMode() {
        assertEquals(
            hex(paletteOf(DefaultColorPalette).darkScheme.surface.toArgb()),
            windowPaper("values-night"),
        )
    }

    private fun windowPaper(folder: String): String {
        val xml = File("src/main/res/$folder/colors.xml").readText()
        val value =
            requireNotNull(WINDOW_PAPER.find(xml)) { "$folder/colors.xml no tiene window_paper" }
        return value.groupValues[1].uppercase()
    }

    private fun hex(argb: Int) = "#%08X".format(argb)

    private companion object {
        val WINDOW_PAPER = Regex("""<color name="window_paper">(#[0-9A-Fa-f]{8})</color>""")
    }
}
