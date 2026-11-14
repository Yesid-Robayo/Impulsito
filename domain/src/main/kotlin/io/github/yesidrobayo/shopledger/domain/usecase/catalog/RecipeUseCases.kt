package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductComponentRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.RecipeCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.RecipeRules

/** Cómo terminó guardar la receta de un combo o de un producto que se produce. */
sealed interface SaveRecipeResult {
    /** Quedó guardada, o ya estaba exactamente así. */
    data object Saved : SaveRecipeResult

    /** El combo ya no existe. */
    data object NotFound : SaveRecipeResult

    /**
     * El producto no admite receta: no es un combo ni lleva stock propio que se pueda producir.
     */
    data object NotCombo : SaveRecipeResult

    /** No se guardó nada: [issues] dice qué corregir, y lo armado se conserva. */
    data class Invalid(val issues: List<RecipeIssue>) : SaveRecipeResult

    /** La base no pudo escribir. No quedó nada a medias, y lo armado se conserva. */
    data class StorageFailed(val failure: StorageFailure) : SaveRecipeResult
}

/**
 * Guarda la receta entera de un combo, o de un producto con stock que se produce, en una sola
 * transacción: la que armó la persona reemplaza a la guardada.
 *
 * Solo escribe lo que cambió: quita las filas que ya no están, cambia la cantidad de las que
 * siguen y agrega las nuevas, en ese orden, para que la guarda de la tabla nunca vea a la vez una
 * fila vieja y su reemplazo. Antes revisa la receta completa contra todas las demás, con los dos
 * niveles y sin ciclos. Una receta que no cambia nada no escribe nada ni cuenta un cambio sin
 * respaldar.
 */
class SaveRecipeUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val components: ProductComponentRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(parentId: Long, lines: List<RecipeLine>): SaveRecipeResult =
        storageSafe(SaveRecipeResult::StorageFailed) {
            transactions.write { save(parentId, lines) }
        }

    private suspend fun save(parentId: Long, lines: List<RecipeLine>): TxResult<SaveRecipeResult> {
        val parent = products.findById(parentId) ?: return reject(SaveRecipeResult.NotFound)
        if (!RecipeRules.allowsRecipe(parent.details.stockMode)) {
            return reject(SaveRecipeResult.NotCombo)
        }
        val stored = components.findAll()
        val found = products.findByIds(lines.map { it.componentProductId }.distinct())
        val byId = found.associateBy { it.id }
        when (val check = RecipeRules.check(parentId, lines, byId, stored)) {
            is RecipeCheck.Invalid -> return reject(SaveRecipeResult.Invalid(check.issues))
            RecipeCheck.Valid -> Unit
        }
        val current = stored.filter { it.parentProductId == parentId }
        val wanted = lines.associateBy { it.componentProductId }
        val removed = current.filter { it.componentProductId !in wanted }
        val changed = current.filter { row ->
            wanted[row.componentProductId]?.let { it.quantity != row.quantity } == true
        }
        val kept = current.mapTo(HashSet()) { it.componentProductId }
        val added = lines.filter { it.componentProductId !in kept }
        if (removed.isEmpty() && changed.isEmpty() && added.isEmpty()) {
            return TxResult.Rollback(SaveRecipeResult.Saved)
        }
        val now = clock.now()
        removed.forEach { components.delete(it.id) }
        changed.forEach { row ->
            val quantity = wanted.getValue(row.componentProductId).quantity
            components.updateQuantity(row.id, quantity, now)
        }
        added.forEach { components.insert(parentId, it.componentProductId, it.quantity, now) }
        return TxResult.Commit(SaveRecipeResult.Saved)
    }

    private fun reject(result: SaveRecipeResult): TxResult<SaveRecipeResult> =
        TxResult.Rollback(result)
}
