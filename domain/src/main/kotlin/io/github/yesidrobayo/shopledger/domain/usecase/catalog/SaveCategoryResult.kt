package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryFormIssue
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/** Cómo termina crear o editar una categoría. Salvo [Saved], no se guardó nada. */
sealed interface SaveCategoryResult {
    data class Saved(val categoryId: Long) : SaveCategoryResult

    /** Lo escrito no se puede guardar, por todos estos motivos a la vez. */
    data class Invalid(val issues: Set<CategoryFormIssue>) : SaveCategoryResult

    /**
     * Otra categoría ya tiene ese nombre, comparado sin mayúsculas ni tildes. [existingName] es
     * el suyo tal como se guardó.
     */
    data class DuplicateName(val existingName: String) : SaveCategoryResult

    /** La categoría que se editaba ya no existe. */
    data object NotFound : SaveCategoryResult

    data class StorageFailed(val failure: StorageFailure) : SaveCategoryResult
}
