package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductModifier
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los extras de cada producto: qué otros productos se ofrecen al venderlo.
 *
 * Las listas van en el orden en que se ofrecen. Las funciones que escriben solo se llaman dentro de
 * [TransactionRunner.write]. Cada venta guarda sus extras como líneas propias, así que nada de lo
 * que se escribe aquí cambia una venta pasada.
 */
interface ProductModifierRepository {
    /** Todos los extras de todos los productos, de nuevo con cada cambio. */
    fun observeAll(): Flow<List<ProductModifier>>

    /** Los extras de [productId], en el orden en que se ofrecen. */
    fun observeByProduct(productId: Long): Flow<List<ProductModifier>>

    /** Los extras de [productId], en el orden en que se ofrecen. */
    suspend fun findByProduct(productId: Long): List<ProductModifier>

    /** Agrega [modifierProductId] a los extras de [productId] y devuelve el id de la fila. */
    suspend fun insert(
        productId: Long,
        modifierProductId: Long,
        sortOrder: Long,
        createdAt: Instant,
    ): Long

    /** Cambia solo el orden de la fila [id], sin tocar su grupo. */
    suspend fun updateOrder(id: Long, sortOrder: Long, updatedAt: Instant)

    /** Quita la fila [id] de los extras de su producto. */
    suspend fun delete(id: Long)
}
