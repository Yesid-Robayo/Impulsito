package io.github.yesidrobayo.shopledger.domain.rule.returns

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Lo que vale una línea de una devolución a proveedor: [cost] es lo que sale del valor del stock,
 * [unitCost] el costo por unidad base que congela la línea, y [lineTotal] lo que el proveedor
 * devuelve o deja a favor por ella.
 */
data class SupplierReturnLineAmounts(val cost: Money, val unitCost: Money, val lineTotal: Money)

/**
 * Una línea que se va a valorar: [quantity] milésimas de [productId], de la línea de compra
 * [purchaseLine] si la tiene, que tiene que ser de ese producto.
 */
data class SupplierReturnPricingLine(
    val productId: Long,
    val purchaseLine: PurchaseLine?,
    val quantity: QuantityMilli,
) {
    init {
        require(purchaseLine == null || purchaseLine.content.productId == productId) {
            "La línea de compra es del mismo producto"
        }
    }
}

/** Cómo quedó cada línea de una devolución al valorarla, en el orden pedido. */
data class SupplierReturnPricing(val lines: List<SupplierReturnLinePricing>) {
    /**
     * El total de la devolución, o `null` si alguna línea no tiene valor o la suma pasa del máximo
     * de un documento.
     */
    val total: Money?
        get() {
            var total = Money.ZERO
            for (line in lines) {
                val amount = when (line) {
                    is SupplierReturnLinePricing.Priced -> line.amounts.lineTotal

                    is SupplierReturnLinePricing.AboveRemaining,
                    SupplierReturnLinePricing.InvalidQuantity,
                    SupplierReturnLinePricing.TooLarge,
                    -> return null
                }
                // Cada línea cabe en un documento, así que la suma revisada paso a paso no desborda.
                total += amount
                if (!Limit.DOCUMENT_TOTAL.allows(total.minor)) return null
            }
            return total
        }
}

/** Cómo quedó una línea al valorarla. */
sealed interface SupplierReturnLinePricing {
    /** Vale [amounts], y deja el costo de su producto de [before] en [after]. */
    data class Priced(
        val amounts: SupplierReturnLineAmounts,
        val before: CostState,
        val after: CostState,
    ) : SupplierReturnLinePricing

    /** Pide más de lo que queda de su línea de compra: a lo sumo [remaining] milésimas. */
    data class AboveRemaining(val remaining: QuantityMilli) : SupplierReturnLinePricing

    /** La cantidad no es positiva. */
    data object InvalidQuantity : SupplierReturnLinePricing

    /** Sus cifras no caben en sus topes. Solo sale con cifras absurdas. */
    data object TooLarge : SupplierReturnLinePricing
}

/**
 * Las cuentas de una devolución a proveedor (`devolucionAProveedor`): cuánto sale del stock, a qué
 * costo, cuánto devuelve el proveedor y cuánto queda por devolver de cada línea de compra.
 *
 * - **Con línea de compra, sale al costo de esa línea**, no al promedio: lo devuelto vuelve a lo que
 *   costó, y el promedio de lo que queda sale del valor que queda. Por eso una devolución sí cambia
 *   el costo promedio, a diferencia de una venta.
 * - **Las partes se reparten como en una devolución de cliente**, con [ReturnShares]: devolver una
 *   línea de 3 unidades por 10000 de a una da 3333, 3334 y 3333, y lo que guardan las devoluciones
 *   activas nunca pasa de la línea, tampoco después de anular una de en medio.
 * - **El proveedor devuelve lo que se le pagó por esa parte**, impuesto incluido. El stock, en
 *   cambio, sale a su costo: sin el impuesto, si quien compra lo recupera. Sin impuesto los dos son
 *   iguales.
 * - **Sin línea de compra, sale con `costoDeSalida`**, el mismo de una venta, y el proveedor devuelve
 *   ese costo: no hay otro precio conocido.
 */
object SupplierReturnRules {
    /**
     * Lo que trae una línea de compra para repartirla entre sus devoluciones: la cantidad, lo que se
     * pagó por ella, impuesto incluido, y su costo. La devolución a proveedor no separa el impuesto:
     * el proveedor devuelve lo pagado, así que va entero como neto.
     */
    fun boughtOf(line: NewPurchaseLine): LineAmounts =
        LineAmounts(line.quantity, line.lineTotal, Money.ZERO, line.costAmount)

