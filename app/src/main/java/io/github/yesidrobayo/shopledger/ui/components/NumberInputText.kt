package io.github.yesidrobayo.shopledger.ui.components

import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols

/**
 * [value], un entero con [decimals] cifras decimales implícitas, como lo escribiría la persona con
 * [symbols]: sin separador de miles y sin ceros de más al final. `600050` con dos decimales es
 * `6000,5`, y `1000` milésimas con tres son `1`.
 *
 * Sirve para volver a llenar un campo con lo que ya estaba guardado, como al corregir un documento:
 * el campo lo lee de nuevo con el mismo formato y da el mismo número.
 */
internal fun fixedPointInputText(value: Long, decimals: Int, symbols: NumberSymbols): String {
    require(value >= 0) { "Solo cantidades y montos de cero o más" }
    if (decimals == 0) return value.toString()
    var scale = 1L
    repeat(decimals) { scale *= DECIMAL_BASE }
    val whole = value / scale
    val fraction = value % scale
    if (fraction == 0L) return whole.toString()
    val digits = fraction.toString().padStart(decimals, '0').trimEnd('0')
    return "$whole${symbols.decimalSeparator}$digits"
}

private const val DECIMAL_BASE = 10L
