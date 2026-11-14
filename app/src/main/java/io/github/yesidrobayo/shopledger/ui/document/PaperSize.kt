package io.github.yesidrobayo.shopledger.ui.document

import android.icu.util.LocaleData
import android.os.Build
import io.github.yesidrobayo.shopledger.format.FormatLocale
import kotlin.math.roundToInt

/**
 * El tamaño de una hoja de PDF, en puntos de 1/72 de pulgada.
 *
 * Sale de lo que ICU sabe del formato regional, nunca de una lista de países: con una región que
 * usa carta da carta, y con las demás A4. Android 8 no expone esos datos de ICU: ahí todo sale en
 * A4, y el diálogo de impresión lo escala si se elige otra hoja.
 */
data class PaperSize(val width: Int, val height: Int) {
    companion object {
        /** A4: 210 por 297 mm. */
        val A4 = PaperSize(595, 842)

        /** La hoja de [formatLocale] según ICU. */
        fun of(formatLocale: FormatLocale): PaperSize {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return A4
            val size = LocaleData.getPaperSize(formatLocale.numberLocale)
            if (size.width <= 0 || size.height <= 0) return A4
            return PaperSize(points(size.width), points(size.height))
        }

        private fun points(millimeters: Int): Int =
            (millimeters * POINTS_PER_INCH / MILLIMETERS_PER_INCH).roundToInt()

        private const val POINTS_PER_INCH = 72.0
        private const val MILLIMETERS_PER_INCH = 25.4
    }
}
