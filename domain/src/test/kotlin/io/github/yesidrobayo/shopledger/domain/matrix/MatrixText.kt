package io.github.yesidrobayo.shopledger.domain.matrix

import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols

/**
 * Escribe el entero [scaled], con [decimals] decimales, como lo escribiría una persona con los
 * símbolos [symbols] de su región: `123450` con dos decimales y coma decimal es `1.234,50`.
 *
 * Es el oráculo de las pruebas de la matriz en la JVM, donde no hay ICU de Android: arma el texto
 * cifra por cifra desde el entero, sin pasar por un número con decimales binarios. [grouped] decide
 * si lleva los separadores de grupo, que la persona puede omitir, y [minus] es el signo que escribe,
 * el tipográfico de la pantalla o el guion del teclado.
 */
internal fun written(
    scaled: Long,
    decimals: Int,
    symbols: NumberSymbols,
    grouped: Boolean = true,
    minus: Char = NumberSymbols.TYPOGRAPHIC_MINUS,
): String {
    val digits = scaled.toString().removePrefix("-").padStart(decimals + 1, '0')
    val integer = digits.substring(0, digits.length - decimals)
    val fraction = digits.substring(digits.length - decimals)
    val grouping = symbols.grouping
    val integerText =
        if (grouped && grouping != null) {
            groups(integer, grouping.primarySize, grouping.secondarySize)
                .joinToString(grouping.separator.toString())
        } else {
            integer
        }
    val number =
        if (decimals == 0) integerText else integerText + symbols.decimalSeparator + fraction
    return if (scaled < 0L) "$minus$number" else number
}

/** Las cifras de [integer] en grupos, de izquierda a derecha: el de la derecha de [primary]. */
private fun groups(integer: String, primary: Int, secondary: Int): List<String> {
    if (integer.length <= primary) return listOf(integer)
    val result = ArrayDeque<String>()
    var end = integer.length
    result.addFirst(integer.substring(end - primary, end))
    end -= primary
    while (end > 0) {
        val start = maxOf(0, end - secondary)
        result.addFirst(integer.substring(start, end))
        end = start
    }
    return result.toList()
}
