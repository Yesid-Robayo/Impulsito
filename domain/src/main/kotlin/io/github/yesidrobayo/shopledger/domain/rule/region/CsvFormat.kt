package io.github.yesidrobayo.shopledger.domain.rule.region

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator

/**
 * Cómo se escriben los números en los CSV del negocio, para que una hoja de cálculo los lea como
 * números y los pueda sumar.
 *
 * Un monto sale con exactamente [currencyDecimals] decimales y una cantidad con tres, ambos con el
 * [decimalSeparator] del formato regional, sin separador de miles, sin símbolo de moneda y con el
 * guion ASCII como signo menos. Es lo contrario de la pantalla: con los miles, el símbolo o el signo
 * menos tipográfico, una hoja de cálculo podría leer un texto en vez de un número.
 *
 * Las cifras salen del entero guardado, sin pasar por un `Double` ni redondear: lo que se exporta es
 * exactamente lo que guarda la base.
 *
 * Si la persona elige la coma como separador de columnas en una región de coma decimal, cada número
 * con decimales va entre comillas dobles: si no, la coma decimal partiría la columna en dos. Una celda
 * numérica nunca se trata como texto: no lleva el apóstrofo que neutraliza fórmulas en las celdas de
 * texto, porque un `-5000` en una columna de monto tiene que seguir siendo un número.
 */
class CsvFormat(
    /** El separador de columnas que eligió la persona en los ajustes. */
    val separator: CsvSeparator,
    /** El separador decimal del formato regional del negocio. */
    val decimalSeparator: DecimalSeparator,
    /** Los decimales de la moneda del negocio, de los ajustes. */
    val currencyDecimals: Int,
) {
    init {
        require(currencyDecimals in MIN_CURRENCY_DECIMALS..MAX_CURRENCY_DECIMALS) {
            "Los decimales de la moneda van de $MIN_CURRENCY_DECIMALS a $MAX_CURRENCY_DECIMALS"
        }
    }

    /** La celda de un monto: `1234` en unidad menor da `12,34` con dos decimales y coma decimal. */
    fun moneyCell(amount: Money): String = cell(amount.minor, currencyDecimals)

    /**
     * La celda de una cantidad, siempre con tres decimales, también en unidades de conteo: `2,000`.
     * Así la columna no cambia de forma entre productos y no se confunde un entero con milésimas.
     */
    fun quantityCell(quantity: QuantityMilli): String = cell(quantity.milli, QUANTITY_DECIMALS)

    /**
     * La celda de un porcentaje, con dos decimales: 1250 puntos básicos dan `12,50`. Es el número
     * del porcentaje, sin el signo `%`, para que la hoja de cálculo lo lea como número.
     */
    fun percentCell(rate: BasisPoints): String = cell(rate.bp, PERCENT_DECIMALS)

    /**
     * La celda de un número guardado con sus propios [decimals], como el saldo de una cuenta en
     * otra moneda o una cotización en millonésimas: `4000500000` con seis decimales da
     * `4000,500000`.
     */
    fun scaledCell(value: Long, decimals: Int): String {
        require(decimals >= 0) { "Los decimales no pueden ser negativos" }
        return cell(value, decimals)
    }

    private fun cell(scaled: Long, decimals: Int): String {
        val number = decimal(scaled, decimals)
        return if (separator.char in number) "$QUOTE$number$QUOTE" else number
    }

    /**
     * El entero [scaled] con la coma o el punto [decimals] cifras antes del final. Se trabaja con el
     * texto del entero y no con su valor absoluto, que no existe para el mínimo de `Long`.
     */
    private fun decimal(scaled: Long, decimals: Int): String {
        val digits = scaled.toString()
        if (decimals == 0) return digits
        val negative = scaled < 0L
        val magnitude = (if (negative) digits.substring(1) else digits).padStart(decimals + 1, ZERO)
        val point = magnitude.length - decimals
        val text =
            magnitude.substring(0, point) + decimalSeparator.char + magnitude.substring(point)
        return if (negative) "$MINUS$text" else text
    }

    companion object {
        /** Los decimales que admiten los ajustes del negocio, los mismos que los de la pantalla. */
        const val MIN_CURRENCY_DECIMALS: Int = 0
        const val MAX_CURRENCY_DECIMALS: Int = 4

        /** Decimales de una cantidad en milésimas. */
        const val QUANTITY_DECIMALS: Int = 3

        /** Decimales de un porcentaje en puntos básicos. */
        const val PERCENT_DECIMALS: Int = 2

        private const val QUOTE = '"'
        private const val ZERO = '0'

        /** El guion ASCII: el signo menos de un número en un CSV, no el tipográfico de la pantalla. */
        private const val MINUS = '-'
    }
}
