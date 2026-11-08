package io.github.yesidrobayo.shopledger.domain.model.sale

/** Un pago de una venta ya guardado. Nunca se edita ni se borra, tampoco al anular la venta. */
data class SalePayment(val id: Long, val saleId: Long, val content: NewSalePayment)
