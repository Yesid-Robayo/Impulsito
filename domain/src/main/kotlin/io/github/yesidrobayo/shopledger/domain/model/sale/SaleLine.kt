package io.github.yesidrobayo.shopledger.domain.model.sale

/** Una línea de venta guardada. Nunca se edita ni se borra, ni siquiera al anular la venta. */
data class SaleLine(val id: Long, val saleId: Long, val content: NewSaleLine)
