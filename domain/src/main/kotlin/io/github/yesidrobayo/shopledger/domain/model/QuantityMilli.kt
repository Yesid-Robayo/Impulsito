package io.github.yesidrobayo.shopledger.domain.model

/**
 * Una cantidad en milésimas de una unidad: tres unidades son 3000, y 0,250 kg son 250.
 *
 * El número depende de la unidad. Los mismos 250 g son 250 en un producto que se lleva en
 * kilogramos, y 250000 en uno que se lleva en gramos: el libro de stock siempre va en la unidad
 * base del producto, y una línea en otra unidad se convierte antes de tocarlo.
 *
 * Es un tipo distinto de [Money] para que el compilador impida sumar una cantidad con un monto.
 * Suma, resta y cambio de signo lanzan [ArithmeticException] en vez de desbordarse.
 */
@JvmInline
value class QuantityMilli(val milli: Long) : Comparable<QuantityMilli> {
    /** Si es un número entero de unidades, sin fracción. */
    val isWholeUnits: Boolean get() = milli % Scale.MILLI == 0L

    operator fun plus(other: QuantityMilli): QuantityMilli =
        QuantityMilli(Math.addExact(milli, other.milli))

    operator fun minus(other: QuantityMilli): QuantityMilli =
        QuantityMilli(Math.subtractExact(milli, other.milli))

    operator fun unaryMinus(): QuantityMilli = QuantityMilli(Math.negateExact(milli))

    override fun compareTo(other: QuantityMilli): Int = milli.compareTo(other.milli)

    companion object {
        val ZERO: QuantityMilli = QuantityMilli(0L)

        /** [units] unidades enteras. Lanza [ArithmeticException] si no caben en `Long`. */
        fun ofUnits(units: Long): QuantityMilli =
            QuantityMilli(Math.multiplyExact(units, Scale.MILLI))
    }
}
