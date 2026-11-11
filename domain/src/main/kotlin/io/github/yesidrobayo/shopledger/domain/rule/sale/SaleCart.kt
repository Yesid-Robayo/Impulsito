package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.LineTax
import io.github.yesidrobayo.shopledger.domain.rule.TaxCalculator

/** Lo que resulta de agregar un producto o de cambiar la cantidad de una línea. */
sealed interface CartChange {
    /** Las líneas nuevas de la venta. */
    data class Changed(val lines: List<SaleLineRequest>) : CartChange

    /** La venta ya tiene el máximo de líneas: el producto no se agrega. */
    data object LinesFull : CartChange

    /** La cantidad pasaría del tope de una línea, o no sería positiva: la línea no cambia. */
    data object InvalidQuantity : CartChange
}

/** Por qué una línea de la venta en edición no se puede confirmar todavía. */
enum class CartLineIssue {
    /** El producto ya no está entre los que se venden: se archivó o se eliminó. */
    PRODUCT_UNAVAILABLE,

    /** El descuento pasa del bruto de la línea o de 100 %. */
    INVALID_DISCOUNT,

    /** El bruto de la línea pasa del tope de una línea. */
    AMOUNT_TOO_LARGE,
}

/**
 * Una línea de la venta en edición, con sus cuentas hechas igual que al confirmar.
 *
 * [unitPrice] es el precio de la línea, o el del producto si nadie lo cambió. [gross] y
 * [discount] faltan si la línea tiene un [issue]; [tax] y [total] faltan también si la venta entera
 * no se puede cobrar. [total] es lo que cobra la línea con su impuesto y su parte del recargo del
 * método.
 */
data class CartLinePreview(
    val request: SaleLineRequest,
    val unitPrice: Money,
    val gross: Money?,
    val discount: Money?,
    val tax: Money?,
    val total: Money?,
    val issue: CartLineIssue?,
)

/**
 * La venta en edición con sus cuentas: cada línea, cuántos productos lleva, su impuesto y su total.
 *
 * [tax] y [total] faltan si alguna línea tiene un problema o si la venta pasa del tope de un
 * documento: un total así es un error de digitación, y la venta no se confirma hasta corregirlo.
 */
data class CartPreview(
    val lines: List<CartLinePreview>,
    val itemCount: Long,
    val tax: Money?,
    val total: Money?,
) {
    /** Si la venta tiene algo que confirmar y ninguna línea lo impide. */
    val canRegister: Boolean get() = lines.isNotEmpty() && total != null
}

/**
 * Con qué se cobra la venta en edición: el modo de impuesto del negocio, la tasa de cada producto,
 * o la por defecto si no tiene, y el recargo o descuento del método elegido.
 *
 * [rates] son las tasas por id. Sin impuesto en el negocio, ninguna línea lleva impuesto.
 */
data class CartPricing(
    val taxMode: TaxMode = TaxMode.NONE,
    val rates: Map<Long, BasisPoints> = emptyMap(),
    val defaultRateId: Long? = null,
    val methodAdjustment: BasisPoints = BasisPoints.ZERO,
) {
    /** La tasa con que paga impuesto [product]: la suya, la por defecto o ninguna. */
    fun rateOf(product: Product): BasisPoints {
        if (taxMode == TaxMode.NONE) return BasisPoints.ZERO
        val id = product.details.taxRateId ?: defaultRateId ?: return BasisPoints.ZERO
        return rates[id] ?: BasisPoints.ZERO
    }
}

/**
 * Las reglas de la venta en edición, antes de confirmar: agregar, sumar y quitar, y las mismas
 * cuentas que hará la venta al registrarse, para mostrar el total sin escribir nada.
 *
 * Mientras la venta solo use la unidad base de cada producto, hay una línea por producto: agregar
 * otra vez el mismo producto suma a su línea. Las cuentas de dinero son las de [SaleLineRules], así
 * que el total que se ve es el que se guarda. Nada aquí bloquea por stock: vender de más solo se
 * avisa.
 */
object SaleCart {
    /** Lo que suma o resta un toque en una unidad que se cuenta entera. */
    val STEP: QuantityMilli = QuantityMilli.ofUnits(1L)

    /** La cantidad de [productId] en la venta, o cero si no está. */
    fun quantityOf(lines: List<SaleLineRequest>, productId: Long): QuantityMilli =
        lines.firstOrNull { it.productId == productId }?.quantity ?: QuantityMilli.ZERO