    /**
     * Lo que vale [share], la parte de [line] que se devuelve: sale del stock a su parte del costo,
     * congela el costo por unidad base de la fila de compra y el proveedor devuelve su parte de lo
     * pagado.
     */
    fun fromPurchaseLine(
        line: NewPurchaseLine,
        share: ReturnShare,
    ): Checked<SupplierReturnLineAmounts> {
        // El mismo costo por unidad base que congeló la fila de compra del libro.
        val unitCost = mulDivRound(line.costAmount.minor, Scale.MILLI, line.quantity.milli)
            .valueOrNull() ?: return Checked.Overflow
        return checkedAmounts(SupplierReturnLineAmounts(share.cost, Money(unitCost), share.total))
    }

    /**
     * Lo que vale devolver [quantity] milésimas sin compra de referencia, con el stock en [state]:
     * sale con `costoDeSalida`, congela el costo promedio y el proveedor devuelve ese mismo costo.
     */
    fun loose(state: CostState, quantity: QuantityMilli): Checked<SupplierReturnLineAmounts> {
        requirePositive(quantity)
        val cost = WeightedAverageCost.exitCost(state, quantity).valueOrNull()
            ?: return Checked.Overflow
        return checkedAmounts(SupplierReturnLineAmounts(cost, state.averageCost, cost))
    }

    /**
     * Lo que vale cada línea de una devolución y cómo deja el costo de su producto, en orden. Dos
     * líneas del mismo producto se encadenan: la segunda parte del costo que dejó la primera. Dos
     * de la misma línea de compra también: la segunda cuenta lo que devolvió la primera.
     *
     * Una línea que no se puede valorar no mueve nada, y las siguientes se valoran igual: así la
     * pantalla marca cada una, y el caso de uso rechaza la primera. [stateOf] es el costo de cada
     * producto antes de la devolución, y [takenOf] lo que ya se llevaron de cada línea de compra
     * sus devoluciones activas. Es el mismo cálculo en la pantalla y al registrar.
     */
    fun price(
        lines: List<SupplierReturnPricingLine>,
        stateOf: (productId: Long) -> CostState,
        takenOf: (purchaseLineId: Long) -> LineAmounts,
    ): SupplierReturnPricing {
        val states = HashMap<Long, CostState>()
        val taken = HashMap<Long, LineAmounts>()
        val priced = lines.map { line ->
            val before = states.getOrPut(line.productId) { stateOf(line.productId) }
            val purchaseLine = line.purchaseLine
            val amounts = if (purchaseLine == null) {
                if (line.quantity.milli <= 0L) return@map SupplierReturnLinePricing.InvalidQuantity
                loose(before, line.quantity)
            } else {
                val already = taken.getOrPut(purchaseLine.id) { takenOf(purchaseLine.id) }
                val bought = boughtOf(purchaseLine.content)
                val share = when (val check = ReturnShares.of(bought, already, line.quantity)) {
                    is ReturnShareCheck.Share -> check.share

                    ReturnShareCheck.NotPositive ->
                        return@map SupplierReturnLinePricing.InvalidQuantity

                    is ReturnShareCheck.AboveRemaining ->
                        return@map SupplierReturnLinePricing.AboveRemaining(check.remaining)
                }
                taken[purchaseLine.id] = LineAmounts(
                    quantity = already.quantity + line.quantity,
                    net = already.net + share.net,
                    tax = already.tax + share.tax,
                    cost = already.cost + share.cost,
                )
                fromPurchaseLine(purchaseLine.content, share)
            }.valueOrNull() ?: return@map SupplierReturnLinePricing.TooLarge
            val after = WeightedAverageCost.supplierReturn(before, line.quantity, amounts.cost)
                .valueOrNull() ?: return@map SupplierReturnLinePricing.TooLarge
            states[line.productId] = after
            SupplierReturnLinePricing.Priced(amounts, before, after)
        }
        return SupplierReturnPricing(priced)
    }

    /** Rechaza con su tope un costo unitario o un total que no caben en una línea. */
    private fun checkedAmounts(
        amounts: SupplierReturnLineAmounts,
    ): Checked<SupplierReturnLineAmounts> = when {
        !Limit.UNIT_MONEY.allows(amounts.unitCost.minor) -> Checked.AboveLimit(Limit.UNIT_MONEY)

        !Limit.DOCUMENT_TOTAL.allows(amounts.lineTotal.minor) ->
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL)

        !Limit.DOCUMENT_TOTAL.allows(
            amounts.cost.minor,
        ) -> Checked.AboveLimit(Limit.DOCUMENT_TOTAL)

        else -> Checked.Value(amounts)
    }

    private fun requirePositive(quantity: QuantityMilli) {
        require(quantity.milli > 0L) { "Se devuelve una cantidad positiva" }
    }
}
