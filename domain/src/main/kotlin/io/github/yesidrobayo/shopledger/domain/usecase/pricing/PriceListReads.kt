package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListsOverview
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceResult
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Las listas de precios como las muestra su pantalla: vigentes, archivadas y cuáles tienen uso.
 * Vuelve a emitir con cada cambio confirmado.
 */
class ObservePriceListsUseCase(private val priceLists: PriceListRepository) {
    operator fun invoke(): Flow<PriceListsOverview> = combine(
        priceLists.observeActive(),
        priceLists.observeArchived(),
        priceLists.observeInUse(),
    ) { active, archived, inUse -> PriceListsOverview(active, archived, inUse) }
        .distinctUntilChanged()
}

/**
 * El precio de un producto en una lista, leído una vez. Se usa al vender y dentro de la transacción
 * de un caso de uso, donde no se observa: lee todas las listas, que son pocas, y los precios
 * propios de ese producto.
 */
class ListPriceResolver(
    private val priceLists: PriceListRepository,
    private val productPrices: ProductPriceRepository,
) {
    /** El precio de [productId], de precio de venta [salePrice], en la lista [priceListId]. */
    suspend fun priceOf(priceListId: Long, productId: Long, salePrice: Money): ListPriceResult =
        ListPriceRules.resolve(
            listId = priceListId,
            lists = priceLists.findAll().associateBy { it.id },
            salePrice = salePrice,
            ownPrices = productPrices.findByProduct(productId),
        )
}
