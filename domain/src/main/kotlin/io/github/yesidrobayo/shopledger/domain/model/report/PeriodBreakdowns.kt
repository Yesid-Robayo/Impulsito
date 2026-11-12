package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind

// Los desgloses de un período, como los leen las estadísticas. Cada suma cuenta solo ventas y
// gastos activos con fecha de negocio en el período.

/**
 * Lo vendido de un producto en un período, con lo devuelto de esas mismas líneas ya restado. Las
 * variantes cuentan en su producto padre.
 */
data class ProductSales(
    /** El producto, o el padre de las variantes. */
    val productId: Long,
    /** Su nombre de hoy. */
    val name: String,
    /** La unidad base en que se cuenta la cantidad. */
    val baseUnitId: Long,
    /** Cuánto se vendió, en milésimas de la unidad base. */
    val quantity: QuantityMilli,
    /** Lo vendido sin impuesto. */
    val netSales: Money,
    /** Lo que costó lo vendido. */
    val cost: Money,
)

/** Lo cobrado con un método en un período, leído de los pagos de cada venta. */
data class PaymentMethodTotal(
    /** El nombre del método tal como quedó en cada pago: renombrarlo no cambia el pasado. */
    val name: String,
    /** El tipo del método, tal como quedó en el pago. */
    val kind: PaymentMethodKind,
    /** En cuántas ventas se usó. Una venta con dos métodos cuenta en los dos. */
    val salesCount: Long,
    /** Lo cobrado: la suma de los pagos, propina y redondeo incluidos. */
    val amount: Money,
    /** Las comisiones de esos pagos. */
    val fees: Money,
)

/**
 * Lo gastado en una categoría. Una categoría inicial no tiene nombre propio: se muestra con el
 * texto traducido de su [systemKey].
 */
data class ExpenseCategoryTotal(
    val categoryId: Long,
    val name: String?,
    val systemKey: String?,
    val amount: Money,
)

/** Cuántas cosas explican o cambian las cifras de un período, para sus avisos. */
data class PeriodNoticeCounts(
    /** Ventas con alguna línea vendida sin costo: la ganancia puede estar inflada. */
    val salesWithoutCost: Long,
    /** Productos con stock negativo hoy. */
    val negativeStock: Long,
    /** Ventas, gastos y ajustes del período anulados después de que terminó. */
    val voidedAfterPeriod: Long,
    /** Ventas y gastos con fecha del período registrados después de que terminó. */
    val lateRecords: Long,
    /** Ventas y gastos anulados con fecha en el período. */
    val voidedCount: Long,
) {
    companion object {
        /** Nada que avisar. */
        val NONE = PeriodNoticeCounts(0, 0, 0, 0, 0)
    }
}
