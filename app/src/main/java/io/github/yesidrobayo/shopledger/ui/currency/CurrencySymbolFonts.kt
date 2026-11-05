package io.github.yesidrobayo.shopledger.ui.currency

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.FontRes
import io.github.yesidrobayo.shopledger.R

/**
 * Qué símbolos dibujan las fuentes de la app y la del sistema, para armar la cadena del símbolo.
 *
 * Las fuentes propias se leen una sola vez por proceso, de su tabla `cmap`: son 70 KB del APK, y
 * cada monto de cada pantalla pregunta por ellas. La del sistema se pregunta a `Paint.hasGlyph` con
 * la fuente por defecto, que incluye todo el respaldo del teléfono.
 */
object CurrencySymbolFonts {
    @Volatile
    private var owned: OwnedCoverage? = null

    /**
     * La fuente del sistema. Un `Paint` nuevo en cada consulta, porque `Paint` no es seguro entre
     * hilos, y la pantalla y un documento pueden preguntar a la vez.
     */
    val systemCoverage: GlyphCoverage =
        GlyphCoverage { codePoint ->
            val paint = Paint()
            paint.typeface = Typeface.DEFAULT
            paint.hasGlyph(String(Character.toChars(codePoint)))
        }

    /**
     * La cadena con las fuentes de la app y, para lo que no tengan, [system]. Las pruebas cambian
     * [system] para ver qué pasa en un teléfono sin el glifo.
     */
    fun chain(resources: Resources, system: GlyphCoverage = systemCoverage): CurrencySymbolChain {
        val fonts = owned(resources)
        return CurrencySymbolChain(
            appFont = GlyphCoverage { it in fonts.app },
            currencyFont = GlyphCoverage { it in fonts.currency },
            systemFont = system,
        )
    }

    private fun owned(resources: Resources): OwnedCoverage = owned ?: synchronized(this) {
        owned ?: OwnedCoverage(
            app = readCodePoints(resources, R.font.figtree_variable),
            // Los dos pesos traen los mismos símbolos: lo comprueban las pruebas.
            currency = readCodePoints(resources, R.font.currency_symbols_600),
        ).also { owned = it }
    }

    // openRawResource lee cualquier recurso que sea un archivo, y los .ttf de res/font lo son. Lint
    // espera un recurso raw por la anotación del parámetro, no porque falle.
    @SuppressLint("ResourceType")
    private fun readCodePoints(resources: Resources, @FontRes fontId: Int): Set<Int> =
        CmapReader.readCodePoints(resources.openRawResource(fontId).use { it.readBytes() })

    private class OwnedCoverage(val app: Set<Int>, val currency: Set<Int>)
}
