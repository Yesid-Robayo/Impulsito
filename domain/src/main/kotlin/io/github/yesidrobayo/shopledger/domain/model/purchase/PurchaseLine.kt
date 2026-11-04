package io.github.yesidrobayo.shopledger.domain.model.purchase

/** Una línea de compra guardada. Nunca se edita ni se borra, ni siquiera al anular la compra. */
data class PurchaseLine(val id: Long, val purchaseId: Long, val content: NewPurchaseLine)
