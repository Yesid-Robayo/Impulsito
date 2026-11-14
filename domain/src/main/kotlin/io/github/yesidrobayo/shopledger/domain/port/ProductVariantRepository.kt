package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import kotlinx.coroutines.flow.Flow

/**
 * Las variantes de cada producto: los productos que lo nombran como su producto principal.
 *
 * Se crean, se editan y se archivan con [ProductRepository], porque cada variante es un producto
 * con su libro. Aquí solo se leen agrupadas, archivadas o no, en el orden en que se crearon.
 */
interface ProductVariantRepository {
    /** Las variantes de [parentId], archivadas o no, en el orden de su id. */
    suspend fun findVariants(parentId: Long): List<Product>

    /** Lo mismo que [findVariants], que vuelve a emitir con cada cambio confirmado. */
    fun observeVariants(parentId: Long): Flow<List<Product>>
}
