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
 * mosaico, hasta 18 caracteres. Los textos esperados son los de la guía de redacción.
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

    private fun assertFits(@StringRes text: Int, max: Int) {
        for (language in languages) {
            val value = resourcesIn(language).getString(text)
            assertTrue("$language: $value", value.isNotBlank() && value.length <= max)
        }
    }

    private companion object {
        const val TILE_LABEL_MAX = 18
    }
}
