package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductComponentEntity
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.port.ProductComponentRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las recetas de los combos sobre `product_components`.
 *
 * Los dos niveles y la ausencia de ciclos los revisa el caso de uso sobre todas las recetas; la
 * guarda de la tabla repite lo que puede ver desde una fila, como última defensa. Cada venta
 * congela lo que consumió, así que nada de lo que se escribe aquí cambia una venta pasada.
 */
internal class RoomProductComponentRepository(database: AppDatabase) : ProductComponentRepository {
    private val components = database.productComponentDao()
    private val writes = RepositoryWrites(database)

    // Room vuelve a emitir con cualquier cambio de la tabla: una lista igual no se repite.
    override fun observeAll(): Flow<List<RecipeComponent>> =
        components.observeAll().map { rows -> rows.map(::toComponent) }.distinctUntilChanged()

    override fun observeByParent(parentId: Long): Flow<List<RecipeComponent>> =
        components.observeByParent(parentId)
            .map { rows -> rows.map(::toComponent) }
            .distinctUntilChanged()

    override suspend fun findAll(): List<RecipeComponent> = components.findAll().map(::toComponent)

    override suspend fun findByParent(parentId: Long): List<RecipeComponent> =
        components.findByParent(parentId).map(::toComponent)

    override suspend fun insert(
        parentId: Long,
        componentId: Long,
        quantity: QuantityMilli,
        createdAt: Instant,
    ): Long = writes.inTransaction {
        val at = createdAt.toStoredMillis()
        components.insert(
            ProductComponentEntity(
                parentProductId = parentId,
                componentProductId = componentId,
                quantityMilli = quantity.milli,
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    override suspend fun updateQuantity(id: Long, quantity: QuantityMilli, updatedAt: Instant) {
        writes.inTransaction {
            val at = updatedAt.toStoredMillis()
            requireOneRow(components.updateQuantity(id, quantity.milli, at))
        }
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(components.delete(id)) }
    }

    private fun toComponent(row: ProductComponentEntity): RecipeComponent = RecipeComponent(
        id = row.id,
        parentProductId = row.parentProductId,
        componentProductId = row.componentProductId,
        quantity = QuantityMilli(row.quantityMilli),
    )
}
