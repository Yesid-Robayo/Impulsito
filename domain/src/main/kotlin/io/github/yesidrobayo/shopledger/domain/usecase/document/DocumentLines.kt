package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest

/** Cómo pasa una línea guardada de una cotización o de un pedido a la venta. */
object DocumentLines {
    /**
     * La línea como la pide la venta: la cantidad en su unidad, el precio congelado como precio
     * escrito y el descuento en monto. Así la venta cobra lo que se acordó.
     */
    fun requestOf(line: DocumentLine): SaleLineRequest {
        val content = line.content
        return SaleLineRequest(
            productId = content.productId,
            quantity = content.enteredQuantity,
            unitPrice = content.unitPrice,
            discount = if (content.lineDiscount == Money.ZERO) {
                LineDiscount.None
            } else {
                LineDiscount.Amount(content.lineDiscount)
            },
            unitId = content.unitId,
        )
    }
}
