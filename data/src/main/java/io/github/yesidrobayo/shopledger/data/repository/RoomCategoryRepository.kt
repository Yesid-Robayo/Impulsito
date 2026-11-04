package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.CategoryEntity
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las categorías de producto sobre `categories`.
 *
 * Al eliminar una, la llave de `products` deja sin categoría a sus productos en la misma sentencia:
 * no hace falta tocarlos aquí.
 */
internal class RoomCategoryRepository(database: AppDatabase) : CategoryRepository {
    private val categories = database.categoryDao()
    private val writes = RepositoryWrites(database)

    // Room vuelve a emitir con cualquier cambio de la tabla, aunque lo leído sea igual: una lista
    // igual a la anterior no se repite.
    override fun observeAll(): Flow<List<Category>> =
        categories.observeAll().map { rows -> rows.map(::toCategory) }.distinctUntilChanged()

    override suspend fun findById(id: Long): Category? = categories.findById(id)?.let(::toCategory)

    override suspend fun findByNormalizedName(normalizedName: String): Category? =
        categories.findByNormalizedName(normalizedName)?.let(::toCategory)

    override suspend fun countProducts(id: Long): Int = categories.countProducts(id)

    override suspend fun insert(details: CategoryDetails, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            categories.insert(
                CategoryEntity(
                    name = details.name,
                    normalizedName = details.normalizedName,
                    targetMarginBp = details.targetMargin?.bp,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    override suspend fun update(id: Long, details: CategoryDetails, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                categories.update(
                    id = id,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    targetMarginBp = details.targetMargin?.bp,
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(categories.delete(id)) }
    }

    private fun toCategory(row: CategoryEntity): Category = Category(
        id = row.id,
        details = CategoryDetails(
            name = row.name,
            normalizedName = row.normalizedName,
            targetMargin = row.targetMarginBp?.let(::BasisPoints),
        ),
        createdAt = row.createdAt.toInstant(),
        updatedAt = row.updatedAt.toInstant(),
    )
}
