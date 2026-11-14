package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.port.ProductComponentRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Las recetas sobre [InMemoryDatabase], con las mismas reglas que la guarda de la tabla: el padre
 * se arma con componentes, no se contiene a sí mismo, el par es único y cada fila mira un nivel a
 * cada lado. Así una prueba atrapa un caso de uso que escribiera en un orden que la base rechaza.
 */
internal class FakeProductComponentRepository(private val database: InMemoryDatabase) :
    ProductComponentRepository {
    override fun observeAll(): Flow<List<RecipeComponent>> =
        database.tables.map { it.productComponents.values.sortedBy(RecipeComponent::id) }

    override fun observeByParent(parentId: Long): Flow<List<RecipeComponent>> =
        database.tables.map { tables -> byParent(tables, parentId) }

    override suspend fun findAll(): List<RecipeComponent> =
        database.read().productComponents.values.sortedBy(RecipeComponent::id)

    override suspend fun findByParent(parentId: Long): List<RecipeComponent> =
        byParent(database.read(), parentId)

    override suspend fun insert(
        parentId: Long,
        componentId: Long,
        quantity: QuantityMilli,
        createdAt: Instant,
    ): Long {
        val rows = database.read().productComponents.values
        val taken =
            rows.any { it.parentProductId == parentId && it.componentProductId == componentId }
        check(!taken) { "UNIQUE" }
        checkGuard(parentId, componentId, quantity)
        val id = database.nextId(TABLE)
        val row = RecipeComponent(id, parentId, componentId, quantity)
        database.write { it.copy(productComponents = it.productComponents + (id to row)) }
        return id
    }

    override suspend fun updateQuantity(id: Long, quantity: QuantityMilli, updatedAt: Instant) {
        val row = checkNotNull(database.read().productComponents[id]) { "La fila no existe" }
        checkGuard(row.parentProductId, row.componentProductId, quantity)
        val changed = row.copy(quantity = quantity)
        database.write { it.copy(productComponents = it.productComponents + (id to changed)) }
    }

    override suspend fun delete(id: Long) {
        checkNotNull(database.read().productComponents[id]) { "La fila no existe" }
        database.write { it.copy(productComponents = it.productComponents - id) }
    }

    private fun checkGuard(parentId: Long, componentId: Long, quantity: QuantityMilli) {
        val tables = database.read()
        val rows = tables.productComponents.values
        val parentMode = tables.products[parentId]?.details?.stockMode
        val componentHasGrandchild = rows.any { a ->
            a.parentProductId == componentId &&
                rows.any { b -> b.parentProductId == a.componentProductId }
        }
        val parentIsComponent = rows.any { it.componentProductId == parentId }
        val componentHasChild = rows.any { it.parentProductId == componentId }
        check(
            quantity.milli in 1L..1_000_000_000L &&
                parentId != componentId &&
                parentMode != null && parentMode != ProductStockMode.UNTRACKED &&
                !componentHasGrandchild &&
                !(parentIsComponent && componentHasChild),
        ) { "guard:product_components" }
    }

    private fun byParent(tables: Tables, parentId: Long): List<RecipeComponent> =
        tables.productComponents.values
            .filter { it.parentProductId == parentId }
            .sortedBy { it.id }

    private companion object {
        const val TABLE = "product_components"
    }
}
