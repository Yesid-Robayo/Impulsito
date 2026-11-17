package io.github.yesidrobayo.shopledger.domain.model.report

/**
 * El día mayor y el día menor de la gráfica de un período, entre los días que ya llegaron: un día
 * después de hoy todavía no pasó, y su cero no lo hace el día más flojo.
 */
data class DayExtremes(
    /** El día de mayor valor. Entre iguales, el primero. */
    val highest: DayPoint,
    /** El día de menor valor. Entre iguales, el primero. */
    val lowest: DayPoint,
)
