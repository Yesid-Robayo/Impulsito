package io.github.yesidrobayo.shopledger.domain.model

/**
 * Un monto en la unidad menor de la moneda del negocio.
 *
 * No conoce la moneda ni sus decimales. El mismo entero 12350 son 12350 unidades de una moneda sin
 * decimales, 123,50 de una con dos y 12,350 de una con tres: solo el formato y la exportación usan
 * los decimales, y la aritmética nunca. Por eso aquí no se divide por 100 ni se supone una moneda.
 *
 * Suma, resta y cambio de signo lanzan [ArithmeticException] en vez de desbordarse. Un monto que da
 * la vuelta y sale negativo sería un dato falso guardado como bueno; la excepción, en cambio,
 * revierte la transacción y no deja nada a medias.
 *
 * Multiplicar o dividir un monto pasa por las reglas de `rule`, que redondean y rechazan con un
 * resultado tipado lo que no cabe.
 */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {
    operator fun plus(other: Money): Money = Money(Math.addExact(minor, other.minor))

    operator fun minus(other: Money): Money = Money(Math.subtractExact(minor, other.minor))

    operator fun unaryMinus(): Money = Money(Math.negateExact(minor))

    override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

    companion object {
        val ZERO: Money = Money(0L)
    }
}

/**
 * Suma exacta de los montos.
 *
 * Lanza [ArithmeticException] si desborda: sirve al escribir, donde la transacción debe revertirse.
 * Para mostrar un reporte usa [sumOrNull].
 */
fun Iterable<Money>.sum(): Money = fold(Money.ZERO) { total, amount -> total + amount }

/**
 * Suma de los montos para un reporte, o `null` si desborda.
 *
 * Una suma así solo sale con cifras absurdas, pero la pantalla no debe caerse por ellas: muestra
 * "sin valor" en su lugar.
 */
fun Iterable<Money>.sumOrNull(): Money? = try {
    sum()
} catch (overflow: ArithmeticException) {
    null
}
