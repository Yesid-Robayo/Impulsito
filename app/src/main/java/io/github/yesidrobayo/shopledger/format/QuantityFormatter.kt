package io.github.yesidrobayo.shopledger.format

import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import java.math.BigDecimal

/**
 * Formatea cantidades con el formato regional [formatLocale]: `12` en una unidad de conteo, y
 * `0,250` en una que admite fracciones.
 *
 * Una unidad con fracciones muestra siempre tres decimales, también en `1,000`: así una columna de
 * pesos alinea, y se ve que la cantidad admite fracción. Una de conteo muestra un entero. Devuelve
 * solo el número: el símbolo de la unidad sale de los recursos del idioma y se agrega afuera.
 *
 * Es seguro usarlo desde varios hilos: ICU no lo es, y cada llamada toma el formato en exclusiva.
 */
class QuantityFormatter(val formatLocale: FormatLocale) {
    private val wholeFormat: DecimalFormat = decimalFormat(formatLocale, fractionDigits = 0)
    private val fractionFormat: DecimalFormat = decimalFormat(formatLocale, fractionDigits = 3)

    /**
     * Los separadores y el signo con que este formateador escribe una cantidad. El dominio los usa
     * para leer lo que la persona escribe en un campo de cantidad.
     */
    val inputSymbols: NumberSymbols = numberInputSymbols(fractionFormat)

    /**
     * La cantidad, con el signo menos tipográfico si es negativa: `−2`, `−0,250`.
     *
     * Con [allowsFraction] en falso y una cantidad que no es entera, muestra igual sus tres
     * decimales. No debería pasar, porque esas unidades no aceptan fracciones, pero redondear
     * escondería un dato: 12,5 cajas no son 12 ni 13.
     */
    fun format(quantity: QuantityMilli, allowsFraction: Boolean): String {
        val whole = !allowsFraction && quantity.isWholeUnits
        val text =
            if (whole) {
                synchronized(wholeFormat) {
                    wholeFormat.format(BigDecimal.valueOf(quantity.milli / Scale.MILLI))
                }
            } else {
                synchronized(fractionFormat) {
                    fractionFormat.format(BigDecimal.valueOf(quantity.milli, MILLI_DIGITS))
                }
            }
        return IcuTextNormalizer.normalizeNumber(text)
    }

    /**
     * Un conteo entero, como los puntos de un cliente o cuántos clientes hay, agrupado como lo
     * agrupa la región: `1.250`, `1,250` o `1,00,000`.
     *
     * Un `%d` en un recurso lo escribiría con el idioma de la interfaz y sin separador de miles,
     * así que todo conteo que se muestra pasa por aquí y entra al texto como `%s`.
     */
    fun formatCount(count: Long): String {
        val text = synchronized(wholeFormat) { wholeFormat.format(count) }
        return IcuTextNormalizer.normalizeNumber(text)
    }

    private companion object {
        /** Decimales de una cantidad en milésimas. */
        const val MILLI_DIGITS = 3

        fun decimalFormat(formatLocale: FormatLocale, fractionDigits: Int): DecimalFormat =
            fixedDecimalFormat(formatLocale.numberLocale, NumberFormat.NUMBERSTYLE, fractionDigits)
    }
}
