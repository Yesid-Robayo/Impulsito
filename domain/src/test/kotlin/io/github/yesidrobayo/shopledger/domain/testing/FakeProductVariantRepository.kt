package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.port.ProductVariantRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un [ProductVariantRepository] sobre [InMemoryDatabase]. Lee los productos con [products], para
 * que cada variante salga con el estado de stock que dejó el libro, como en la base real.
 */
internal class FakeProductVariantRepository(
    private val database: InMemoryDatabase,
    private val products: FakeProductRepository,
) : ProductVariantRepository {
    override suspend fun findVariants(parentId: Long): List<Product> =
        idsOf(database.read(), parentId).mapNotNull { products.findById(it) }

    override fun observeVariants(parentId: Long): Flow<List<Product>> =
        database.tables.map { tables ->
            idsOf(tables, parentId).mapNotNull { products.findById(it) }
        }

    private fun idsOf(tables: Tables, parentId: Long): List<Long> = tables.products.values
        .filter { it.details.parentProductId == parentId }
        .map { it.id }
        .sorted()
}
