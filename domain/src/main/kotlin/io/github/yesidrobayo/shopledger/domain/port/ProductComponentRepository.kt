package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las recetas de los combos: qué componentes lleva cada uno y cuánto de cada uno.
 *
 * Un combo tiene a lo sumo una fila por componente. Las listas van en el orden en que se armó la
 * receta. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write]. Cada
 * venta congela lo que consumió, así que nada de lo que se escribe aquí cambia una venta pasada.
 */
interface ProductComponentRepository {
    /** Todas las filas de todas las recetas, de nuevo con cada cambio. */
    fun observeAll(): Flow<List<RecipeComponent>>

    /** La receta de [parentId], en el orden en que se armó. */
    fun observeByParent(parentId: Long): Flow<List<RecipeComponent>>

    /** Todas las filas de todas las recetas: el grafo que revisa niveles y ciclos. */
    suspend fun findAll(): List<RecipeComponent>

    /** La receta de [parentId], en el orden en que se armó. */
    suspend fun findByParent(parentId: Long): List<RecipeComponent>

    /** Agrega [componentId] a la receta de [parentId] y devuelve el id de la fila. */
    suspend fun insert(
        parentId: Long,
        componentId: Long,
        quantity: QuantityMilli,
        createdAt: Instant,
    ): Long

    /** Cambia solo la cantidad de la fila [id]. */
    suspend fun updateQuantity(id: Long, quantity: QuantityMilli, updatedAt: Instant)

    /** Quita la fila [id] de su receta. */
    suspend fun delete(id: Long)
}
