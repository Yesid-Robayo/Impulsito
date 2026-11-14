package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los campos propios: sus definiciones y sus valores en cada producto. Las funciones que escriben
 * solo se llaman dentro de [TransactionRunner.write]. Las lecturas que emiten vuelven a emitir con
 * cada cambio confirmado en lo que leen.
 */
interface CustomFieldRepository {
    /** Todos, vigentes y archivados, por su orden y, con el mismo orden, por id. */
    fun observeFields(): Flow<List<CustomField>>

    suspend fun findById(id: Long): CustomField?

    /** El vigente con ese nombre normalizado: uno archivado no reserva el suyo. */
    suspend fun findActiveByNormalizedName(normalizedName: String): CustomField?

    /** El orden que le toca a un campo nuevo: después del último. */
    suspend fun nextSortOrder(): Long

    /** Crea un campo vigente y devuelve su id. */
    suspend fun insert(
        name: String,
        normalizedName: String,
        type: CustomFieldType,
        sortOrder: Long,
        createdAt: Instant,
    ): Long

    /** Cambia el nombre y el tipo. El tipo solo cambia si el campo no tiene valores. */
    suspend fun update(
        id: Long,
        name: String,
        normalizedName: String,
        type: CustomFieldType,
        updatedAt: Instant,
    )

    suspend fun setSortOrder(id: Long, sortOrder: Long, updatedAt: Instant)

    /** Lo archiva con [archivedAt], o lo reactiva con `null`. Sus valores no cambian. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)

    /** Lo borra. Solo se pide sin valores: con ellos, se archiva. */
    suspend fun delete(id: Long)

    /** El texto guardado de cada campo con valor en el producto, por id de campo. */
    fun observeValues(productId: Long): Flow<Map<Long, String>>

    /** Igual que [observeValues], dentro de la transacción que va a escribir. */
    suspend fun findValues(productId: Long): Map<Long, String>

    /** Escribe el valor de [fieldId] en el producto, nuevo o en lugar del que tenía. */
    suspend fun putValue(productId: Long, fieldId: Long, stored: String, updatedAt: Instant)

    /** Quita el valor de [fieldId] del producto. */
    suspend fun removeValue(productId: Long, fieldId: Long)
}

/**
 * Las etiquetas y sus vínculos con los productos. Las funciones que escriben solo se llaman dentro
 * de [TransactionRunner.write]. Las lecturas que emiten vuelven a emitir con cada cambio confirmado.
 */
interface TagRepository {
    /** Todas, por nombre normalizado, con cuántos productos las llevan. */
    fun observeTags(): Flow<List<ProductTag>>

    suspend fun findById(id: Long): ProductTag?

    suspend fun findByNormalizedName(normalizedName: String): ProductTag?

    /** Crea una etiqueta y devuelve su id. */
    suspend fun insert(name: String, normalizedName: String, createdAt: Instant): Long

    suspend fun rename(id: Long, name: String, normalizedName: String, updatedAt: Instant)

    /** La borra, y con ella sus vínculos. Los productos no cambian. */
    suspend fun delete(id: Long)

    /** Las etiquetas que lleva el producto. */
    fun observeTagIdsOf(productId: Long): Flow<Set<Long>>

    /** Igual que [observeTagIdsOf], dentro de la transacción que va a escribir. */
    suspend fun findTagIdsOf(productId: Long): Set<Long>

    suspend fun link(productId: Long, tagId: Long)

    suspend fun unlink(productId: Long, tagId: Long)

    /**
     * Por id de producto, la forma normalizada del nombre de cada etiqueta que lleva: lo que usa la
     * búsqueda de productos.
     */
    fun observeSearchIndex(): Flow<Map<Long, List<String>>>
}
