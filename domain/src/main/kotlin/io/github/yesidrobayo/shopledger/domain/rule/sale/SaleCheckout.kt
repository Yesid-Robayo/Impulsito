package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.CashRounding
import io.github.yesidrobayo.shopledger.domain.rule.TaxCalculator

/**
 * Un pago tal como está escrito en el cobro, con lo que su método aporta a las cuentas.
 *
 * [amount] es lo que la persona escribió para este pago, antes del recargo de su método: los pagos
 * escritos suman lo que se cobra antes de esos recargos. [feeAmount] es la comisión escrita en esta
 * venta, o `null` para usar la del método, con [feeRate] y [feeFixed]. [tenderedAmount] es lo
 * recibido en efectivo, si se escribió.
 */
data class CheckoutPaymentInput(
    val methodId: Long,
    val kind: PaymentMethodKind,
    val amount: Money,
    val priceAdjustment: BasisPoints = BasisPoints.ZERO,
    val feeRate: BasisPoints = BasisPoints.ZERO,
    val feeFixed: Money = Money.ZERO,
    val feeAmount: Money? = null,
    val tenderedAmount: Money? = null,
)

/**
 * Un pago con sus cuentas hechas, igual que al confirmar: [surcharge] es el recargo de su método,
 * con signo, [rounding] el redondeo del efectivo, con signo, [amount] lo que termina cubriendo con
 * los dos, y [fee] su comisión. [change] es lo recibido menos [amount], si se escribió lo recibido.
 */
data class CheckoutPaymentPreview(
    val methodId: Long,
    val kind: PaymentMethodKind,
    val written: Money,
    val surcharge: Money,
    val amount: Money,
    val fee: Money,
    val rounding: Money = Money.ZERO,
    val tendered: Money? = null,
) {
    /** El cambio que se devuelve, o `null` si no se escribió lo recibido. */
    val change: Money? get() = tendered?.let { it - amount }

    /** Si lo recibido no alcanza para lo que cubre el pago: no se confirma. */
    val tenderedBelowAmount: Boolean get() = tendered != null && tendered < amount

    /** Lo que de verdad llega por este pago. Negativo si la comisión pasa del pago. */
    val netReceived: Money get() = amount - fee

    /** Si la comisión pasa del pago: se avisa en la fila, sin impedir el cobro. */
    val feeAboveAmount: Boolean get() = fee > amount
}

/** Si lo escrito en los pagos cubre lo que se cobra. */
sealed interface CheckoutBalance {
    /** Falta escribir [amount] en los pagos. */
    data class Missing(val amount: Money) : CheckoutBalance

    /** Los pagos pasan [amount] de lo que se cobra. */
    data class Excess(val amount: Money) : CheckoutBalance

    /** Los pagos suman exactamente lo que se cobra. */
    data object Complete : CheckoutBalance
}

/** Por qué el cobro no se puede confirmar todavía, aparte de que los pagos no sumen. */
sealed interface CheckoutProblem {
    /** Una línea de la venta tiene un problema: se corrige en la venta. */
    data object LineIssue : CheckoutProblem

    /** El descuento escrito para toda la venta supera lo que suman sus líneas. */
    data object DiscountAboveSale : CheckoutProblem

    /**
     * El descuento de los puntos canjeados, sumado al ajuste escrito, supera lo que suman las
     * líneas.
     */
    data object PointsAboveSale : CheckoutProblem

    /** Algún total, recargo o comisión pasa del tope de un documento. */
    data object TooLarge : CheckoutProblem

    /**
     * El pago número [index] quedaría negativo: el descuento de su método, con el impuesto que deja
     * de cobrarse, baja el total más que lo que el pago cubría.
     */
    data class PaymentBelowZero(val index: Int) : CheckoutProblem

    /** Lo recibido en el pago número [index] no alcanza para lo que cubre en efectivo. */
    data class TenderedBelowAmount(val index: Int) : CheckoutProblem
}

