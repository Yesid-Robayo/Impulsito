package io.github.yesidrobayo.shopledger.domain.usecase.production

import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/**
 * Una producción de una tarjeta: [request], lo que se produce y con qué, y [recipe], la receta
 * entera que se guarda antes de producir, o `null` si la receta no cambia.
 */
data class ProductionStep(val request: ProductionRequest, val recipe: List<RecipeLine>? = null)

/** Cómo terminó registrar varias producciones juntas. Un rechazo no deja ninguna. */
sealed interface RegisterProductionsResult {
    /** Quedaron todas, con el id de cada una en el orden pedido. */
    data class Registered(val batchIds: List<Long>) : RegisterProductionsResult

    /** La receta de la producción [index] no se guardó, por [result]. */
    data class RecipeRejected(val index: Int, val result: SaveRecipeResult) :
        RegisterProductionsResult

    /** La producción [index] no se registró, por [result]. */
    data class ProductionRejected(val index: Int, val result: RegisterProductionResult) :
        RegisterProductionsResult

    /** La base no pudo escribir, por [failure]. No quedó nada. */
    data class StorageFailed(val failure: StorageFailure) : RegisterProductionsResult
}

/**
 * Registra las producciones de una tarjeta en una sola transacción, como "hice 10 empanadas y 5
 * panes", o una producción con la receta que se dijo, como "hice un pastel con 200 g de harina y 3
 * huevos" de un pastel sin receta. Cada paso usa las reglas de siempre: primero guarda la receta, si
 * cambia, y luego produce, y la producción siguiente ve el stock y el costo que dejó la anterior.
 *
 * Si una receta o una producción se rechaza, o la base falla, no queda ninguna: ni recetas ni
 * producciones a medias.
 */
class RegisterProductionsUseCase(
    private val transactions: TransactionRunner,
    private val recipes: SaveRecipeUseCase,
    private val production: RegisterProductionUseCase,
) {
    suspend operator fun invoke(steps: List<ProductionStep>): RegisterProductionsResult {
        require(steps.isNotEmpty()) { "Hace falta al menos una producción" }
        return storageSafe(RegisterProductionsResult::StorageFailed) {
            transactions.write { registerAll(steps) }
        }
    }

    /**
     * Lo mismo que [invoke] dentro de una transacción que ya abrió quien llama, como producir un
     * producto nuevo dicho con una frase, que se crea en la misma escritura.
     */
    internal suspend fun registerWithin(
        steps: List<ProductionStep>,
    ): TxResult<RegisterProductionsResult> = registerAll(steps)

    private suspend fun registerAll(
        steps: List<ProductionStep>,
    ): TxResult<RegisterProductionsResult> {
        val batches = ArrayList<Long>()
        for ((index, step) in steps.withIndex()) {
            val recipe = step.recipe
            if (recipe != null) {
                // Una receta igual a la guardada no escribe nada y sigue.
                val saved = recipes.save(step.request.productId, recipe).outcome
                if (saved != SaveRecipeResult.Saved) {
                    return TxResult.Rollback(RegisterProductionsResult.RecipeRejected(index, saved))
                }
            }
            when (val result = production.register(step.request).outcome) {
                is RegisterProductionResult.Registered -> batches += result.batchId

                else -> return TxResult.Rollback(
                    RegisterProductionsResult.ProductionRejected(index, result),
                )
            }
        }
        return TxResult.Commit(RegisterProductionsResult.Registered(batches))
    }
}

/** El valor de un paso, se confirme o se deshaga: aquí todo se junta en la misma transacción. */
private val <T> TxResult<T>.outcome: T
    get() = when (this) {
        is TxResult.Commit -> value
        is TxResult.CommitUncounted -> value
        is TxResult.Rollback -> value
    }
