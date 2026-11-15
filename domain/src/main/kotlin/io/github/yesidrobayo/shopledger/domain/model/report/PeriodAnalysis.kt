package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

// El análisis de un período en `Números`: qué productos dejan la mayor parte de la ganancia,
// cuáles tienen stock y no se movieron, y qué clientes compraron más. Todo sale de consultas que
// suman en la base, sin tablas nuevas.

/**
 * El análisis de un período.
 *
 * @param concentration los productos que dejan el 80 % de la ganancia en ventas, o por qué no hay.
 * @param idle los productos con stock que no se vendieron en el período.
 * @param customers los clientes que más compraron en el período, de mayor a menor.
 */
data class PeriodAnalysis(
    val concentration: ProfitConcentration,
    val idle: IdleProducts,
    val customers: List<CustomerSales>,
) {
    /** Sin nada que mostrar en ninguna de sus partes: la herramienta no aparece. */
    val isEmpty: Boolean
        get() =
            concentration == ProfitConcentration.NoSales &&
                idle.total == 0L &&
                customers.isEmpty()
}

/** Qué productos dejan el 80 % de la ganancia en ventas de un período. */
sealed interface ProfitConcentration {
    /** No se vendió nada en el período: no hay a quién ordenar. */
    data object NoSales : ProfitConcentration

    /**
     * Se vendió, pero la ganancia en ventas del período es cero o negativa: no hay un 80 % que
     * repartir. La pantalla muestra `—` y lo explica, nunca un cero ni un error.
     */
    data object NoProfit : ProfitConcentration

    /** Una cifra no cabe en `Long`. La pantalla muestra `—`. */
    data object OutOfRange : ProfitConcentration

    /**
     * Los productos que, de mayor a menor ganancia, llegan juntos al 80 % de la ganancia del
     * período.
     *
     * @param leaders los productos, de mayor a menor ganancia, con las variantes en su principal.
     * @param soldProducts cuántos productos se vendieron en el período, contando así.
     * @param reachedShare la parte de la ganancia del período que suman [leaders].
     */
    data class Leaders(
        val leaders: List<ProfitLeader>,
        val soldProducts: Int,
        val reachedShare: BasisPoints,
    ) : ProfitConcentration
}

/**
 * Un producto de los que dejan el 80 %.
 *
 * @param productId el producto, o el principal si vendió por variantes.
 * @param name su nombre de hoy.
 * @param profit lo que dejó en el período: lo vendido sin impuesto menos su costo, ya restado lo
 *   devuelto.
 * @param share la parte de la ganancia del período que dejó.
 */
data class ProfitLeader(
    val productId: Long,
    val name: String,
    val profit: Money,
    val share: BasisPoints,
)

/**
 * Los productos con stock y sin ventas en el período, los primeros por valor del stock, y cuántos
 * son en total.
 */
data class IdleProducts(val products: List<IdleProduct>, val total: Long) {
    companion object {
        /** Ningún producto quieto. */
        val NONE: IdleProducts = IdleProducts(emptyList(), 0L)
    }
}

/**
 * Un producto con stock que no se vendió en el período.
 *
 * @param onHand lo que hay hoy, en milésimas de su unidad base.
 * @param stockValue lo que vale ese stock al costo promedio.
 */
data class IdleProduct(
    val productId: Long,
    val name: String,
    val baseUnitId: Long,
    val onHand: QuantityMilli,
    val stockValue: Money,
)

/**
 * Lo que compró un cliente en el período.
 *
 * @param name su nombre, o `null` si se anonimizó: la pantalla lo nombra con el texto de un
 *   cliente anónimo.
 * @param salesCount cuántas ventas activas suyas hubo.
 * @param netSales la suma de esas ventas sin impuesto.
 */
data class CustomerSales(
    val customerId: Long,
    val name: String?,
    val salesCount: Long,
    val netSales: Money,
)