    /**
     * Agrega [quantity] de [productId]: suma a su línea si ya está, o agrega una línea al final si
     * todavía cabe otra.
     */
    fun add(lines: List<SaleLineRequest>, productId: Long, quantity: QuantityMilli): CartChange {
        val index = lines.indexOfFirst { it.productId == productId }
        if (index < 0) {
            if (lines.size >= Limit.DOCUMENT_LINES.max) return CartChange.LinesFull
            if (!validQuantity(quantity)) return CartChange.InvalidQuantity
            return CartChange.Changed(lines + SaleLineRequest(productId, quantity))
        }
        return setQuantity(lines, productId, lines[index].quantity + quantity)
    }

    /** Cambia la cantidad de la línea de [productId]. Una línea que no está no cambia nada. */
    fun setQuantity(
        lines: List<SaleLineRequest>,
        productId: Long,
        quantity: QuantityMilli,
    ): CartChange {
        if (!validQuantity(quantity)) return CartChange.InvalidQuantity
        return CartChange.Changed(update(lines, productId) { it.copy(quantity = quantity) })
    }

    /**
     * Resta una unidad a la línea de [productId]. Con una unidad o menos, quita la línea: el botón
     * menos ya mostraba la papelera.
     */
    fun decrement(lines: List<SaleLineRequest>, productId: Long): List<SaleLineRequest> {
        val current = quantityOf(lines, productId)
        if (current <= STEP) return remove(lines, productId)
        return update(lines, productId) { it.copy(quantity = current - STEP) }
    }

    /** Si quitar una unidad de una línea con [quantity] la deja sin nada. */
    fun decrementRemoves(quantity: QuantityMilli): Boolean = quantity <= STEP

    /** Si a una línea con [quantity] todavía se le puede sumar una unidad sin pasar del tope. */
    fun canIncrement(quantity: QuantityMilli): Boolean =
        Limit.LINE_QUANTITY_MILLI.allows(quantity.milli + STEP.milli)

    fun remove(lines: List<SaleLineRequest>, productId: Long): List<SaleLineRequest> =
        lines.filterNot { it.productId == productId }

    /** Reemplaza la línea de [productId] por lo que devuelva [change]. */
    fun update(
        lines: List<SaleLineRequest>,
        productId: Long,
        change: (SaleLineRequest) -> SaleLineRequest,
    ): List<SaleLineRequest> = lines.map { if (it.productId == productId) change(it) else it }

    /**
     * Cuánto quedaría de [product] si se vende [inSale], solo si queda en negativo: es el aviso de
     * la tarjeta, que nunca impide vender. Un producto sin stock propio nunca avisa.
     */
    fun oversell(product: Product, inSale: QuantityMilli): QuantityMilli? {
        if (product.details.stockMode != ProductStockMode.TRACKED) return null
        val left = product.onHand - inSale
        return left.takeIf { it < QuantityMilli.ZERO }
    }

    /**
     * Las cuentas de la venta en edición, con [products] por id tal como están ahora, igual que las
     * hará la venta al confirmar:
     *
     * - cada línea cobra su bruto menos su descuento, y sobre eso se separa o se suma su impuesto,
     *   línea por línea, con [pricing];
     * - el recargo o descuento del método elegido se calcula sobre lo que se cobra antes de él, se
     *   reparte entre las líneas en proporción y paga impuesto en cada una.
     *
     * Cuenta los productos como los ve la persona: cada unidad entera de una línea que se cuenta, y
     * una sola vez la línea de un producto por peso o medida, que es un producto aunque lleve 1,250
     * kg. [fractionUnits] son las unidades que admiten decimales.
     */
    fun preview(
        lines: List<SaleLineRequest>,
        products: Map<Long, Product>,
        fractionUnits: Set<Long>,
        pricing: CartPricing = CartPricing(),
    ): CartPreview {
        var itemCount = 0L
        val bases = lines.map { line ->
            val product = products[line.productId]
            itemCount += if (product == null || product.details.baseUnitId in fractionUnits) {
                1L
            } else {
                line.quantity.milli / STEP.milli
            }
            baseOf(line, product, pricing)
        }
        val charged = when {
            bases.isEmpty() -> Charged(emptyList(), Money.ZERO, Money.ZERO)
            bases.all { it.issue == null } -> charged(bases, pricing)
            else -> null
        }
        val previews = bases.mapIndexed { index, base ->
            val split = charged?.let { it.splits[index] }
            CartLinePreview(
                request = base.request,
                unitPrice = base.unitPrice,
                gross = base.gross,
                discount = base.discount,
                tax = split?.taxAmount,
                total = split?.lineTotal,
                issue = base.issue,
            )
        }
        return CartPreview(previews, itemCount, charged?.tax, charged?.total)
    }

