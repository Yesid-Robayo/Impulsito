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
 * Crea una categoría de producto, también desde el formulario del producto, sin salir de él.
 *
 * El nombre es único entre todas las categorías, comparado sin mayúsculas ni tildes: dos categorías
 * que solo se distinguen por una tilde partirían el catálogo sin que nadie lo note.
 */
class CreateCategoryUseCase(
    private val transactions: TransactionRunner,
    private val categories: CategoryRepository,
    private val clock: Clock,
) {
    /** Crea la categoría [name], con su margen objetivo [targetMargin], o sin él con `null`. */
    suspend operator fun invoke(
        name: String,
        targetMargin: BasisPoints? = null,
    ): SaveCategoryResult = storageSafe(SaveCategoryResult::StorageFailed) {
        transactions.write { create(name, targetMargin) }
    }

    private suspend fun create(
        name: String,
        targetMargin: BasisPoints?,
    ): TxResult<SaveCategoryResult> {
        val valid = when (val check = CategoryFormRules.check(name, targetMargin)) {
            is CategoryFormCheck.Invalid -> return TxResult.Rollback(
                SaveCategoryResult.Invalid(check.issues),
            )

            is CategoryFormCheck.Valid -> check
        }
        categories.findByNormalizedName(valid.normalizedName)?.let {
            return TxResult.Rollback(SaveCategoryResult.DuplicateName(it.details.name))
        }
        val details = CategoryDetails(valid.name, valid.normalizedName, targetMargin)
        val id = categories.insert(details, clock.now())
        return TxResult.Commit(SaveCategoryResult.Saved(id))
    }
}
