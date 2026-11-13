package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.CashRounding
import io.github.yesidrobayo.shopledger.domain.rule.LineTax
import io.github.yesidrobayo.shopledger.domain.rule.TaxCalculator
import io.github.yesidrobayo.shopledger.domain.rule.pricing.LinePriceList
import io.github.yesidrobayo.shopledger.domain.rule.pricing.SalePriceList

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

    /** El producto ya no se vende en la unidad de la línea: se quitó su conversión de venta. */
    UNIT_UNAVAILABLE,
}

/**
 * Una línea de la venta en edición, con sus cuentas hechas igual que al confirmar.
 *
 * [unitPrice] es el precio de la línea, o el de su unidad si nadie lo cambió. [baseQuantity] es lo
 * que la línea saca del stock, en la unidad base; falta si la línea tiene un [issue]. [gross] y
 * [discount] faltan si la línea tiene un [issue]; [tax] y [total] faltan también si la venta entera
 * no se puede cobrar. [total] es lo que cobra la línea con su impuesto y su parte del recargo del
 * método.
 */
data class CartLinePreview(
    val request: SaleLineRequest,
    val unitPrice: Money,
    val baseQuantity: QuantityMilli?,
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
 * [cashRounding] es el redondeo del efectivo, con signo, si se cobra en efectivo, y [amountDue] lo
 * que se cobra: el total más ese redondeo.
 */
data class CartPreview(
    val lines: List<CartLinePreview>,
    val itemCount: Long,
    val tax: Money?,
    val total: Money?,
    val cashRounding: Money = Money.ZERO,
    val amountDue: Money? = total,
) {
    /** Si la venta tiene algo que confirmar y ninguna línea lo impide. */
    val canRegister: Boolean get() = lines.isNotEmpty() && amountDue != null
}

/**
 * Con qué se cobra la venta en edición: el modo de impuesto del negocio, la tasa de cada producto,
 * o la por defecto si no tiene, y el recargo o descuento del método elegido.
 *
 * [rates] son las tasas por id. Sin impuesto en el negocio, ninguna línea lleva impuesto. Con
 * [cashMethod], el método elegido es de efectivo, y lo que se cobra se redondea a [cashStep].
 * [priceList] es la lista de precios elegida para la venta; sin ella, la inicial.
 */
data class CartPricing(
    val taxMode: TaxMode = TaxMode.NONE,
    val rates: Map<Long, BasisPoints> = emptyMap(),
    val defaultRateId: Long? = null,
    val methodAdjustment: BasisPoints = BasisPoints.ZERO,
    val cashMethod: Boolean = false,
    val cashStep: Money = Money.ZERO,
    val priceList: SalePriceList? = null,
) {
    /** El precio de [product] en la lista de la venta, en su unidad base. */
    fun listOf(product: Product): LinePriceList {
        val list = priceList ?: return LinePriceList.default(product)
        return list.priceOf(product)
    }

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
 * Hay una línea por producto, en la unidad que elija la persona: agregar otra vez el mismo producto
 * suma a su línea, en su unidad. Las cuentas de dinero son las de [SaleLineRules], así
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
     *   reparte entre las líneas en proporción y paga impuesto en cada una;
     * - en efectivo, lo que se cobra se redondea al paso del negocio, ya con el recargo.
     *
     * Cuenta los productos como los ve la persona: cada unidad entera de una línea que se cuenta, y
     * una sola vez la línea de un producto por peso o medida, que es un producto aunque lleve 1,250
     * kg, también si se vende en gramos. [fractionUnits] son las unidades que admiten decimales. Una
     * línea en otra unidad que se cuenta cuenta en esa unidad: 2 cajas son 2 productos. [conversions] son, por producto, sus unidades de venta
     * por id de unidad, como las da el catálogo.
     */
    fun preview(
        lines: List<SaleLineRequest>,
        products: Map<Long, Product>,
        fractionUnits: Set<Long>,
        pricing: CartPricing = CartPricing(),
        conversions: Map<Long, Map<Long, ConversionDetails>> = emptyMap(),
    ): CartPreview {
        var itemCount = 0L
        val bases = lines.map { line ->
            val product = products[line.productId]
            // Un producto por peso o medida es uno, también vendido en gramos.
            val byMeasure = product == null ||
                product.details.baseUnitId in fractionUnits ||
                line.unitId in fractionUnits
            itemCount += if (byMeasure) {
                1L
            } else {
                line.quantity.milli / STEP.milli
            }
            baseOf(line, product, pricing, conversions[line.productId].orEmpty())
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
                baseQuantity = base.baseQuantity,
                gross = base.gross,
                discount = base.discount,
                tax = split?.taxAmount,
                total = split?.lineTotal,
                issue = base.issue,
            )
        }
        val total = charged?.total
        val rounding = if (pricing.cashMethod && total != null) {
            CashRounding.amount(total, pricing.cashStep)
        } else {
            Money.ZERO
        }
        val amountDue = total?.plus(rounding)?.takeIf { Limit.DOCUMENT_TOTAL.allows(it.minor) }
        return CartPreview(previews, itemCount, charged?.tax, total, rounding, amountDue)
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
        val baseQuantity: QuantityMilli? = null,
        val gross: Money? = null,
        val discount: Money? = null,
        val issue: CartLineIssue? = null,
        val base: Money = Money.ZERO,
    )

    private fun baseOf(
        line: SaleLineRequest,
        product: Product?,
        pricing: CartPricing,
        conversions: Map<Long, ConversionDetails>,
    ): LineBase {
        if (product == null) {
            return LineBase(
                line,
                line.unitPrice ?: Money.ZERO,
                BasisPoints.ZERO,
                issue = CartLineIssue.PRODUCT_UNAVAILABLE,
            )
        }
        val rate = pricing.rateOf(product)
        val unit = when (
            val result = SaleUnits.of(
                line,
                product,
                conversions,
                pricing.listOf(product),
            )
        ) {
            is SaleUnitResult.Resolved -> result.unit

            SaleUnitResult.Unavailable -> return LineBase(
                line,
                line.unitPrice ?: Money.ZERO,
                rate,
                issue = CartLineIssue.UNIT_UNAVAILABLE,
            )
        }
        // Sin precio escrito ni precio de lista que quepa, la línea pasa del tope.
        val unitPrice = line.unitPrice ?: unit.listPrice
            ?: return LineBase(line, Money.ZERO, rate, issue = CartLineIssue.AMOUNT_TOO_LARGE)
        // Como al confirmar: una cantidad que no se puede llevar a la base no se cobra.
        val baseQuantity = SaleUnits.baseQuantity(line.quantity, unit)
            ?: return LineBase(line, unitPrice, rate, issue = CartLineIssue.AMOUNT_TOO_LARGE)
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
        return LineBase(line, unitPrice, rate, baseQuantity, gross, discount, base = base)
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
