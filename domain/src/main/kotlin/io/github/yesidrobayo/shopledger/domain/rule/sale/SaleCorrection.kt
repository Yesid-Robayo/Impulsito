package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitConversion
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Lo que trae una venta nueva que corrige otra ya anulada: sus líneas con cantidad, precio y
 * descuento, el método con que se cobró, la fecha y la nota. El costo no viaja: la venta nueva
 * toma el costo de hoy al confirmarse.
 *
 * [leftOut] cuenta las líneas que no se trajeron porque su producto hoy está archivado: no se
 * puede vender, y la venta nueva lo dice. [notSoldLeftOut] cuenta aparte las que no se trajeron
 * porque su producto hoy es un insumo, que tampoco se vende.
 */
data class SaleCorrection(
    val lines: List<SaleLineRequest>,
    val paymentMethodId: Long?,
    val date: BusinessDate,
    val note: String?,
    val leftOut: Int,
    val notSoldLeftOut: Int = 0,
)

/** Arma la corrección de una venta guardada. No escribe nada. */
object SaleCorrections {
    /**
     * La corrección de [detail].
     *
     * - Cada línea trae su precio escrito y su descuento en monto, aunque el producto hoy cueste
     *   otra cosa: se corrige lo que se vendió, no se vende otra vez a precio nuevo.
     * - Cada línea viene en la unidad en que se vendió, con la cantidad escrita en esa unidad: 3
     *   cajas de 12 a 12000 la caja vuelven como 3 cajas a 12000, no como 36 unidades a 12000. El
     *   precio guardado es por una unidad entera de esa unidad, así que cantidad y precio deben ir
     *   en la misma.
     * - Una línea de un producto hoy archivado queda por fuera y se cuenta, con los extras que
     *   llevaba. Una de un producto que hoy es un insumo también, y se cuenta aparte.
     * - Un producto en dos líneas llega en una sola, con las cantidades y los descuentos sumados y
     *   el precio de la primera, porque la venta en pantalla lleva una línea por producto. Si las
     *   dos iban en la misma unidad, llega en esa unidad; si iban en unidades distintas, llega en
     *   la unidad base, con la cantidad del libro y el precio de la primera llevado a la base con
     *   su factor. Si ese precio no cabe, la línea toma el precio de hoy.
     * - Los extras llegan bajo la línea de su producto, uno por producto de extra, con el precio del
     *   primero y los descuentos sumados. Su cantidad la pone la venta en pantalla, que la hace
     *   seguir a su línea.
     * - El método es el del primer pago; con varios pagos, la venta nueva se cobra con ese.
     */
    fun of(detail: SaleDetail): SaleCorrection {
        val archived = detail.archivedProductIds
        val notSold = detail.notSoldProductIds
        val productOfLine = detail.lines.associate { it.id to it.content.productId }
        val mains = Merged()
        // Por producto principal, sus extras por producto, en el orden de la venta.
        val extras = LinkedHashMap<Long, Merged>()
        var leftOut = 0
        var notSoldLeftOut = 0
        for (line in detail.lines) {
            val content = line.content
            val parentProduct = content.parentLineId?.let { productOfLine[it] }
            when {
                content.productId in archived ||
                    (parentProduct != null && parentProduct in archived) -> leftOut++

                content.productId in notSold ||
                    (parentProduct != null && parentProduct in notSold) -> notSoldLeftOut++

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
            notSoldLeftOut = notSoldLeftOut,
        )
    }

    /**
     * Las líneas de cada producto juntas: la primera, lo que suman todas en la unidad en que se
     * vendieron y en la unidad base, y si alguna iba en otra unidad que la primera.
     */
    private class Merged {
        private val first = LinkedHashMap<Long, NewSaleLine>()
        private val entered = HashMap<Long, QuantityMilli>()
        private val base = HashMap<Long, QuantityMilli>()
        private val discounts = HashMap<Long, Money>()
        private val mixed = HashSet<Long>()

        fun add(line: NewSaleLine) {
            val product = line.productId
            val head = first.getOrPut(product) { line }
            if (head.unitId != line.unitId) mixed += product
            entered[product] = (entered[product] ?: QuantityMilli.ZERO) + line.enteredQuantity
            base[product] = (base[product] ?: QuantityMilli.ZERO) + line.quantity
            discounts[product] = (discounts[product] ?: Money.ZERO) + line.lineDiscount
        }

        fun lines(): List<SaleLineRequest> = first.values.map { line ->
            val product = line.productId
            val discount = discounts.getValue(product)
            val request = SaleLineRequest(
                productId = product,
                quantity = entered.getValue(product),
                unitPrice = line.unitPrice,
                discount = if (discount > Money.ZERO) {
                    LineDiscount.Amount(discount)
                } else {
                    LineDiscount.None
                },
                unitId = line.unitId,
            )
            if (product in mixed) {
                // Unidades distintas no se suman: todo pasa a la unidad base, que sí se suma.
                request.copy(
                    quantity = base.getValue(product),
                    unitPrice = basePriceOf(line),
                    unitId = null,
                )
            } else {
                request
            }
        }

        /** El precio de [line] por una unidad base, o `null` si pasa del tope de un precio. */
        private fun basePriceOf(line: NewSaleLine): Money? = mulDivRound(
            line.unitPrice.minor,
            UnitConversion.BASE_UNIT_FACTOR_MILLI,
            line.unitFactor.milli,
            Limit.UNIT_MONEY,
        ).valueOrNull()?.let(::Money)
    }
}
