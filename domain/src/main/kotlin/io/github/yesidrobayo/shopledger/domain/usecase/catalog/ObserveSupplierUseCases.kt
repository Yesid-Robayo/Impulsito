package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierListEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierOverview
import io.github.yesidrobayo.shopledger.domain.model.catalog.SuppliersOverview
import io.github.yesidrobayo.shopledger.domain.port.SupplierActivitySource
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * La lista de proveedores, vigentes y archivados, con cuántos productos tiene cada uno como
 * habitual. Vuelve a emitir con cada cambio confirmado de proveedores o productos.
 */
class ObserveSuppliersUseCase(
    private val suppliers: SupplierRepository,
    private val activity: SupplierActivitySource,
) {
    operator fun invoke(): Flow<SuppliersOverview> = combine(
        suppliers.observe(ArchiveFilter.ACTIVE),
        suppliers.observe(ArchiveFilter.ARCHIVED),
        activity.observeUsualProductCounts(),
    ) { active, archived, counts ->
        SuppliersOverview(
            active = active.map { SupplierListEntry(it, counts[it.id] ?: 0) },
            archived = archived.map { SupplierListEntry(it, counts[it.id] ?: 0) },
        )
    }.distinctUntilChanged()
}

/**
 * El detalle de un proveedor: sus datos, lo comprado, sus compras y sus productos habituales. Emite
 * `null` si el proveedor no existe, por ejemplo porque se acaba de eliminar.
 */
class ObserveSupplierOverviewUseCase(
    private val suppliers: SupplierRepository,
    private val activity: SupplierActivitySource,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(supplierId: Long): Flow<SupplierOverview?> =
        suppliers.observeById(supplierId).flatMapLatest { supplier ->
            if (supplier == null) {
                flowOf(null)
            } else {
                combine(
                    activity.observeTotals(supplierId),
                    activity.observePurchases(supplierId),
                    activity.observeUsualProducts(supplierId),
                ) { totals, purchases, products ->
                    SupplierOverview(supplier, totals, purchases, products)
                }
            }
        }.distinctUntilChanged()
}
