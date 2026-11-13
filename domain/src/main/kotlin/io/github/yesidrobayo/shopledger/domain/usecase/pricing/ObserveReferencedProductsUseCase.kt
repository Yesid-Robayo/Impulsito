package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Cuántos productos vigentes tienen costo o precio de referencia: los que una cotización nueva
 * podría repreciar. Solo cuenta; no cambia ningún precio. Vuelve a emitir con cada cambio.
 */
class ObserveReferencedProductsUseCase(private val products: ProductRepository) {
    operator fun invoke(): Flow<Int> = products.observe(ArchiveFilter.ACTIVE)
        .map { list -> list.count(::hasReference) }
        .distinctUntilChanged()

    private fun hasReference(product: Product): Boolean {
        val details = product.details
        return (details.referenceCost ?: Money.ZERO) > Money.ZERO ||
            (details.referencePrice ?: Money.ZERO) > Money.ZERO
    }
}
