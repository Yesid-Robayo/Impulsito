package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.ModifierIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductModifierRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ModifierRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Cómo terminó guardar los extras de un producto. */
sealed interface SaveModifiersResult {
    /** Quedaron guardados, o ya estaban exactamente así. */
    data object Saved : SaveModifiersResult

    /** El producto ya no existe. */
    data object NotFound : SaveModifiersResult

    /** No se guardó nada, por [issue]. Los extras guardados siguen como estaban. */
    data class Invalid(val issue: ModifierIssue) : SaveModifiersResult

    /** La base no pudo escribir. No quedó nada a medias. */
    data class StorageFailed(val failure: StorageFailure) : SaveModifiersResult
}

/**
 * Guarda los extras de un producto en una sola transacción: la lista elegida, en su orden,
 * reemplaza a la guardada.
 *
 * Solo escribe lo que cambió: quita los que ya no están, mueve los que cambiaron de lugar y agrega
 * los nuevos. Unos extras iguales a los guardados no escriben nada ni cuentan un cambio sin
 * respaldar. Las ventas pasadas guardaron sus extras como líneas propias: no cambian.
 */
class SaveModifiersUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val modifiers: ProductModifierRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(productId: Long, wanted: List<Long>): SaveModifiersResult =
        storageSafe(SaveModifiersResult::StorageFailed) {
            transactions.write { save(productId, wanted) }
        }

    private suspend fun save(productId: Long, wanted: List<Long>): TxResult<SaveModifiersResult> {
        products.findById(productId) ?: return TxResult.Rollback(SaveModifiersResult.NotFound)
        val byId = products.findByIds(wanted.toSet()).associateBy { it.id }
        val withVariants = byId.keys.filterTo(HashSet()) {
            CatalogLink.VARIANT_PARENT in products.catalogLinks(it)
        }
        ModifierRules.check(productId, wanted, byId, withVariants)?.let {
            return TxResult.Rollback(SaveModifiersResult.Invalid(it))
        }
        val current = modifiers.findByProduct(productId)
        val order = wanted.withIndex().associate { (index, id) -> id to index.toLong() }
        val removed = current.filter { it.modifierProductId !in order }
        val moved = current.filter { row ->
            order[row.modifierProductId]?.let { it != row.sortOrder } == true
        }
        val kept = current.mapTo(HashSet()) { it.modifierProductId }
        val added = wanted.filter { it !in kept }
        if (removed.isEmpty() && moved.isEmpty() && added.isEmpty()) {
            return TxResult.Rollback(SaveModifiersResult.Saved)
        }
        val now = clock.now()
        removed.forEach { modifiers.delete(it.id) }
        moved.forEach { modifiers.updateOrder(it.id, order.getValue(it.modifierProductId), now) }
        added.forEach { modifiers.insert(productId, it, order.getValue(it), now) }
        return TxResult.Commit(SaveModifiersResult.Saved)
    }
}

/**
 * Lo que necesita la pantalla de los extras de un producto: el producto, sus extras en el orden en
 * que se ofrecen, y los productos que se pueden agregar, por nombre.
 */
data class ModifierEditorData(
    val product: Product,
    val extras: List<Product>,
    val candidates: List<Product>,
)

/**
 * Los extras de [productId] para su pantalla, de nuevo con cada cambio de los productos o de los
 * extras. Emite `null` si el producto ya no existe. Un extra archivado sigue en la lista, para
 * poder quitarlo; al vender no se ofrece.
 */
class ObserveModifierEditorUseCase(
    private val products: ProductRepository,
    private val modifiers: ProductModifierRepository,
) {
    operator fun invoke(productId: Long): Flow<ModifierEditorData?> = combine(
        products.observe(ArchiveFilter.ACTIVE),
        products.observe(ArchiveFilter.ARCHIVED),
        modifiers.observeByProduct(productId),
    ) { active, archived, stored ->
        val byId = (active + archived).associateBy { it.id }
        val product = byId[productId] ?: return@combine null
        val withVariants = (active + archived).mapNotNullTo(HashSet()) {
            it.details.parentProductId
        }
        val extras = stored.mapNotNull { byId[it.modifierProductId] }
        val chosen = extras.mapTo(HashSet()) { it.id }
        val candidates = active
            .filter { ModifierRules.canOffer(productId, it, withVariants) && it.id !in chosen }
            .sortedWith(compareBy<Product> { it.details.normalizedName }.thenBy { it.id })
        ModifierEditorData(product, extras, candidates)
    }
}
