package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las categorías de producto.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface CategoryRepository {
    /** Todas las categorías, en el orden de su nombre normalizado. */
    fun observeAll(): Flow<List<Category>>

    suspend fun findById(id: Long): Category?

    /** La categoría con ese nombre normalizado, para no repetir nombres. */
    suspend fun findByNormalizedName(normalizedName: String): Category?

    /** Cuántos productos, archivados o no, tienen esta categoría: se muestra antes de eliminarla. */
    suspend fun countProducts(id: Long): Int

    /** Crea la categoría y devuelve su id. */
    suspend fun insert(details: CategoryDetails, createdAt: Instant): Long

    suspend fun update(id: Long, details: CategoryDetails, updatedAt: Instant)

    /** Elimina la categoría. Sus productos quedan sin categoría en la misma transacción. */
    suspend fun delete(id: Long)
}
