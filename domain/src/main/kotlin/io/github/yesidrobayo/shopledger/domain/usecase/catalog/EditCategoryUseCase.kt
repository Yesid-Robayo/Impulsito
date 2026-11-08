package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CategoryFormCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CategoryFormRules

/**
 * Renombra una categoría o cambia su margen objetivo. Se puede en cualquier momento: la categoría no
 * entra en ninguna cuenta de dinero, así que no tiene historia que proteger.
 *
 * Guardar sin cambios no escribe nada ni cuenta un cambio sin respaldar.
 */
class EditCategoryUseCase(
    private val transactions: TransactionRunner,
    private val categories: CategoryRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        categoryId: Long,
        name: String,
        targetMargin: BasisPoints?,
    ): SaveCategoryResult = storageSafe(SaveCategoryResult::StorageFailed) {
        transactions.write { edit(categoryId, name, targetMargin) }
    }

    private suspend fun edit(
        categoryId: Long,
        name: String,
        targetMargin: BasisPoints?,
    ): TxResult<SaveCategoryResult> {
        val category = categories.findById(categoryId)
            ?: return TxResult.Rollback(SaveCategoryResult.NotFound)
        val valid = when (val check = CategoryFormRules.check(name, targetMargin)) {
            is CategoryFormCheck.Invalid -> return TxResult.Rollback(
                SaveCategoryResult.Invalid(check.issues),
            )

            is CategoryFormCheck.Valid -> check
        }
        categories.findByNormalizedName(valid.normalizedName)
            ?.takeIf { it.id != categoryId }
            ?.let { return TxResult.Rollback(SaveCategoryResult.DuplicateName(it.details.name)) }
        val details = CategoryDetails(valid.name, valid.normalizedName, targetMargin)
        if (details ==
            category.details
        ) {
            return TxResult.Rollback(SaveCategoryResult.Saved(categoryId))
        }
        categories.update(categoryId, details, clock.now())
        return TxResult.Commit(SaveCategoryResult.Saved(categoryId))
    }
}
