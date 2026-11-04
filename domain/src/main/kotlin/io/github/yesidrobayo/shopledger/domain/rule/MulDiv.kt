package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit

/**
 * Calcula `a × b / d` con el redondeo de [roundDiv], aunque `a × b` no quepa en `Long`.
 *
 * Toda división de un producto pasa por aquí. Una línea de un millón de unidades al precio máximo
 * multiplica 10^9 milésimas por 10^12, que no cabe en `Long` aunque el resultado, 10^18, sí. El
 * producto intermedio se lleva en 128 bits con aritmética entera propia, sin `java.math`, para que
 * el dominio siga siendo Kotlin puro.
 *
 * Devuelve [Checked.Overflow] si el resultado no cabe en `Long`. El rango es simétrico: un
 * resultado negativo también se rechaza si su valor absoluto pasa de `Long.MAX_VALUE`.
 */
fun mulDivRound(a: Long, b: Long, d: Long): Checked<Long> {
    val result = mulDivRoundOrNull(a, b, d) ?: return Checked.Overflow
    return Checked.Value(result)
}

/**
 * Igual que [mulDivRound], y además rechaza con [Checked.AboveLimit] un resultado mayor que el
 * tope [limit]. Así una línea que supera el total máximo de un documento no llega a guardarse.
 */
fun mulDivRound(a: Long, b: Long, d: Long, limit: Limit): Checked<Long> {
    val result = mulDivRoundOrNull(a, b, d) ?: return Checked.Overflow
    return if (limit.allows(result)) Checked.Value(result) else Checked.AboveLimit(limit)
}

/**
 * Calcula `a × b / d` redondeado hacia arriba, aunque `a × b` no quepa en `Long`.
 *
 * Es el [ceilDiv] de un producto, con los mismos 128 bits que [mulDivRound]. Lo usa el precio
 * sugerido, que nunca debe quedar por debajo de lo calculado: con un costo de 0,4 por unidad, el
 * precio sale de la fracción sin redondear y solo el resultado sube al entero siguiente. Igual que
 * en [ceilDiv], ningún número es negativo: un factor negativo es un error de programación.
 *
 * Devuelve [Checked.Overflow] si el resultado no cabe en `Long`.
 */
fun mulDivCeil(a: Long, b: Long, d: Long): Checked<Long> {
    require(a >= 0 && b >= 0) { "Los factores no pueden ser negativos" }
    require(d > 0) { "El divisor debe ser positivo" }
    val division = divideProduct(a.toULong(), b.toULong(), d.toULong()) ?: return Checked.Overflow
    // Se compara antes de sumar: un cociente de 2^64 − 1 con resto daría la vuelta a cero.
    if (division.quotient > MAX_MAGNITUDE) return Checked.Overflow
    val ceiling =
        if (division.remainder == 0uL) division.quotient else division.quotient + 1uL
    if (ceiling > MAX_MAGNITUDE) return Checked.Overflow
    return Checked.Value(ceiling.toLong())
}

private const val LOW_32_BITS: ULong = 0xFFFF_FFFFuL
private val MAX_MAGNITUDE: ULong = Long.MAX_VALUE.toULong()

/** El cálculo de [mulDivRound], o `null` si el resultado no cabe en `Long`. */
private fun mulDivRoundOrNull(a: Long, b: Long, d: Long): Long? {
    require(d > 0) { "El divisor debe ser positivo" }
    val divisor = d.toULong()
    val negative = (a < 0) != (b < 0)
    val division = divideProduct(magnitude(a), magnitude(b), divisor) ?: return null
    val quotient = division.quotient
    val remainder = division.remainder

    if (quotient > MAX_MAGNITUDE) return null
    // 2 × resto >= divisor, sin multiplicar el resto. Con empate se aleja de cero.
    val rounded = if (remainder >= divisor - remainder) quotient + 1uL else quotient
    if (rounded > MAX_MAGNITUDE) return null
    val magnitude = rounded.toLong()
    return if (negative) -magnitude else magnitude
}

/** Cociente y resto enteros de una división sin signo. */
private class UnsignedDivision(val quotient: ULong, val remainder: ULong)

/**
 * Divide `x × y` entre [divisor], con el producto en 128 bits. Devuelve `null` si el cociente no
 * cabe en 64 bits. El divisor es positivo y menor que 2^63: viene de un `Long` positivo.
 */
private fun divideProduct(x: ULong, y: ULong, divisor: ULong): UnsignedDivision? {
    // Producto de 128 bits por mitades de 32: cada producto parcial cabe en 64 bits sin signo.
    val xLow = x and LOW_32_BITS
    val xHigh = x shr 32
    val yLow = y and LOW_32_BITS
    val yHigh = y shr 32
    val lowLow = xLow * yLow
    val lowHigh = xLow * yHigh
    val highLow = xHigh * yLow
    val middle = (lowLow shr 32) + (lowHigh and LOW_32_BITS) + (highLow and LOW_32_BITS)
    val productLow = (middle shl 32) or (lowLow and LOW_32_BITS)
    val productHigh = xHigh * yHigh + (lowHigh shr 32) + (highLow shr 32) + (middle shr 32)

    // Si la mitad alta llega al divisor, el cociente pasa de 64 bits.
    if (productHigh >= divisor) return null

    // División larga, bit a bit. El resto siempre es menor que el divisor, que es menor que 2^63,
    // así que desplazarlo una posición no pierde bits.
    var remainder = productHigh
    var quotient = 0uL
    for (bit in 63 downTo 0) {
        remainder = (remainder shl 1) or ((productLow shr bit) and 1uL)
        quotient = quotient shl 1
        if (remainder >= divisor) {
            remainder -= divisor
            quotient = quotient or 1uL
        }
    }
    return UnsignedDivision(quotient, remainder)
}

/**
 * Valor absoluto como entero sin signo. Cubre también `Long.MIN_VALUE`, cuyo valor absoluto, 2^63,
 * no cabe en `Long` pero sí en `ULong`: la resta desde cero es aritmética módulo 2^64.
 */
private fun magnitude(value: Long): ULong =
    if (value < 0) 0uL - value.toULong() else value.toULong()