/**
 * Las cuentas del cobro, las mismas que hará la venta al confirmar.
 *
 * - [subtotal] es lo que cobran las líneas tras sus descuentos, antes de cualquier ajuste y, con el
 *   impuesto añadido, antes del impuesto.
 * - [writtenAdjustment] es el descuento o recargo escrito para toda la venta, con signo.
 * - [pointsDiscount] es el descuento de los puntos canjeados, positivo. Entra en el ajuste de la
 *   venta junto con el escrito, nunca como un pago: los puntos no son dinero.
 * - [taxTotal] y [total] son el impuesto y el total de la venta con todos los ajustes: el escrito y
 *   los recargos de los métodos.
 * - [due] es lo que deben sumar los pagos escritos: el total con el ajuste escrito, más la propina,
 *   antes de los recargos y del redondeo. [paid] es lo que suman.
 * - [cashRounding] es la suma de los redondeos del efectivo, con signo, y [tip] la propina.
 * - [amountDue] es lo que de verdad se cobra: el total con los recargos más el redondeo. Con los
 *   pagos completos, es la suma de lo que cubre cada pago.
 *
 * Con un [problem], faltan las cifras que dependen de él: el cobro no se confirma hasta corregirlo.
 */
data class CheckoutPreview(
    val subtotal: Money?,
    val writtenAdjustment: Money,
    val taxTotal: Money?,
    val total: Money?,
    val due: Money?,
    val amountDue: Money?,
    val paid: Money,
    val payments: List<CheckoutPaymentPreview>,
    val problem: CheckoutProblem?,
    val cashRounding: Money = Money.ZERO,
    val tip: Money = Money.ZERO,
    val pointsDiscount: Money = Money.ZERO,
) {
    /** Si lo escrito cubre lo que se cobra, o `null` si no hay cómo saber cuánto se cobra. */
    val balance: CheckoutBalance?
        get() {
            val target = due ?: return null
            return when {
                paid < target -> CheckoutBalance.Missing(target - paid)
                paid > target -> CheckoutBalance.Excess(paid - target)
                else -> CheckoutBalance.Complete
            }
        }

    /** Si el cobro se puede confirmar: sin problemas y con los pagos completos. */
    val canRegister: Boolean get() = problem == null && balance == CheckoutBalance.Complete

    /** Los recargos o descuentos de cada método, con signo, sumados. */
    val methodAdjustment: Money get() = payments.fold(Money.ZERO) { sum, it -> sum + it.surcharge }
}

/**
 * Las cuentas de la pantalla de cobro: el ajuste escrito para toda la venta, varios pagos con el
 * recargo y la comisión de su método, y lo que falta o sobra. Repite paso a paso lo que hace la
 * venta al confirmarse, así que lo que se ve es lo que se guarda.
 */
