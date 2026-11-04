package io.github.yesidrobayo.shopledger.domain.model

/**
 * Un porcentaje en puntos básicos: 100 son 1 %, y 12,5 % son 1250.
 *
 * Es un entero para que un porcentaje no meta decimales binarios en una cuenta de dinero. La
 * persona escribe hasta dos decimales de porcentaje, que es exactamente lo que cabe en un punto
 * básico. Un margen puede ser negativo, o muy grande con un precio pequeño, así que este tipo no
 * se acota.
 */
@JvmInline
value class BasisPoints(val bp: Long) : Comparable<BasisPoints> {
    override fun compareTo(other: BasisPoints): Int = bp.compareTo(other.bp)

    companion object {
        val ZERO: BasisPoints = BasisPoints(0L)

        /** 100 %. */
        val ONE_HUNDRED_PERCENT: BasisPoints = BasisPoints(Scale.BASIS_POINTS)

        /**
         * `MIN_BULK_PERCENT_BP`: la rebaja máxima de una actualización masiva de precios, −99,99 %.
         * Con −100 % todos los precios quedarían en cero.
         */
        val MIN_BULK_PERCENT: BasisPoints = BasisPoints(-9_999L)

        /**
         * `MAX_BULK_PERCENT_BP`: el alza máxima de una actualización masiva, +999,99 %. Con el
         * precio máximo, `precio × (10000 + porcentaje)` todavía cabe en `Long`.
         */
        val MAX_BULK_PERCENT: BasisPoints = BasisPoints(99_999L)

        /**
         * `MAX_TARGET_MARGIN_BP`: el margen objetivo máximo, 99 %. El precio sugerido divide por
         * `10000 − margen`, que con 100 % sería cero.
         */
        val MAX_TARGET_MARGIN: BasisPoints = BasisPoints(9_900L)

        /** `DEFAULT_TARGET_MARGIN_BP`: el margen objetivo con el que empieza el negocio, 30 %. */
        val DEFAULT_TARGET_MARGIN: BasisPoints = BasisPoints(3_000L)
    }
}
