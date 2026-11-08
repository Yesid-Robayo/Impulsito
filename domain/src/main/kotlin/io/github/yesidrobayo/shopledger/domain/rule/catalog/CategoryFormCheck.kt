package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryFormIssue

/** Resultado de [CategoryFormRules.check]. */
sealed interface CategoryFormCheck {
    /** Se puede guardar: el nombre recortado y su forma normalizada. */
    data class Valid(val name: String, val normalizedName: String) : CategoryFormCheck

    /** No se puede guardar, por todos estos motivos a la vez. */
    data class Invalid(val issues: Set<CategoryFormIssue>) : CategoryFormCheck
}