object SaleCheckout {
    /**
     * El cobro de [lines] con [adjustment] escrito para toda la venta y [payments] en el orden en
     * que se escribieron. [pricing] da el impuesto; su recargo del método no se usa, porque cada
     * pago trae el suyo. Cada pago en efectivo se redondea a [cashStep], ya con su recargo, y cero
     * es sin redondeo. [conversions] son, por producto, sus unidades de venta: una línea por caja
     * cobra el precio de la caja. [tip] es la propina: los pagos la cubren, cada uno con su parte,
     * y su recargo se calcula sin ella. [pointsDiscount] es el descuento de los puntos canjeados,
     * que se suma al ajuste escrito como un descuento más.
     */
    fun preview(
        lines: List<SaleLineRequest>,
        products: Map<Long, Product>,
        pricing: CartPricing,
        adjustment: Money,
        payments: List<CheckoutPaymentInput>,
        cashStep: Money = Money.ZERO,
        conversions: Map<Long, Map<Long, ConversionDetails>> = emptyMap(),
        tip: Money = Money.ZERO,
        pointsDiscount: Money = Money.ZERO,
    ): CheckoutPreview {
        val paid = payments.fold(Money.ZERO) { sum, it -> sum + it.amount }
        val empty = CheckoutPreview(
            subtotal = null,
            writtenAdjustment = adjustment,
            pointsDiscount = pointsDiscount,
            taxTotal = null,
            total = null,
            due = null,
            amountDue = null,
            paid = paid,
            payments = emptyList(),
            problem = null,
            tip = tip,
        )
        val bases = lines.map { line ->
            baseOf(line, products, pricing, conversions[line.productId].orEmpty())
                ?: return empty.copy(problem = CheckoutProblem.LineIssue)
        }
        // Cada base cabe en el tope de un documento, y hay pocas líneas: la suma cabe en Long.
        val subtotal = bases.fold(Money.ZERO) { sum, it -> sum + it.amount }
        val withSubtotal = empty.copy(subtotal = subtotal)
        // Se compara sin negar el descuento: negar el menor Long no cabe.
        if (adjustment.minor < -Limit.DOCUMENT_TOTAL.max || adjustment.minor < -subtotal.minor) {
            return withSubtotal.copy(problem = CheckoutProblem.DiscountAboveSale)
        }
        // Como al confirmar: el descuento de los puntos se suma al ajuste escrito. Los dos caben en
        // el tope de un documento, así que la resta cabe en Long.
        if (pointsDiscount < Money.ZERO || !Limit.DOCUMENT_TOTAL.allows(pointsDiscount.minor)) {
            return withSubtotal.copy(problem = CheckoutProblem.PointsAboveSale)
        }
        val combined = adjustment - pointsDiscount
        if (combined.minor < -subtotal.minor) {
            return withSubtotal.copy(problem = CheckoutProblem.PointsAboveSale)
        }
        val due = totalWith(bases, pricing.taxMode, combined)
            ?: return withSubtotal.copy(problem = CheckoutProblem.TooLarge)
        // Las dos sumas caben en Long: cada una está dentro del tope de un documento.
        val dueWithTip = due.total + tip
        if (!Limit.DOCUMENT_TOTAL.allows(dueWithTip.minor)) {
            return withSubtotal.copy(problem = CheckoutProblem.TooLarge)
        }
        val withDue = withSubtotal.copy(due = dueWithTip, total = due.total, taxTotal = due.tax)
        if (payments.any {
                it.amount < Money.ZERO || !Limit.DOCUMENT_TOTAL.allows(it.amount.minor)
            }
        ) {
            return withDue.copy(problem = CheckoutProblem.TooLarge)
        }
        // El último total que se prueba es el de todos los recargos juntos: es el que se cobra.
        var charged = due
        val tips = PaymentTips.shares(tip, payments.map { it.amount })
        val settlement = PaymentSurcharges.settle(
            amounts = payments.zip(tips) { payment, share -> payment.amount - share },
            rates = payments.map { it.priceAdjustment },
            totalWith = { surcharge ->
                totalWith(bases, pricing.taxMode, combined + surcharge)
                    ?.also { charged = it }
                    ?.total
            },
        )
        val covered = when (settlement) {
            is SurchargeSettlement.Settled -> settlement.amounts

            SurchargeSettlement.NotAllowed -> return withDue.copy(
                problem = CheckoutProblem.TooLarge,
            )

            is SurchargeSettlement.BelowZero ->
                return withDue.copy(problem = CheckoutProblem.PaymentBelowZero(settlement.index))
        }
        val previews = ArrayList<CheckoutPaymentPreview>(payments.size)
        for ((index, payment) in payments.withIndex()) {
            val withTip = covered[index] + tips[index]
            val rounding = if (payment.kind == PaymentMethodKind.CASH) {
                CashRounding.amount(withTip, cashStep)
            } else {
                Money.ZERO
            }
            val amount = withTip + rounding
            val fee = feeOf(payment, amount)
                ?: return withDue.copy(problem = CheckoutProblem.TooLarge)
            previews += CheckoutPaymentPreview(
                methodId = payment.methodId,
                kind = payment.kind,
                written = payment.amount,
                surcharge = PaymentSurcharges.surchargeOf(
                    payment.amount - tips[index],
                    payment.priceAdjustment,
                ),
                amount = amount,
                fee = fee,
                rounding = rounding,
                tendered = payment.tenderedAmount,
            )
        }
        // Lo que se cobra es el total con todos los recargos y el redondeo, aunque lo escrito
        // todavía no sume: con los pagos completos, es también la suma de lo que cubre cada uno.
        val rounding = previews.fold(Money.ZERO) { sum, it -> sum + it.rounding }
        val amountDue = charged.total + tip + rounding
        if (!Limit.DOCUMENT_TOTAL.allows(amountDue.minor)) {
            return withDue.copy(problem = CheckoutProblem.TooLarge)
        }
        val short = previews.indexOfFirst { it.tenderedBelowAmount }
        return withDue.copy(
            taxTotal = charged.tax,
            total = charged.total,
            amountDue = amountDue,
            payments = previews,
            cashRounding = rounding,
            problem = if (short >= 0) CheckoutProblem.TenderedBelowAmount(short) else null,
        )
    }

