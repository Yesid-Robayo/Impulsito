package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseCatalog
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * El catálogo de la compra: los productos que se compran, los archivados que la búsqueda ofrece
 * reactivar, las unidades, los proveedores y los impuestos. Vuelve a emitir con cada cambio
 * confirmado de cualquiera de ellos y cuando cambia la zona del negocio. No escribe nada.
 *
 * Solo se compran los productos con stock propio y sin lotes: un servicio o un combo no tienen
 * libro que sumar, y comprar por lote todavía no se ofrece. Las tasas archivadas también van,
 * porque un producto conserva la suya.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservePurchaseCatalogUseCase(
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val suppliers: SupplierRepository,
    private val calendar: BusinessCalendarSource,
    private val taxSettings: TaxSettingsRepository,
    private val taxRates: TaxRateRepository,
    private val clock: Clock,
    private val productUnits: ProductUnitRepository? = null,
) {
    operator fun invoke(): Flow<PurchaseCatalog> = calendar.observe().flatMapLatest { result ->
        val ready = when (result) {
            is BusinessCalendarResult.Ready -> result.calendar
            is BusinessCalendarResult.UnknownZone -> null
        }
        val rates = combine(
            taxRates.observe(ArchiveFilter.ACTIVE),
            taxRates.observe(ArchiveFilter.ARCHIVED),
        ) { active, archived -> (active + archived).associate { it.id to it.details } }
        // Solo las conversiones que se ofrecen al comprar: las de venta no se eligen aquí.
        val conversions = productUnits?.observeAll()?.map { all ->
            all.filter { it.details.forPurchase }
                .groupBy { it.productId }
                .mapValues { (_, list) -> list.associate { it.details.unitId to it.details } }
        } ?: flowOf(emptyMap())
        combine(
            products.observe(ArchiveFilter.ACTIVE),
            products.observe(ArchiveFilter.ARCHIVED),
            units.observeAll(),
            suppliers.observe(ArchiveFilter.ACTIVE),
            combine(taxSettings.observe(), rates, ::Pair),
        ) { active, archived, allUnits, activeSuppliers, (taxes, byId) ->
            PurchaseCatalog(
                products = active.filter(::isBuyable),
                archived = archived.filter(::isBuyable),
                units = allUnits.associateBy { it.id },
                suppliers = activeSuppliers,
                taxes = taxes,
                taxRates = byId,
                today = ready?.businessDateOf(clock.now()),
                weekStartDay = ready?.weekStartDay,
            )
        }.combine(conversions) { catalog, byProduct -> catalog.copy(conversions = byProduct) }
    }

    private fun isBuyable(product: Product): Boolean =
        StockModeRules.hasLedger(product.details.stockMode) && !product.details.tracksLots
}
