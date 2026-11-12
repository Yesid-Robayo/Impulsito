package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryInputError
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseCategoryCheck
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/** Cómo termina crear o editar una categoría de gasto. Salvo [Saved], no se escribió nada. */
sealed interface SaveExpenseCategoryResult {
    /** Quedó guardada, o ya estaba exactamente así. [categoryId] es el de la categoría. */
    data class Saved(val categoryId: Long) : SaveExpenseCategoryResult

    /** No se guardó nada: cada error va en su campo, y lo escrito se conserva. */
    data class Invalid(val errors: Set<ExpenseCategoryInputError>) : SaveExpenseCategoryResult

    /** Otra categoría vigente ya se llama así, comparado sin mayúsculas ni tildes. */
    data object DuplicateName : SaveExpenseCategoryResult

    /** La categoría que se editaba ya no existe. */
    data object NotFound : SaveExpenseCategoryResult

    /** La base no pudo escribir. No quedó nada a medias, y lo escrito se conserva. */
    data class StorageFailed(val failure: StorageFailure) : SaveExpenseCategoryResult
}

/**
 * Cómo termina archivar, reactivar o eliminar una categoría de gasto. Salvo [Done], no se escribió
 * nada.
 */
sealed interface ExpenseCategoryLifecycleResult {
    /** Se hizo lo pedido. */
    data object Done : ExpenseCategoryLifecycleResult

    /** La categoría ya no existe. */
    data object NotFound : ExpenseCategoryLifecycleResult

    /** Ya estaba como se pedía: archivada al archivar, o vigente al reactivar. Nada cambió. */
    data object Unchanged : ExpenseCategoryLifecycleResult

    /**
     * Es la categoría de otros gastos, la que se propone por defecto: no se archiva ni se elimina,
     * para que siempre haya dónde anotar un gasto.
     */
    data object Protected : ExpenseCategoryLifecycleResult

    /**
     * Tiene gastos, aunque estén anulados. Ninguno se borra, así que la categoría tampoco: solo se
     * archiva, y sigue en los reportes.
     */
    data object HasExpenses : ExpenseCategoryLifecycleResult

    /**
     * Otra categoría vigente ya usa su nombre. Hay que renombrar una de las dos antes de
     * reactivarla.
     */
    data object NameTaken : ExpenseCategoryLifecycleResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : ExpenseCategoryLifecycleResult
}

/**
 * Crea una categoría de gasto, o renombra una existente, en una sola transacción.
 *
 * El nombre es único entre las vigentes, comparado sin mayúsculas ni tildes: una archivada no
 * reserva el suyo. Una categoría inicial sin nombre propio sigue el idioma de la interfaz; con uno,
 * deja de seguirlo, y guardarla en blanco la devuelve al nombre traducido. Ese nombre traducido no
 * reserva nada, porque depende del idioma de quien mira. Los gastos pasados no cambian: guardan el
 * id de su categoría, no su nombre. Una edición que no cambia nada no escribe ni cuenta un cambio
 * sin respaldar.
 */
class SaveExpenseCategoryUseCase(
    private val transactions: TransactionRunner,
    private val categories: ExpenseCategoryRepository,
    private val clock: Clock,
) {
    /** Crea la categoría de [draft] si [editingId] es nulo, o edita esa categoría. */
    suspend operator fun invoke(
        editingId: Long?,
        draft: ExpenseCategoryDraft,
    ): SaveExpenseCategoryResult = storageSafe(SaveExpenseCategoryResult::StorageFailed) {
        transactions.write { save(editingId, draft) }
    }

    private suspend fun save(
        editingId: Long?,
        draft: ExpenseCategoryDraft,
    ): TxResult<SaveExpenseCategoryResult> {
        val current = editingId?.let {
            categories.findById(it) ?: return TxResult.Rollback(SaveExpenseCategoryResult.NotFound)
        }
        val details =
            when (val check = ExpenseRules.checkCategory(draft, current?.systemKey != null)) {
                is ExpenseCategoryCheck.Invalid ->
                    return TxResult.Rollback(SaveExpenseCategoryResult.Invalid(check.errors))

                is ExpenseCategoryCheck.Valid -> check.details
            }
        // Solo una vigente choca: así lo cuida también la guarda de la base.
        val clash = details.normalizedName
            ?.takeIf { current?.isArchived != true }
            ?.let { categories.findActiveByNormalizedName(it) }
        if (clash != null && clash.id != editingId) {
            return TxResult.Rollback(SaveExpenseCategoryResult.DuplicateName)
        }
        if (current == null) {
            val id = categories.insert(details, clock.now())
            return TxResult.Commit(SaveExpenseCategoryResult.Saved(id))
        }
        if (current.details == details) {
            return TxResult.Rollback(SaveExpenseCategoryResult.Saved(current.id))
        }
        categories.update(current.id, details, clock.now())
        return TxResult.Commit(SaveExpenseCategoryResult.Saved(current.id))
    }
}