    /**
     * La comisión de un pago que cubre [amount], o `null` si pasa del tope. Un pago de cero no paga
     * comisión; una escrita vale tal cual.
     */
    private fun feeOf(payment: CheckoutPaymentInput, amount: Money): Money? = when {
        amount == Money.ZERO -> Money.ZERO

        payment.feeAmount != null -> payment.feeAmount

        else -> (
            PaymentFees.feeOf(
                amount,
                payment.feeRate,
                payment.feeFixed,
            ) as? Checked.Value
            )?.value
    }

    /**
     * Lo que cobra [line] antes de cualquier ajuste, su bruto menos su descuento, como al confirmar,
     * o `null` si la línea no se puede cobrar: su producto o su unidad ya no se venden, su bruto pasa
     * del tope o su descuento no cabe.
     */
    private fun baseOf(
        line: SaleLineRequest,
        products: Map<Long, Product>,
        pricing: CartPricing,
        conversions: Map<Long, ConversionDetails>,
    ): Base? {
        val product = products[line.productId] ?: return null
        val unit = when (
            val result = SaleUnits.of(
                line,
                product,
                conversions,
                pricing.listOf(product),
            )
        ) {
            is SaleUnitResult.Resolved -> result.unit
            SaleUnitResult.Unavailable -> return null
        }
        // Como al confirmar: una cantidad que no se puede llevar a la base no se cobra.
        SaleUnits.baseQuantity(line.quantity, unit) ?: return null
        val unitPrice = line.unitPrice ?: unit.listPrice ?: return null
        val gross = SaleLineRules.gross(line.quantity, unitPrice) as? Checked.Value ?: return null
        val discount = SaleLineRules.discountOf(gross.value, line.discount) ?: return null
        return Base(gross.value - discount, pricing.rateOf(product))
    }

    /** Lo que cobra una línea antes de cualquier ajuste, y la tasa con que paga impuesto. */
    private class Base(val amount: Money, val rate: BasisPoints)

    private class Totals(val tax: Money, val total: Money)

    /**
     * El impuesto y el total con [adjustment] repartido entre las líneas, o `null` si el ajuste no
     * cabe o algún total pasa del tope de un documento.
     */
    private fun totalWith(bases: List<Base>, mode: TaxMode, adjustment: Money): Totals? {
        if (adjustment.minor !in -Limit.DOCUMENT_TOTAL.max..Limit.DOCUMENT_TOTAL.max) return null
        val shares = when (
            val split = OrderAdjustmentShares.split(
                adjustment,
                bases.map {
                    it.amount
                },
            )
        ) {
            is AdjustmentSplit.Shares -> split.shares
            AdjustmentSplit.DiscountAboveBases -> return null
        }
        var net = Money.ZERO
        var tax = Money.ZERO
        var total = Money.ZERO
        for ((index, base) in bases.withIndex()) {
            val checked = TaxCalculator.line(base.amount + shares[index], mode, base.rate)
            if (checked !is Checked.Value) return null
            net += checked.value.netAmount
            tax += checked.value.taxAmount
            total += checked.value.lineTotal
            if (listOf(net, tax, total).any { !Limit.DOCUMENT_TOTAL.allows(it.minor) }) return null
        }
        return Totals(tax, total)
    }
}
