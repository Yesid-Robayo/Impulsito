package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentMethod
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.SalePaymentRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LineStockPlan
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LineStockSubject
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.PaymentFees
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleLineRules
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult
import kotlin.time.Instant

/**
 * Confirma una venta: cabecera, líneas, salidas del libro de stock con sus saldos, y su pago, todo
 * en una sola transacción. Si cualquier paso falla o una regla rechaza, no queda nada: una venta a
 * medias es un dato falso guardado como bueno.
 *
 * - **Nunca se bloquea por stock.** Vender más de lo que hay deja el saldo negativo, y ese negativo
 *   avisa que falta registrar una compra o un conteo.
 * - **El costo se congela.** Cada línea guarda el costo promedio del momento para mostrarlo, y su
 *   costo real sale del valor del stock. Nada posterior lo reescribe.
 * - **Todo se lee dentro de la transacción**, que toma el bloqueo de escritura al empezar: el saldo
 *   y el costo con que se calcula cada salida son los que hay al confirmar. Dos líneas del mismo
 *   producto encadenan su costo.
 * - **La moneda se bloquea con la primera venta**, en su misma transacción: sin moneda confirmada
 *   no se vende, porque cada monto se guarda en su unidad menor.
 * - **Un solo pago**, que cubre todo lo que se cobra, con la comisión y la acreditación que la
 *   persona escribió en su método. Copia el nombre y el tipo del método, y su cuenta, sin mover
 *   dinero todavía.
 *
 * Por ahora no calcula impuesto, ni ajuste global, propina o redondeo del efectivo: la venta queda
 * con impuesto cero y esos montos en cero. Tampoco vende combos ni productos con lotes, ni a
 * crédito: los rechaza con su motivo.
 */
class RegisterSaleUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val products: ProductRepository,
    private val sales: SaleRepository,
    private val payments: SalePaymentRepository,
    private val units: UnitRepository,
    private val systemRows: SystemRowRepository,
    private val ledger: StockLedgerRepository,
    private val methodNames: PaymentMethodNames,
    private val clock: Clock,
) {
    private val currencyLock = CurrencyLock(settings)

    suspend operator fun invoke(request: SaleRequest): RegisterSaleResult = try {
        transactions.write { register(request) }
    } catch (failure: StorageFailureException) {
        RegisterSaleResult.StorageFailed(failure.failure)
    }

    private suspend fun register(request: SaleRequest): TxResult<RegisterSaleResult> {
        shapeRejection(request)?.let { return TxResult.Rollback(it) }
        val note = request.note?.takeIf { it.isNotBlank() }

        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return TxResult.Rollback(RegisterSaleResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        val time = recordTime(calendar, request.date, now)
            ?: return TxResult.Rollback(RegisterSaleResult.FutureDate)

        val method = payments.findMethod(request.paymentMethodId)
            ?: return TxResult.Rollback(RegisterSaleResult.PaymentMethodNotFound)
        methodRejection(method)?.let { return TxResult.Rollback(it) }

        val priced = when (val result = priceLines(request.lines)) {
            is Priced.Lines -> result
            is Priced.Rejected -> return TxResult.Rollback(result.rejection)
        }
        val payment = paymentOf(method, priced.totals.total, time.businessDate, now)
            ?: return TxResult.Rollback(RegisterSaleResult.SaleTotalTooLarge)

        // Justo antes de escribir: la primera venta bloquea la moneda en esta misma transacción, y
        // si la venta se deshace, el bloqueo también.
        when (currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> Unit

            CurrencyLockResult.Unconfirmed ->
                return TxResult.Rollback(RegisterSaleResult.CurrencyNotConfirmed)
        }
        val saleId = write(priced, payment, note, time, now)
        return TxResult.Commit(RegisterSaleResult.Registered(saleId))
    }

    /** Lo que se rechaza sin leer la base: la forma de la venta. */
    private fun shapeRejection(request: SaleRequest): RegisterSaleResult? = when {
        request.lines.isEmpty() -> RegisterSaleResult.EmptySale
        request.lines.size > Limit.DOCUMENT_LINES.max -> RegisterSaleResult.TooManyLines
        (request.note?.length ?: 0) > MAX_NOTE_LENGTH -> RegisterSaleResult.NoteTooLong
        else -> null
    }

    /**
     * El instante y la fecha de la venta: ahora, si la fecha es hoy o no se eligió; las 12:00 de
     * ese día, si es pasada; o `null` si es futura.
     */
    private fun recordTime(
        calendar: BusinessCalendar,
        chosen: BusinessDate?,
        now: Instant,
    ): RecordTime? {
        val today = calendar.stamp(now)
        return when {
            chosen == null || chosen == today.businessDate -> today
            chosen > today.businessDate -> null
            else -> calendar.stampBackdated(chosen)
        }
    }

    private fun methodRejection(method: SalePaymentMethod): RegisterSaleResult? = when {
        method.isArchived -> RegisterSaleResult.PaymentMethodArchived
        method.kind == PaymentMethodKind.ON_ACCOUNT -> RegisterSaleResult.PaymentNeedsCustomer
        else -> null
    }

    /**
     * Calcula cada línea en orden, con los productos leídos de una vez. Un producto que se repite
     * calcula su segunda línea con el estado de costo que dejó la primera.
     */
    private suspend fun priceLines(requests: List<SaleLineRequest>): Priced {
        val byId = products.findByIds(requests.map { it.productId }.toSet()).associateBy { it.id }
        val costStates = HashMap<Long, CostState>()
        val fractionByUnit = HashMap<Long, Boolean>()
        val lines = ArrayList<PricedLine>(requests.size)
        val totals = Totals()
        for ((index, request) in requests.withIndex()) {
            val product = byId[request.productId]
                ?: return rejected(index, LineRejection.PRODUCT_NOT_FOUND)
            productRejection(product)?.let { return rejected(index, it) }
            val allowsFraction = fractionByUnit.getOrPut(product.details.baseUnitId) {
                // La unidad base de un producto siempre existe: su llave no deja borrarla.
                val unit = units.findById(product.details.baseUnitId) ?: damaged()
                unit.allowsFraction
            }
            quantityRejection(request.quantity, allowsFraction)?.let { return rejected(index, it) }
            val line = when (val result = priceLine(request, product, costStates)) {
                is LineResult.Ready -> result.line
                is LineResult.Rejected -> return rejected(index, result.reason)
            }
            if (!totals.add(line)) return Priced.Rejected(RegisterSaleResult.SaleTotalTooLarge)
            line.plan.let { plan ->
                if (plan is LineStockPlan.Direct) costStates[product.id] = plan.exit.stateAfter
            }
            lines += line
        }
        return Priced.Lines(lines, totals)
    }

    private fun productRejection(product: Product): LineRejection? = when {
        product.isArchived -> LineRejection.PRODUCT_ARCHIVED

        product.details.stockMode == ProductStockMode.COMPONENTS ->
            LineRejection.COMPONENTS_NOT_SUPPORTED

        product.details.tracksLots -> LineRejection.LOTS_NOT_SUPPORTED

        else -> null
    }

    private fun quantityRejection(
        quantity: QuantityMilli,
        allowsFraction: Boolean,
    ): LineRejection? {
        if (!Limit.LINE_QUANTITY_MILLI.allows(quantity.milli)) return LineRejection.INVALID_QUANTITY
        return when (UnitQuantityRule.check(quantity, allowsFraction)) {
            UnitQuantityCheck.Valid -> null
            UnitQuantityCheck.NotPositive -> LineRejection.INVALID_QUANTITY
            UnitQuantityCheck.FractionNotAllowed -> LineRejection.FRACTION_NOT_ALLOWED
        }
    }

    private fun priceLine(
        request: SaleLineRequest,
        product: Product,
        costStates: Map<Long, CostState>,
    ): LineResult {
        val unitPrice = request.unitPrice ?: product.salePrice
        if (unitPrice < Money.ZERO || !Limit.UNIT_MONEY.allows(unitPrice.minor)) {
            return LineResult.Rejected(LineRejection.INVALID_PRICE)
        }
        val gross = SaleLineRules.gross(request.quantity, unitPrice).valueOr {
            return LineResult.Rejected(LineRejection.AMOUNT_TOO_LARGE)
        }
        val discount = SaleLineRules.discountOf(gross, request.discount)
            ?: return LineResult.Rejected(LineRejection.INVALID_DISCOUNT)
        val plan = StockModeRules.planLine(stockSubject(product, request.quantity, costStates))
            .valueOr { return LineResult.Rejected(LineRejection.AMOUNT_TOO_LARGE) }
        val unitCost = when (plan) {
            is LineStockPlan.Direct -> plan.exit.unitCost

            is LineStockPlan.NoStock -> plan.unitCost

            // Un combo se rechaza antes de llegar aquí.
            is LineStockPlan.Components -> error("Un combo no llega a calcular su línea")
        }
        val unitReplacement = SaleLineRules.unitReplacementCost(product.replacementCost, unitCost)
        val lineReplacement = SaleLineRules.lineReplacementCost(request.quantity, unitReplacement)
            .valueOr { return LineResult.Rejected(LineRejection.AMOUNT_TOO_LARGE) }
        // Sin impuesto ni ajuste global todavía: lo neto es el bruto menos el descuento.
        val net = gross - discount
        return LineResult.Ready(
            PricedLine(
                product = product,
                quantity = request.quantity,
                unitPrice = unitPrice,
                unitCost = unitCost,
                discount = discount,
                net = net,
                plan = plan,
                unitReplacement = unitReplacement,
                lineReplacement = lineReplacement,
            ),
        )
    }

    private fun stockSubject(
        product: Product,
        quantity: QuantityMilli,
        costStates: Map<Long, CostState>,
    ): LineStockSubject = when (product.details.stockMode) {
        ProductStockMode.TRACKED -> LineStockSubject.Tracked(
            costState = costStates[product.id]
                ?: CostState(product.onHand, product.averageCost, product.stockValue),
            quantity = quantity,
        )

        ProductStockMode.UNTRACKED ->
            LineStockSubject.Untracked(product.details.fixedUnitCost, quantity)

        ProductStockMode.COMPONENTS -> error("Un combo no llega a calcular su línea")
    }

    /**
     * El único pago, por todo lo que se cobra, o `null` si su comisión pasa del tope. El nombre que
     * se congela es el del método, o el de su clave en el idioma de la interfaz si nadie lo
     * renombró, recortado al largo que admite el pago.
     */
    private fun paymentOf(
        method: SalePaymentMethod,
        amountDue: Money,
        businessDate: BusinessDate,
        now: Instant,
    ): NewSalePayment? {
        val fee = PaymentFees.feeOf(amountDue, method.feeRate, method.feeFixed)
            .valueOr { return null }
        val name = method.name ?: methodNames.systemName(
            checkNotNull(method.systemKey) { "Un método sin nombre tiene su clave inicial" },
        )
        return NewSalePayment(
            paymentMethodId = method.id,
            methodNameSnapshot = name.take(MAX_METHOD_NAME_LENGTH),
            methodKind = method.kind,
            amount = amountDue,
            feeAmount = fee,
            settlementDate = PaymentFees.settlementDateOf(businessDate, method.settlementDays),
            moneyAccountId = method.moneyAccountId,
            createdAt = now,
        )
    }

    /** Escribe la venta ya calculada y devuelve su id. Lo único que hace aquí es guardar. */
    private suspend fun write(
        priced: Priced.Lines,
        payment: NewSalePayment,
        note: String?,
        time: RecordTime,
        now: Instant,
    ): Long {
        val locationId = systemRows.findId(SystemRow.MAIN_LOCATION) ?: damaged()
        val priceListId = systemRows.findId(SystemRow.DEFAULT_PRICE_LIST) ?: damaged()
        val totals = priced.totals
        val saleId = sales.insert(
            NewSale(
                netTotal = totals.net,
                taxTotal = Money.ZERO,
                total = totals.total,
                totalCost = totals.cost,
                totalReplacementCost = totals.replacement,
                orderAdjustment = Money.ZERO,
                tipAmount = Money.ZERO,
                cashRoundingAmount = Money.ZERO,
                amountDue = totals.total,
                customerId = null,
                priceListId = priceListId,
                locationId = locationId,
                cashSessionId = null,
                quoteId = null,
                orderId = null,
                note = note,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            ),
        )
        for (line in priced.lines) {
            val lineId = sales.insertLine(saleId, line.toNewLine(priceListId))
            val plan = line.plan
            if (plan is LineStockPlan.Direct) {
                val exit = plan.exit
                ledger.append(
                    NewStockMovement(
                        movement = exit.saleMovement(
                            StockKey(line.product.id, locationId, lotId = null),
                        ),
                        reason = null,
                        unitCost = exit.unitCost,
                        totalCost = -exit.cost,
                        balanceAfter = exit.stateAfter.onHand,
                        averageCostAfter = exit.stateAfter.averageCost,
                        stockValueAfter = exit.stateAfter.stockValue,
                        source = StockMovementSource(saleLineId = lineId),
                        note = null,
                        // El movimiento hereda el hecho de la venta: una venta atrasada lo
                        // fecha en su día, aunque tome su lugar en el libro por orden de llegada.
                        occurredAt = time.occurredAt,
                        businessDate = time.businessDate,
                        createdAt = now,
                    ),
                )
            }
        }
        payments.insert(saleId, payment)
        return saleId
    }

    private fun PricedLine.toNewLine(priceListId: Long) = NewSaleLine(
        productId = product.id,
        unitId = product.details.baseUnitId,
        unitFactor = QuantityMilli.ofUnits(1L),
        enteredQuantity = quantity,
        quantity = quantity,
        unitPrice = unitPrice,
        unitCost = unitCost,
        lineDiscount = discount,
        orderAdjustmentShare = Money.ZERO,
        netAmount = net,
        taxRate = BasisPoints.ZERO,
        taxNameSnapshot = null,
        taxAmount = Money.ZERO,
        lineTotal = net,
        lineCost = plan.lineCost,
        unitReplacementCost = unitReplacement,
        lineReplacementCost = lineReplacement,
        productNameSnapshot = product.details.name,
        stockEffect = plan.stockEffect,
        parentLineId = null,
        priceListId = priceListId,
    )

    /**
     * Una fila que la base siempre tiene y no está: la base está dañada, y la venta no se escribe.
     * Sale como cualquier falla de la base, sin texto.
     */
    private fun damaged(): Nothing =
        throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

    private fun rejected(index: Int, reason: LineRejection): Priced =
        Priced.Rejected(RegisterSaleResult.LineRejected(index, reason))

    /** Una línea ya calculada, lista para guardar. */
    private class PricedLine(
        val product: Product,
        val quantity: QuantityMilli,
        val unitPrice: Money,
        val unitCost: Money,
        val discount: Money,
        val net: Money,
        val plan: LineStockPlan,
        val unitReplacement: Money,
        val lineReplacement: Money,
    )

    private sealed interface LineResult {
        class Ready(val line: PricedLine) : LineResult

        class Rejected(val reason: LineRejection) : LineResult
    }

    private sealed interface Priced {
        class Lines(val lines: List<PricedLine>, val totals: Totals) : Priced

        class Rejected(val rejection: RegisterSaleResult) : Priced
    }

    /**
     * Los totales de la cabecera, sumados línea a línea. Cada suma se compara con el tope de un
     * documento antes de seguir: cada línea está dentro de ese tope, así que la suma cabe en
     * `Long`.
     */
    private class Totals {
        var net: Money = Money.ZERO
            private set
        var total: Money = Money.ZERO
            private set
        var cost: Money = Money.ZERO
            private set
        var replacement: Money = Money.ZERO
            private set

        /** Suma [line], o devuelve `false` si algún total pasaría del tope. */
        fun add(line: PricedLine): Boolean {
            val nextNet = net + line.net
            val nextCost = cost + line.plan.lineCost
            val nextReplacement = replacement + line.lineReplacement
            val fits = listOf(nextNet, nextCost, nextReplacement).all {
                Limit.DOCUMENT_TOTAL.allows(it.minor)
            }
            if (!fits) return false
            net = nextNet
            // Sin impuesto todavía, el total es lo neto.
            total = nextNet
            cost = nextCost
            replacement = nextReplacement
            return true
        }
    }

    private companion object {
        /** Largo máximo de la nota de una venta. */
        const val MAX_NOTE_LENGTH = 200

        /** Largo máximo del nombre congelado de un método de pago. */
        const val MAX_METHOD_NAME_LENGTH = 40
    }
}

/** El valor, o lo que devuelva [rejected] si la cuenta se rechazó. */
private inline fun <T> Checked<T>.valueOr(rejected: () -> T): T = when (this) {
    is Checked.Value -> value
    Checked.Overflow, is Checked.AboveLimit -> rejected()
}
