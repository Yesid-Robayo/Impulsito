package io.github.yesidrobayo.shopledger.format

import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * El tamaño de un archivo en megabytes, con un decimal: `2,4 MB`. Los números siguen el formato
 * regional y el nombre de la unidad, el idioma de la interfaz, como el resto de las cifras.
 *
 * Un megabyte son 1.000.000 de bytes, como los cuenta el sistema en sus pantallas. La cuenta va en
 * decimal exacto: es una cifra que se muestra, nunca se suma a otra.
 */
class DataSizeFormatter(formatLocale: FormatLocale, uiLocale: Locale) {
    private val format: MeasureFormat =
        MeasureFormat.getInstance(
            formatLocale.dateLocale(uiLocale),
            MeasureFormat.FormatWidth.SHORT,
            NumberFormat.getInstance(formatLocale.numberLocale).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            },
        )

    /** [bytes] en megabytes, redondeado a un decimal. */
    fun megabytes(bytes: Long): String {
        val value =
            BigDecimal.valueOf(
                bytes,
            ).divide(BigDecimal.valueOf(BYTES_PER_MEGABYTE), 1, RoundingMode.HALF_UP)
        val text = synchronized(format) { format.format(Measure(value, MeasureUnit.MEGABYTE)) }
        return IcuTextNormalizer.normalizeText(text).replace(' ', IcuTextNormalizer.NO_BREAK_SPACE)
    }

    /**
     * [bytes] en megabytes enteros, redondeados hacia arriba y sin la unidad: para un texto que ya
     * la dice, como cuánto espacio falta liberar. Nunca dice menos de lo que falta.
     */
    fun wholeMegabytes(bytes: Long): String {
        val value =
            BigDecimal.valueOf(
                bytes,
            ).divide(BigDecimal.valueOf(BYTES_PER_MEGABYTE), 0, RoundingMode.CEILING)
        return synchronized(whole) { whole.format(value) }
    }

    private val whole: NumberFormat = NumberFormat.getIntegerInstance(formatLocale.numberLocale)

    private companion object {
        const val BYTES_PER_MEGABYTE = 1_000_000L
    }
}
