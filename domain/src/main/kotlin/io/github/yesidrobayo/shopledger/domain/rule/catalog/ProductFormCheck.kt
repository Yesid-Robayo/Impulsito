package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue

/** Resultado de [ProductFormRules.check]. */
sealed interface ProductFormCheck {
    /** Se puede guardar: el nombre recortado y normalizado, y el código recortado o `null`. */
    data class Valid(val name: String, val normalizedName: String, val barcode: String?) :
        ProductFormCheck

    /** No se puede guardar, por todos estos motivos a la vez. */
    data class Invalid(val issues: Set<ProductFormIssue>) : ProductFormCheck
}