    /**
     * Lo que cobra una línea antes del recargo y del impuesto, o su problema. [base] es el bruto
     * menos el descuento: sobre eso se reparte el ajuste y se separa el impuesto. Una línea con
     * problema no tiene base, y la venta no se cobra.
     */
    private class LineBase(
        val request: SaleLineRequest,
        val unitPrice: Money,
        val rate: BasisPoints,
        val gross: Money? = null,
        val discount: Money? = null,
        val issue: CartLineIssue? = null,
        val base: Money = Money.ZERO,
    )

    private fun baseOf(line: SaleLineRequest, product: Product?, pricing: CartPricing): LineBase {
        val unitPrice = line.unitPrice ?: product?.salePrice ?: Money.ZERO
        if (product == null) {
            return LineBase(
                line,
                unitPrice,
                BasisPoints.ZERO,
                issue = CartLineIssue.PRODUCT_UNAVAILABLE,
            )
        }
        val rate = pricing.rateOf(product)
        val gross = when (val result = SaleLineRules.gross(line.quantity, unitPrice)) {
            is Checked.Value -> result.value

            Checked.Overflow, is Checked.AboveLimit ->
                return LineBase(line, unitPrice, rate, issue = CartLineIssue.AMOUNT_TOO_LARGE)
        }
        val discount = SaleLineRules.discountOf(gross, line.discount)
            ?: return LineBase(line, unitPrice, rate, issue = CartLineIssue.INVALID_DISCOUNT)
        // Con el impuesto sumado, una línea al tope deja de caber, como al confirmar.
        val base = gross - discount
        if (TaxCalculator.line(base, pricing.taxMode, rate) !is Checked.Value) {
            return LineBase(line, unitPrice, rate, issue = CartLineIssue.AMOUNT_TOO_LARGE)
        }
        return LineBase(line, unitPrice, rate, gross, discount, base = base)
    }

    /** Las cuentas de cada línea y de la venta, con el ajuste ya repartido. */
    private class Charged(val splits: List<LineTax>, val tax: Money, val total: Money)

    /**
     * La venta cobrada con el recargo del método, o `null` si no se puede cobrar: un total sobre el
     * tope, o un descuento del método que no cabe.
     */
    private fun charged(bases: List<LineBase>, pricing: CartPricing): Charged? {
        val unadjusted = chargedWith(bases, pricing, Money.ZERO) ?: return null
        if (pricing.methodAdjustment == BasisPoints.ZERO) return unadjusted
        // La venta común tiene un solo pago, que cubre todo lo que se cobra antes del recargo.
        val settlement = PaymentSurcharges.settle(
            amounts = listOf(unadjusted.total),
            rates = listOf(pricing.methodAdjustment),
            totalWith = { adjustment -> chargedWith(bases, pricing, adjustment)?.total },
        )
        // Con un solo pago, lo que cubre nunca baja de cero: solo puede no caber.
        val settled = settlement as? SurchargeSettlement.Settled ?: return null
        return chargedWith(bases, pricing, settled.adjustment)
    }

    /**
     * Las líneas con [adjustment] repartido en proporción a lo que cobran, y su impuesto separado
     * con su parte, o `null` si el ajuste no cabe o algún total pasa del tope de un documento.
     */
    private fun chargedWith(
        bases: List<LineBase>,
        pricing: CartPricing,
        adjustment: Money,
    ): Charged? {
        val split = OrderAdjustmentShares.split(adjustment, bases.map { it.base })
        val shares = when (split) {
            is AdjustmentSplit.Shares -> split.shares
            AdjustmentSplit.DiscountAboveBases -> return null
        }
        var net = Money.ZERO
        var tax = Money.ZERO
        var total = Money.ZERO
        val splits = bases.zip(shares) { line, share ->
            val checked = TaxCalculator.line(line.base + share, pricing.taxMode, line.rate)
            if (checked !is Checked.Value) return null
            val split = checked.value
            net += split.netAmount
            tax += split.taxAmount
            total += split.lineTotal
            if (listOf(net, tax, total).any { !Limit.DOCUMENT_TOTAL.allows(it.minor) }) return null
            split
        }
        return Charged(splits, tax, total)
    }

    private fun validQuantity(quantity: QuantityMilli): Boolean =
        quantity > QuantityMilli.ZERO && Limit.LINE_QUANTITY_MILLI.allows(quantity.milli)
}
