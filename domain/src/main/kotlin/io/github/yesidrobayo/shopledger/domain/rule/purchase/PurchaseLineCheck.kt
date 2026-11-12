package io.github.yesidrobayo.shopledger.domain.rule.purchase

/** Resultado de [PurchaseLineRules.check]. */
sealed interface PurchaseLineCheck {
    /** La línea se puede registrar con estos dos valores. */
    data class Ready(val amounts: PurchaseLineAmounts) : PurchaseLineCheck

    /** El costo de una unidad entera pasa del máximo de un costo unitario. */
    data object UnitCostTooLarge : PurchaseLineCheck

    /** El total de la línea pasa del máximo de un documento. */
    data object LineTotalTooLarge : PurchaseLineCheck
}
