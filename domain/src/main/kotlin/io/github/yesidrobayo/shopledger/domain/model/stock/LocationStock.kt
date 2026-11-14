package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.rule.stock.LocationRules

/**
 * Cuánto hay de un producto en una ubicación: la suma de sus saldos en ella, de todos sus lotes.
 * Puede ser negativo, porque vender nunca se bloquea por falta de stock.
 */
data class LocationStock(val location: Location, val onHand: QuantityMilli)

/**
 * Una ubicación vigente con cuántos productos tienen stock en ella: los vigentes cuya suma de
 * saldos en la ubicación es mayor que cero.
 */
data class LocationSummary(val location: Location, val stockedProducts: Int)

/**
 * Las ubicaciones, para la pantalla que las administra.
 *
 * [showsLocations] es falso mientras haya una sola vigente: entonces la interfaz no menciona
 * ubicaciones en ningún lado, aunque cada fila del libro siga llevando la suya.
 */
data class LocationsOverview(val active: List<LocationSummary>, val archived: List<Location>) {
    val showsLocations: Boolean get() = LocationRules.showsLocations(active.size)
}

/** El stock de un producto repartido por ubicación, o nada si solo hay una. */
sealed interface ProductLocationStock {
    /** Hay una sola ubicación vigente: no hay nada que repartir ni que mostrar. */
    data object Hidden : ProductLocationStock

    /**
     * Una fila por ubicación vigente, también con cero, más las archivadas donde quedó algo
     * distinto de cero, en el orden en que se crearon. Su suma es el saldo del producto.
     */
    data class Shown(val rows: List<LocationStock>) : ProductLocationStock
}
