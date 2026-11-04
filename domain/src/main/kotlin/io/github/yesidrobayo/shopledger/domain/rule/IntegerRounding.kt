package io.github.yesidrobayo.shopledger.domain.rule

/**
 * Divide [n] entre [d] y redondea al entero más cercano. Un empate se aleja de cero.
 *
 * Toda división que produce dinero usa esta función, salvo las que redondean hacia arriba. La
 * división entera de Kotlin trunca hacia cero y perdería hasta una unidad menor en cada línea. Es
 * simétrica, como `HALF_UP`: −6160,5 da −6161 igual que 6160,5 da 6161, así que un descuento o
 * una devolución redondean igual que su opuesto positivo.
 *
 * No calcula `(2n + d) / (2d)`, que desborda con `n` cerca del máximo de `Long`: compara el resto
 * con lo que le falta para llegar al divisor. Para dividir un producto usa [mulDivRound].
 */
fun roundDiv(n: Long, d: Long): Long {
    require(d > 0) { "El divisor debe ser positivo" }
    val quotient = n / d
    val remainder = n % d
    val absRemainder = if (remainder < 0) -remainder else remainder
    // 2 × resto >= divisor, sin multiplicar el resto, que podría desbordar.
    if (absRemainder < d - absRemainder) return quotient
    return if (n < 0) quotient - 1 else quotient + 1
}

/**
 * Divide [n] entre [d] y redondea hacia arriba.
 *
 * Solo el precio sugerido y el redondeo de precios van hacia arriba, porque ahí el objetivo es no
 * quedar por debajo de lo calculado. Suma uno al cociente si sobra resto, en vez de calcular
 * `(n + d − 1) / d`, que desborda con `n` cerca del máximo de `Long`.
 */
fun ceilDiv(n: Long, d: Long): Long {
    require(n >= 0) { "El dividendo no puede ser negativo" }
    require(d > 0) { "El divisor debe ser positivo" }
    val quotient = n / d
    return if (n % d == 0L) quotient else quotient + 1
}

/**
 * El menor múltiplo de [step] que no queda por debajo de [n]. Un valor que ya es múltiplo no
 * cambia.
 *
 * En los ajustes, un paso 0 significa "sin redondeo", pero esa decisión es de quien llama: aquí el
 * paso siempre es positivo. Lanza [ArithmeticException] si el múltiplo no cabe en `Long`, algo que
 * con precios dentro de su tope no pasa.
 */
fun ceilToStep(n: Long, step: Long): Long = Math.multiplyExact(ceilDiv(n, step), step)
