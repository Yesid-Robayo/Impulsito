package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationStock
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationSummary
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationsOverview
import io.github.yesidrobayo.shopledger.domain.model.stock.ProductLocationStock
import io.github.yesidrobayo.shopledger.domain.port.LocationRepository
import io.github.yesidrobayo.shopledger.domain.port.LocationStockSource
import io.github.yesidrobayo.shopledger.domain.rule.stock.LocationRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Las ubicaciones vigentes, con cuántos productos tienen stock en cada una, y las archivadas.
 * Vuelve a emitir con cada cambio confirmado de las ubicaciones o del libro.
 */
class ObserveLocationsUseCase(
    private val locations: LocationRepository,
    private val stock: LocationStockSource,
) {
    operator fun invoke(): Flow<LocationsOverview> = combine(
        locations.observe(ArchiveFilter.ACTIVE),
        locations.observe(ArchiveFilter.ARCHIVED),
        stock.observeStockedProductCounts(),
    ) { active, archived, counts ->
        LocationsOverview(
            active = active.map { LocationSummary(it, counts[it.id] ?: 0) },
            archived = archived,
        )
    }.distinctUntilChanged()
}

/**
 * El stock de un producto en cada ubicación, o [ProductLocationStock.Hidden] mientras haya una
 * sola vigente: con una sola, nada habla de ubicaciones.
 *
 * Muestra todas las vigentes, también con cero, para que se vea dónde no hay. Una archivada
 * aparece solo si le quedó algo distinto de cero, como lo que devuelve la anulación de una venta
 * hecha allí: así la suma de las filas es siempre el saldo del producto.
 */
class ObserveProductLocationStockUseCase(
    private val locations: LocationRepository,
    private val stock: LocationStockSource,
) {
    operator fun invoke(productId: Long): Flow<ProductLocationStock> = combine(
        locations.observe(ArchiveFilter.ACTIVE),
        locations.observe(ArchiveFilter.ARCHIVED),
        stock.observeProduct(productId),
    ) { active, archived, byLocation ->
        if (!LocationRules.showsLocations(active.size)) {
            ProductLocationStock.Hidden
        } else {
            val shown = active.map { LocationStock(it, byLocation[it.id] ?: QuantityMilli.ZERO) } +
                archived.mapNotNull { location ->
                    byLocation[location.id]
                        ?.takeIf { it != QuantityMilli.ZERO }
                        ?.let { LocationStock(location, it) }
                }
            ProductLocationStock.Shown(shown.sortedBy { it.location.id })
        }
    }.distinctUntilChanged()
}
