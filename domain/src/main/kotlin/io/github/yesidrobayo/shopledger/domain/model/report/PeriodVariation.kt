package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints

/**
 * Cuánto cambió un período frente al comparable. Cada variación es `null` si no se puede medir:
 * con el anterior en cero, una subida no tiene porcentaje, y la pantalla muestra «—».
 */
data class PeriodVariation(
    /** Variación de lo vendido sin impuesto, en puntos básicos sobre el anterior. */
    val netSales: BasisPoints?,
    /** Variación de la ganancia en ventas, en puntos básicos sobre la anterior. */
    val grossProfit: BasisPoints?,
    /** Variación de la ganancia final, en puntos básicos sobre la anterior. */
    val netProfit: BasisPoints?,
    /** Cambio del margen en puntos porcentuales, expresados en puntos básicos: 50 son 0,5 puntos. */
    val grossMargin: BasisPoints?,
)
