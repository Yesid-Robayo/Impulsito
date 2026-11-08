package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryFormIssue

/**
 * Valida el nombre y el margen objetivo de una categoría de producto antes de guardarla.
 *
 * Un margen objetivo de 100 % o más no tiene precio sugerido: dividiría por cero o daría un precio
 * negativo. Por eso el tope es 99 %.
 */
object CategoryFormRules {
    fun check(name: String, targetMargin: BasisPoints?): CategoryFormCheck {
        val checked = CatalogText.name(name, CatalogText.CATEGORY_NAME_MAX)
        val issues = mutableSetOf<CategoryFormIssue>()
        when (checked) {
            CatalogName.Empty -> issues += CategoryFormIssue.NAME_EMPTY
            CatalogName.TooLong -> issues += CategoryFormIssue.NAME_TOO_LONG
            is CatalogName.Valid -> Unit
        }
        if (targetMargin != null && !isTargetMargin(targetMargin)) {
            issues += CategoryFormIssue.TARGET_MARGIN_OUT_OF_RANGE
        }
        return if (checked !is CatalogName.Valid || issues.isNotEmpty()) {
            CategoryFormCheck.Invalid(issues)
        } else {
            CategoryFormCheck.Valid(checked.name, checked.normalizedName)
        }
    }

    private fun isTargetMargin(margin: BasisPoints): Boolean =
        margin >= BasisPoints.ZERO && margin <= BasisPoints.MAX_TARGET_MARGIN
}
