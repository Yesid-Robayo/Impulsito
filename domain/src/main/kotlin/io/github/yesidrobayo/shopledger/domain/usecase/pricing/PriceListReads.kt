package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListsOverview
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceResult
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Las listas de precios como las muestra su pantalla: vigentes, archivadas y cuáles tienen uso,
 * los precios propios de cada lista y cuántos clientes tiene cada una. Vuelve a emitir con cada
 * cambio confirmado.
 */
class ObservePriceListsUseCase(
    private val priceLists: PriceListRepository,
    private val productPrices: ProductPriceRepository,
) {
    /** Los precios propios: por lista, y dentro, por producto. */
    fun ownPrices(): Flow<Map<Long, Map<Long, Money>>> = productPrices.observeAll()

    /** Cuántos clientes vigentes tiene cada lista, que vuelven a la inicial si se archiva. */
    fun customerCounts(): Flow<Map<Long, Int>> = priceLists.observeCustomerCounts()

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

/** El precio de un producto en una lista, para mostrarlo junto al producto. */
data class ProductListPrice(val list: PriceList, val result: ListPriceResult)

/**
 * Los precios de un producto en las listas vigentes distintas de la inicial, cuyo precio es el de
 * venta y se edita aparte. Con una sola lista no hay nada que mostrar. Vuelve a emitir con cada
 * cambio confirmado de las listas, de los precios propios o del producto.
 */
class ObserveProductListPricesUseCase(
    private val priceLists: PriceListRepository,
    private val productPrices: ProductPriceRepository,
    private val products: ProductRepository,
) {
    operator fun invoke(productId: Long): Flow<List<ProductListPrice>> = combine(
        priceLists.observeActive(),
        priceLists.observeArchived(),
        productPrices.observeAll(),
        products.observeById(productId),
    ) { active, archived, own, product ->
        if (product == null || active.size < 2) return@combine emptyList()
        val all = (active + archived).associateBy { it.id }
        val mine = own.mapNotNull { (list, prices) ->
            prices[productId]?.let { list to it }
        }.toMap()
        active.filterNot { it.isDefault }.map { list ->
            ProductListPrice(list, ListPriceRules.resolve(list.id, all, product.salePrice, mine))
        }
    }.distinctUntilChanged()
}
