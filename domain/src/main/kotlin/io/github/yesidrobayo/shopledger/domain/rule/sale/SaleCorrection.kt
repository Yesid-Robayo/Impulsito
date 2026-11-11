package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest

/**
 * Lo que trae una venta nueva que corrige otra ya anulada: sus líneas con cantidad, precio y
 * descuento, el método con que se cobró, la fecha y la nota. El costo no viaja: la venta nueva
 * toma el costo de hoy al confirmarse.
 *
 * [leftOut] cuenta las líneas que no se trajeron porque su producto hoy está archivado: no se
 * puede vender, y la venta nueva lo dice.
 */
data class SaleCorrection(
    val lines: List<SaleLineRequest>,
    val paymentMethodId: Long?,
    val date: BusinessDate,
    val note: String?,
    val leftOut: Int,
)

/** Arma la corrección de una venta guardada. No escribe nada. */
object SaleCorrections {
    /**
     * La corrección de [detail].
     *
     * - Cada línea trae su precio escrito y su descuento en monto, aunque el producto hoy cueste
     *   otra cosa: se corrige lo que se vendió, no se vende otra vez a precio nuevo.
     * - Una línea de un producto hoy archivado queda por fuera y se cuenta.
     * - Los extras de una línea todavía no se venden en la pantalla, así que no se traen.
     * - Un producto en dos líneas llega en una sola, con las cantidades y los descuentos sumados y
     *   el precio de la primera, porque la venta en pantalla lleva una línea por producto.
     * - El método es el del primer pago; con varios pagos, la venta nueva se cobra con ese.
     */
    fun of(detail: SaleDetail): SaleCorrection {
        val main = detail.lines.map { it.content }.filter { it.parentLineId == null }
        val kept = main.filter { it.productId !in detail.archivedProductIds }
        // Por producto, la primera línea y lo que suman las demás, en el orden de la venta.
        val first = LinkedHashMap<Long, NewSaleLine>()
        val quantities = HashMap<Long, QuantityMilli>()
        val discounts = HashMap<Long, Money>()
        for (line in kept) {
            first.getOrPut(line.productId) { line }
            quantities[line.productId] =
                (quantities[line.productId] ?: QuantityMilli.ZERO) + line.quantity
            discounts[line.productId] =
                (discounts[line.productId] ?: Money.ZERO) + line.lineDiscount
        }
        val content = detail.sale.content
        return SaleCorrection(
            lines = first.values.map { line ->
                val discount = discounts.getValue(line.productId)
                SaleLineRequest(
                    productId = line.productId,
                    quantity = quantities.getValue(line.productId),
                    unitPrice = line.unitPrice,
                    discount =
                        if (discount >
                            Money.ZERO
                        ) {
                            LineDiscount.Amount(discount)
                        } else {
                            LineDiscount.None
                        },
                )
            },
            paymentMethodId = detail.payments.firstOrNull()?.let { it.content.paymentMethodId },
            date = content.businessDate,
            note = content.note,
            leftOut = main.size - kept.size,
        )
    }
}
