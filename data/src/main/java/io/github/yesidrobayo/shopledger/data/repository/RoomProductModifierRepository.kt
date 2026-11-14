package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductModifierEntity
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductModifier
import io.github.yesidrobayo.shopledger.domain.port.ProductModifierRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los extras de cada producto sobre `product_modifiers`.
 *
 * Qué puede ser extra lo revisa el caso de uso; la guarda de la tabla repite que un producto no
 * es extra de sí mismo. Cada venta guarda sus extras como líneas propias, así que nada de lo que
 * se escribe aquí cambia una venta pasada.
 */
internal class RoomProductModifierRepository(database: AppDatabase) : ProductModifierRepository {
    private val modifiers = database.productModifierDao()
    private val writes = RepositoryWrites(database)

    // Room vuelve a emitir con cualquier cambio de la tabla: una lista igual no se repite.
    override fun observeAll(): Flow<List<ProductModifier>> =
        modifiers.observeAll().map { rows -> rows.map(::toModifier) }.distinctUntilChanged()

    override fun observeByProduct(productId: Long): Flow<List<ProductModifier>> =
        modifiers.observeByProduct(productId)
            .map { rows -> rows.map(::toModifier) }
            .distinctUntilChanged()

    override suspend fun findByProduct(productId: Long): List<ProductModifier> =
        modifiers.findByProduct(productId).map(::toModifier)

    override suspend fun insert(
        productId: Long,
        modifierProductId: Long,
        sortOrder: Long,
        createdAt: Instant,
    ): Long = writes.inTransaction {
        val at = createdAt.toStoredMillis()
        modifiers.insert(
            ProductModifierEntity(
                productId = productId,
                modifierProductId = modifierProductId,
                groupName = null,
                sortOrder = sortOrder,
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    override suspend fun updateOrder(id: Long, sortOrder: Long, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(modifiers.updateOrder(id, sortOrder, updatedAt.toStoredMillis()))
        }
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(modifiers.delete(id)) }
    }

    private fun toModifier(row: ProductModifierEntity): ProductModifier = ProductModifier(
        id = row.id,
        productId = row.productId,
        modifierProductId = row.modifierProductId,
        groupName = row.groupName,
        sortOrder = row.sortOrder,
    )
}
