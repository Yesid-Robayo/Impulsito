package io.github.yesidrobayo.shopledger.format

import android.icu.text.DecimalFormatSymbols
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat

/**
 * Arma el formato de los números de un CSV con el separador decimal del formato regional del
 * negocio, que solo ICU conoce. Las cifras las escribe [CsvFormat], en `:domain`.
 */
object CsvFormatFactory {
    /**
     * El formato de los CSV del negocio: columnas separadas por [separator], el separador decimal de
     * [formatLocale] y [currencyDecimals] decimales en los montos.
     */
    fun create(
        formatLocale: FormatLocale,
        separator: CsvSeparator,
        currencyDecimals: Int,
    ): CsvFormat = CsvFormat(separator, decimalSeparator(formatLocale), currencyDecimals)

    /**
     * El separador decimal de [formatLocale]: el mismo que usan las cantidades en pantalla.
     *
     * Se lee el de cualquier número y no el de los montos, porque una hoja de cálculo usa uno solo
     * para todas las columnas, y algunas regiones escriben el dinero con otro: con su moneda, una
     * región puede poner el signo de esa moneda donde va la coma decimal. Con cifras latinas, ICU da
     * una coma o un punto en todas las regiones que conoce. Si una versión futura diera otro
     * carácter, sale el punto, que es el separador de las hojas de cálculo sin coma decimal.
     */
    fun decimalSeparator(formatLocale: FormatLocale): DecimalSeparator {
        // El separador como texto, que admitiría más de un carácter, recién existe en API 28.
        val symbol = DecimalFormatSymbols.getInstance(formatLocale.numberLocale).decimalSeparator
        return if (symbol == DecimalSeparator.COMMA.char) {
            DecimalSeparator.COMMA
        } else {
            DecimalSeparator.POINT
        }
    }
}