/**
 * Archiva una categoría de gasto: deja de ofrecerse en gastos nuevos, pero sus gastos siguen ligados
 * a ella y en los reportes, y se puede reactivar. Es lo que se hace con una que ya tiene gastos.
 */
class ArchiveExpenseCategoryUseCase(
    private val transactions: TransactionRunner,
    private val categories: ExpenseCategoryRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(categoryId: Long): ExpenseCategoryLifecycleResult =
        storageSafe(ExpenseCategoryLifecycleResult::StorageFailed) {
            transactions.write { archive(categoryId) }
        }

    private suspend fun archive(categoryId: Long): TxResult<ExpenseCategoryLifecycleResult> {
        val category = categories.findById(categoryId)
            ?: return TxResult.Rollback(ExpenseCategoryLifecycleResult.NotFound)
        if (category.isSystem) return TxResult.Rollback(ExpenseCategoryLifecycleResult.Protected)
        if (category.isArchived) return TxResult.Rollback(ExpenseCategoryLifecycleResult.Unchanged)
        val now = clock.now()
        categories.setArchivedAt(categoryId, now, now)
        return TxResult.Commit(ExpenseCategoryLifecycleResult.Done)
    }
}

/**
 * Reactiva una categoría de gasto archivada: vuelve a ofrecerse en gastos nuevos.
 *
 * Una archivada no reserva su nombre, así que mientras estuvo archivada otra pudo tomarlo: entonces
 * se rechaza y se pide renombrar una de las dos. Una inicial sin nombre propio nunca choca.
 */
class ReactivateExpenseCategoryUseCase(
    private val transactions: TransactionRunner,
    private val categories: ExpenseCategoryRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(categoryId: Long): ExpenseCategoryLifecycleResult =
        storageSafe(ExpenseCategoryLifecycleResult::StorageFailed) {
            transactions.write { reactivate(categoryId) }
        }

    private suspend fun reactivate(categoryId: Long): TxResult<ExpenseCategoryLifecycleResult> {
        val category = categories.findById(categoryId)
            ?: return TxResult.Rollback(ExpenseCategoryLifecycleResult.NotFound)
        if (!category.isArchived) return TxResult.Rollback(ExpenseCategoryLifecycleResult.Unchanged)
        val clash = category.details.normalizedName?.let {
            categories.findActiveByNormalizedName(it)
        }
        if (clash != null) return TxResult.Rollback(ExpenseCategoryLifecycleResult.NameTaken)
        categories.setArchivedAt(categoryId, null, clock.now())
        return TxResult.Commit(ExpenseCategoryLifecycleResult.Done)
    }
}

/**
 * Elimina una categoría de gasto creada por error, sin gastos.
 *
 * Antes de intentarlo dice por qué no se puede, si no se puede: con gastos, aunque estén anulados,
 * solo se archiva, y la de otros gastos nunca se elimina. La base lo rechazaría igual, pero sin decir
 * el motivo.
 */
class DeleteExpenseCategoryUseCase(
    private val transactions: TransactionRunner,
    private val categories: ExpenseCategoryRepository,
) {
    suspend operator fun invoke(categoryId: Long): ExpenseCategoryLifecycleResult =
        storageSafe(ExpenseCategoryLifecycleResult::StorageFailed) {
            transactions.write { delete(categoryId) }
        }

    private suspend fun delete(categoryId: Long): TxResult<ExpenseCategoryLifecycleResult> {
        val category = categories.findById(categoryId)
            ?: return TxResult.Rollback(ExpenseCategoryLifecycleResult.NotFound)
        if (category.isSystem) return TxResult.Rollback(ExpenseCategoryLifecycleResult.Protected)
        if (categories.hasExpenses(categoryId)) {
            return TxResult.Rollback(ExpenseCategoryLifecycleResult.HasExpenses)
        }
        categories.delete(categoryId)
        return TxResult.Commit(ExpenseCategoryLifecycleResult.Done)
    }
}
