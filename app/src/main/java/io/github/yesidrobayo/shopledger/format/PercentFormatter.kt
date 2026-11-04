package io.github.yesidrobayo.shopledger.format

import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv
import java.math.BigDecimal

/**
 * Formatea porcentajes guardados en puntos básicos con el formato regional [formatLocale], con un
 * decimal: `20,0 %` o `20.0%`, según la región.
 *
 * Un decimal basta para leer un margen. Reducirlo es solo presentación: el valor se redondea al
 * décimo de punto más cercano, con el empate alejándose de cero, y todo cálculo sigue con los
 * puntos básicos completos.
 *
 * Es seguro usarlo desde varios hilos: ICU no lo es, y cada llamada toma el formato en exclusiva.
 */
class PercentFormatter(val formatLocale: FormatLocale) {
    private val percentFormat: DecimalFormat =
        fixedDecimalFormat(formatLocale.numberLocale, NumberFormat.PERCENTSTYLE, DISPLAY_DIGITS)
    private val pointsFormat: DecimalFormat =
        fixedDecimalFormat(formatLocale.numberLocale, NumberFormat.NUMBERSTYLE, DISPLAY_DIGITS)

    /** Un porcentaje, como un margen: `20,0 %`, `−20,0 %`. */
    fun format(rate: BasisPoints): String = percent(tenths(rate))

    /**
     * Una variación, siempre con signo: `+8,0 %`, `−3,2 %`. Sin cambio no lleva signo: `0,0 %`.
     *
     * El signo dice la dirección también a quien no distingue colores.
     */
    fun formatChange(change: BasisPoints): String = signed(tenths(change), ::percent)

    /**
     * La variación de un porcentaje en puntos, siempre con signo y sin el signo de porcentaje:
     * `+1,5`, `−0,8`. La palabra que nombra los puntos sale de los recursos del idioma.
     *
     * Existe aparte porque un porcentaje de un porcentaje confunde: si el margen pasa de 20 % a
     * 21,5 %, subió 1,5 puntos, no 7,5 %.
     */
    fun formatPointsChange(change: BasisPoints): String = signed(tenths(change), ::points)

    private fun percent(tenths: Long): String {
        // ICU multiplica por cien, así que recibe la fracción: 200 décimos son 0,200, y 20,0 %.
        val fraction = BigDecimal.valueOf(tenths, 3)
        val text = synchronized(percentFormat) { percentFormat.format(fraction) }
        return IcuTextNormalizer.normalizeNumber(text)
    }

    private fun points(tenths: Long): String {
        val text = synchronized(pointsFormat) { pointsFormat.format(BigDecimal.valueOf(tenths, 1)) }
        return IcuTextNormalizer.normalizeNumber(text)
    }

    private companion object {
        /** Decimales que se muestran de un porcentaje. */
        const val DISPLAY_DIGITS = 1

        /** Puntos básicos en un décimo de punto porcentual. */
        const val BASIS_POINTS_PER_TENTH = 10L

        const val PLUS_SIGN = '+'

        fun tenths(rate: BasisPoints): Long = roundDiv(rate.bp, BASIS_POINTS_PER_TENTH)

        /**
         * Formatea con signo explícito. Un positivo se formatea como su negativo, para que ICU
         * ponga el signo donde lo pone la región, y después el menos se cambia por el más.
         */
        fun signed(tenths: Long, format: (Long) -> String): String = when {
            tenths > 0L -> format(-tenths).replace(IcuTextNormalizer.MINUS_SIGN, PLUS_SIGN)
            else -> format(tenths)
        }
    }
}
