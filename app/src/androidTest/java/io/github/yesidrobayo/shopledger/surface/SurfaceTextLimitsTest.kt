package io.github.yesidrobayo.shopledger.surface

import androidx.annotation.StringRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.localization.resourcesIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los textos que muestra el sistema fuera de la app caben en su lugar en los tres idiomas: el
 * mosaico, hasta 18 caracteres, y cada atajo, 10 en su etiqueta corta y 25 en la larga. Los
 * textos esperados son los de la guía de redacción.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceTextLimitsTest {
    private val languages = listOf("es", "en", "pt")

    @Test
    fun theTileLabelFitsInEachLanguage() {
        assertFits(R.string.tile_new_sale, TILE_LABEL_MAX)
    }

    @Test
    fun theTileLabelIsTheOneOfTheWritingGuide() {
        assertEquals("Nueva venta", resourcesIn("es").getString(R.string.tile_new_sale))
        assertEquals("New sale", resourcesIn("en").getString(R.string.tile_new_sale))
        assertEquals("Nova venda", resourcesIn("pt").getString(R.string.tile_new_sale))
    }

    @Test
    fun everyShortcutLabelFitsInEachLanguage() {
        val shortLabels =
            listOf(
                R.string.shortcut_sale_short,
                R.string.shortcut_expense_short,
                R.string.shortcut_purchase_short,
            )
        val longLabels =
            listOf(
                R.string.shortcut_sale_long,
                R.string.shortcut_expense_long,
                R.string.shortcut_purchase_long,
            )
        shortLabels.forEach { assertFits(it, SHORTCUT_SHORT_MAX) }
        longLabels.forEach { assertFits(it, SHORTCUT_LONG_MAX) }
    }

    @Test
    fun theShortcutLabelsAreTheOnesOfTheWritingGuide() {
        val spanish = resourcesIn("es")
        assertEquals("Vender", spanish.getString(R.string.shortcut_sale_short))
        assertEquals("Nuevo gasto", spanish.getString(R.string.shortcut_expense_long))
        assertEquals("Purchase", resourcesIn("en").getString(R.string.shortcut_purchase_short))
        assertEquals("Nova despesa", resourcesIn("pt").getString(R.string.shortcut_expense_long))
    }

    private fun assertFits(@StringRes text: Int, max: Int) {
        for (language in languages) {
            val value = resourcesIn(language).getString(text)
            assertTrue("$language: $value", value.isNotBlank() && value.length <= max)
        }
    }

    private companion object {
        const val TILE_LABEL_MAX = 18
        const val SHORTCUT_SHORT_MAX = 10
        const val SHORTCUT_LONG_MAX = 25
    }
}
