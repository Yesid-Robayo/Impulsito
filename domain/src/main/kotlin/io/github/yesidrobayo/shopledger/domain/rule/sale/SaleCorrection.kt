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
     * - Una línea de un producto hoy archivado queda por fuera y se cuenta, con los extras que
     *   llevaba.
     * - Un producto en dos líneas llega en una sola, con las cantidades y los descuentos sumados y
     *   el precio de la primera, porque la venta en pantalla lleva una línea por producto.
     * - Los extras llegan bajo la línea de su producto, uno por producto de extra, con el precio del
     *   primero y los descuentos sumados. Su cantidad la pone la venta en pantalla, que la hace
     *   seguir a su línea.
     * - El método es el del primer pago; con varios pagos, la venta nueva se cobra con ese.
     */
    fun of(detail: SaleDetail): SaleCorrection {
        val archived = detail.archivedProductIds
        val productOfLine = detail.lines.associate { it.id to it.content.productId }
        val mains = Merged()
        // Por producto principal, sus extras por producto, en el orden de la venta.
        val extras = LinkedHashMap<Long, Merged>()
        var leftOut = 0
        for (line in detail.lines) {
            val content = line.content
            val parentProduct = content.parentLineId?.let { productOfLine[it] }
            when {
                content.productId in archived ||
                    (parentProduct != null && parentProduct in archived) -> leftOut++

                parentProduct == null -> mains.add(content)

                else -> extras.getOrPut(parentProduct) { Merged() }.add(content)
            }
        }
        val lines = ArrayList<SaleLineRequest>()
        for (main in mains.lines()) {
            val mainIndex = lines.size
            lines += main
            for (extra in extras[main.productId]?.lines().orEmpty()) {
                lines += extra.copy(parentIndex = mainIndex)
            }
        }
        val content = detail.sale.content
        return SaleCorrection(
            lines = lines,
            paymentMethodId = detail.payments.firstOrNull()?.let { it.content.paymentMethodId },
            date = content.businessDate,
            note = content.note,
            leftOut = leftOut,
        )
    }

    /** Las líneas de cada producto juntas: la primera, y lo que suman las demás. */
    private class Merged {
        private val first = LinkedHashMap<Long, NewSaleLine>()
        private val quantities = HashMap<Long, QuantityMilli>()
        private val discounts = HashMap<Long, Money>()

        fun add(line: NewSaleLine) {
            first.getOrPut(line.productId) { line }
            quantities[line.productId] =
                (quantities[line.productId] ?: QuantityMilli.ZERO) + line.quantity
            discounts[line.productId] =
                (discounts[line.productId] ?: Money.ZERO) + line.lineDiscount
        }

        fun lines(): List<SaleLineRequest> = first.values.map { line ->
            val discount = discounts.getValue(line.productId)
            SaleLineRequest(
                productId = line.productId,
                quantity = quantities.getValue(line.productId),
                unitPrice = line.unitPrice,
                discount = if (discount > Money.ZERO) {
                    LineDiscount.Amount(discount)
                } else {
                    LineDiscount.None
                },
            )
        }
    }
}
