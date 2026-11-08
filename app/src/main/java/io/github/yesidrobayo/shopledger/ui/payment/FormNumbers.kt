package io.github.yesidrobayo.shopledger.ui.payment

import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols

/**
 * Lo que el formulario necesita para leer y escribir sus números con el formato regional del
 * negocio: los símbolos del porcentaje y del monto, y los decimales de la moneda. Lo arma la ruta
 * con los formatos del negocio, así el ViewModel no conoce Android.
 */
data class FormNumbers(
    val percentSymbols: NumberSymbols,
    val moneySymbols: NumberSymbols,
    val currencyDecimals: Int,
)

/**
 * [value], un entero con [decimals] decimales implícitos, como el texto que la persona escribiría:
 * sin separadores de grupos y con el separador decimal y el signo menos de [symbols]. Los ceros del
 * final de los decimales sobran, y sin decimales no hay separador: 275 con dos decimales es `2,75`,
 * 250 es `2,5` y 200 es `2`.
 *
 * Es el texto con que el formulario abre un método guardado, y lo lee de vuelta `NumberInputParser`
 * sin cambiar el valor.
 */
fun plainNumberText(value: Long, decimals: Int, symbols: NumberSymbols): String {
    require(decimals >= 0) { "Los decimales no pueden ser negativos" }
    // El texto sin signo, con todos sus decimales: se arma con las cifras, sin dividir.
    val digits = value.toString().removePrefix("-").padStart(decimals + 1, '0')
    val integer = digits.dropLast(decimals)
    val fraction = digits.takeLast(decimals).trimEnd('0')
    val sign = if (value < 0) symbols.minusSign.toString() else ""
    return if (fraction.isEmpty()) {
        sign + integer
    } else {
        sign + integer + symbols.decimalSeparator + fraction
    }
}
