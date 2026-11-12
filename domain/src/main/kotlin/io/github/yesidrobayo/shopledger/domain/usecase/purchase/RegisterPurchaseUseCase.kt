package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.PurchaseLineTax
import io.github.yesidrobayo.shopledger.domain.rule.TaxCalculator
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchaseLineAmounts
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchaseLineRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult
import kotlin.time.Instant

/**
 * Confirma una compra: cabecera, líneas y una entrada del libro de stock por línea, con el saldo,
 * el costo promedio y el valor del stock que deja cada una, todo en una sola transacción. Si
 * cualquier paso falla o una regla rechaza, no queda nada: una compra a medias dejaría el stock o
 * el costo diciendo algo que no pasó.
 *
 * - **El costo promedio sale del total**, no del costo unitario redondeado. Cada línea suma al
 *   valor del stock lo que costó, sin el impuesto si quien compra lo recupera, y el promedio se
 *   deduce de ese valor. Con stock en cero, negativo o sin valor, el costo anterior no pesa.
 * - **Costo unitario o total, a elección.** La persona escribe uno de los dos y el otro se deduce.
 *   Los dos se guardan en la línea. Una bonificación vale cero, y baja el promedio.
 * - **Un producto puede repetirse**, por ejemplo con dos costos distintos. Las líneas se aplican en
 *   orden, cada una con su fila del libro, y la segunda parte del costo que dejó la primera.
 * - **Las ventas pasadas no cambian.** Cada venta congeló su costo, y una compra solo mueve el
 *   costo de lo que venga después. Tampoco cambia el precio de venta de ningún producto.
 * - **El impuesto va línea por línea**, con la tasa del producto o la del negocio, y cada línea
 *   copia la tasa y su nombre. Sin impuesto en el negocio, la línea no lleva.
 * - **La fecha puede ser pasada**, nunca futura. Las filas del libro llevan la fecha de la compra,
 *   pero toman su lugar en el orden del libro al registrarse: una compra atrasada cambia el costo
 *   desde hoy, no desde su fecha.
 * - **La moneda se bloquea con la primera compra**, en su misma transacción.
 *
 * Por ahora la compra va pagada completa al registrarla y sin cuenta de dinero: la compra a plazo
 * y los movimientos de dinero llegan con su libro. Tampoco compra productos con lotes, ni en otra
 * unidad que la base: los rechaza con su motivo.
 */
class RegisterPurchaseUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val products: ProductRepository,
    private val purchases: PurchaseRepository,
    private val suppliers: SupplierRepository,
    private val units: UnitRepository,
    private val systemRows: SystemRowRepository,
    private val ledger: StockLedgerRepository,
    private val taxRates: TaxRateRepository,
    private val clock: Clock,
) {
    private val currencyLock = CurrencyLock(settings)

    suspend operator fun invoke(request: PurchaseRequest): RegisterPurchaseResult = try {
        transactions.write { register(request) }
    } catch (failure: StorageFailureException) {
        RegisterPurchaseResult.StorageFailed(failure.failure)
    }

    private suspend fun register(request: PurchaseRequest): TxResult<RegisterPurchaseResult> {
        val reference = request.invoiceReference?.trim()?.takeIf { it.isNotEmpty() }
        shapeRejection(request, reference)?.let { return TxResult.Rollback(it) }
        val current = settings.get()
        val calendar = when (val result = BusinessCalendar.of(current.regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return reject(RegisterPurchaseResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        val time = recordTime(calendar, request.date, now)
            ?: return reject(RegisterPurchaseResult.FutureDate)
        request.supplierId?.let { id ->
            val supplier = suppliers.findById(id)
                ?: return reject(RegisterPurchaseResult.SupplierNotFound)
            if (supplier.isArchived) return reject(RegisterPurchaseResult.SupplierArchived)
        }
        val priced = when (val result = priceLines(request.lines, current.taxes)) {
            is Priced.Lines -> result
            is Priced.Rejected -> return reject(result.rejection)
        }
        val totals = totalsOf(priced.lines) ?: return reject(
            RegisterPurchaseResult.PurchaseTotalTooLarge,
        )

        // Justo antes de escribir: la primera compra bloquea la moneda en esta misma transacción,
        // y si la compra se deshace, el bloqueo también.
        when (currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> Unit

            CurrencyLockResult.Unconfirmed ->
                return reject(RegisterPurchaseResult.CurrencyNotConfirmed)
        }
        val purchaseId = write(request.supplierId, reference, priced.lines, totals, time, now)
        val changes = priced.costBefore.map { (productId, before) ->
            PurchaseCostChange(
                productId = productId,
                averageCostBefore = before,
                averageCostAfter = checkNotNull(priced.costAfter[productId]).averageCost,
            )
        }
        return TxResult.Commit(RegisterPurchaseResult.Registered(purchaseId, changes))
    }

    /** Lo que se rechaza sin leer la base: la forma de la compra. */
    private fun shapeRejection(
        request: PurchaseRequest,
        reference: String?,
    ): RegisterPurchaseResult? = when {
        request.lines.isEmpty() -> RegisterPurchaseResult.EmptyPurchase

        request.lines.size > Limit.DOCUMENT_LINES.max -> RegisterPurchaseResult.TooManyLines

        (reference?.length ?: 0) > MAX_REFERENCE_LENGTH ->
            RegisterPurchaseResult.InvoiceReferenceTooLong

        else -> null
    }

    /**
     * El instante y la fecha de la compra: ahora, si la fecha es hoy o no se eligió; las 12:00 de
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

    /**
     * Calcula cada línea en orden, con los productos leídos de una vez dentro de la transacción. Un
     * producto que se repite calcula su segunda línea con el costo que dejó la primera.
     */
    private suspend fun priceLines(
        requests: List<PurchaseLineRequest>,
        taxes: TaxSettings,
    ): Priced {
        val byId = products.findByIds(requests.map { it.productId }.toSet()).associateBy { it.id }
        val costBefore = LinkedHashMap<Long, Money>()
        val costAfter = HashMap<Long, CostState>()
        val fractionByUnit = HashMap<Long, Boolean>()
        val taxByRate = HashMap<Long, AppliedTax>()
        val lines = ArrayList<PricedLine>(requests.size)
        for ((index, request) in requests.withIndex()) {
            val product = byId[request.productId]
                ?: return rejected(index, PurchaseLineRejection.PRODUCT_NOT_FOUND)
            productRejection(product)?.let { return rejected(index, it) }
            val allowsFraction = fractionByUnit.getOrPut(product.details.baseUnitId) {
                // La unidad base de un producto siempre existe: su llave no deja borrarla.
                (units.findById(product.details.baseUnitId) ?: damaged()).allowsFraction
            }
            quantityRejection(request.quantity, allowsFraction)?.let { return rejected(index, it) }
            val amounts = when (val result = amountsOf(request)) {
                is LineAmounts.Ready -> result.amounts
                is LineAmounts.Rejected -> return rejected(index, result.reason)
            }
            val tax = appliedTax(product, taxes, taxByRate)
            val split = when (
                val checked = TaxCalculator.purchaseLine(
                    amounts.written,
                    taxes.mode,
                    tax.rate,
                    taxes.purchaseTaxRecoverable,
                )
            ) {
                is Checked.Value -> checked.value

                Checked.Overflow, is Checked.AboveLimit ->
                    return rejected(index, PurchaseLineRejection.LINE_TOTAL_TOO_LARGE)
            }
            // La fila del libro congela el costo de una unidad base con lo que entró al stock.
            val movementCost = PurchaseLineRules.costPerUnit(request.quantity, split.costAmount)
                .valueOrNull()
                ?: return rejected(index, PurchaseLineRejection.UNIT_COST_TOO_LARGE)
            val before = costAfter[product.id]
                ?: CostState(product.onHand, product.averageCost, product.stockValue)
            costBefore.putIfAbsent(product.id, product.averageCost)
            val after = entryAfter(before, request.quantity, split.costAmount)
                ?: return rejected(index, PurchaseLineRejection.STOCK_ABOVE_LIMIT)
            costAfter[product.id] = after
            lines += PricedLine(
                productId = product.id,
                unitId = product.details.baseUnitId,
                quantity = request.quantity,
                amounts = amounts,
                tax = tax,
                split = split,
                movementCost = movementCost,
                after = after,
            )
        }
        return Priced.Lines(lines, costBefore, costAfter)
    }

    private fun productRejection(product: Product): PurchaseLineRejection? = when {
        product.isArchived -> PurchaseLineRejection.PRODUCT_ARCHIVED
        !StockModeRules.hasLedger(product.details.stockMode) -> PurchaseLineRejection.NOT_STOCKED
        product.details.tracksLots -> PurchaseLineRejection.LOTS_NOT_SUPPORTED
        else -> null
    }

    private fun quantityRejection(
        quantity: QuantityMilli,
        allowsFraction: Boolean,
    ): PurchaseLineRejection? {
        if (!Limit.LINE_QUANTITY_MILLI.allows(quantity.milli)) {
            return PurchaseLineRejection.INVALID_QUANTITY
        }
        return when (UnitQuantityRule.check(quantity, allowsFraction)) {
            UnitQuantityCheck.Valid -> null
            UnitQuantityCheck.NotPositive -> PurchaseLineRejection.INVALID_QUANTITY
            UnitQuantityCheck.FractionNotAllowed -> PurchaseLineRejection.FRACTION_NOT_ALLOWED
        }
    }

    /** El costo unitario y el total de la línea, con la cantidad ya revisada. */
    private fun amountsOf(request: PurchaseLineRequest): LineAmounts {
        val written = when (val amount = request.amount) {
            is PurchaseLineAmount.UnitCost -> amount.cost
            is PurchaseLineAmount.LineTotal -> amount.total
        }
        if (written < Money.ZERO) return LineAmounts.Rejected(PurchaseLineRejection.NEGATIVE_AMOUNT)
        return when (val checked = PurchaseLineRules.amounts(request.quantity, request.amount)) {
            is Checked.Value -> LineAmounts.Ready(checked.value)

            is Checked.AboveLimit -> LineAmounts.Rejected(
                if (checked.limit == Limit.UNIT_MONEY) {
                    PurchaseLineRejection.UNIT_COST_TOO_LARGE
                } else {
                    PurchaseLineRejection.LINE_TOTAL_TOO_LARGE
                },
            )

            Checked.Overflow -> LineAmounts.Rejected(PurchaseLineRejection.LINE_TOTAL_TOO_LARGE)
        }
    }

    /**
     * El estado del costo tras la entrada, o `null` si deja el stock, su valor o el costo promedio
     * por encima de sus topes: las cuentas que vienen después no los soportarían.
     */
    private fun entryAfter(before: CostState, quantity: QuantityMilli, total: Money): CostState? {
        val after = WeightedAverageCost.entry(before, quantity, total).valueOrNull() ?: return null
        return after.takeIf {
            Limit.ON_HAND_MILLI.allows(it.onHand.milli) &&
                Limit.STOCK_VALUE.allows(it.stockValue.minor) &&
                Limit.UNIT_MONEY.allows(it.averageCost.minor)
        }
    }

    /**
     * La tasa que paga [product]: la suya, o la tasa por defecto del negocio si no tiene, leída una
     * sola vez por compra. Sin impuesto en el negocio, o sin ninguna tasa, la línea no lleva.
     */
    private suspend fun appliedTax(
        product: Product,
        taxes: TaxSettings,
        cache: MutableMap<Long, AppliedTax>,
    ): AppliedTax {
        if (taxes.mode == TaxMode.NONE) return AppliedTax.NONE
        val rateId = product.details.taxRateId ?: taxes.defaultTaxRateId ?: return AppliedTax.NONE
        return cache.getOrPut(rateId) {
            // Su llave deja sin tasa al producto y al negocio si se elimina: nunca falta.
            val rate = taxRates.findById(rateId) ?: damaged()
            AppliedTax(rate.details.rate, rate.details.name.take(MAX_TAX_NAME_LENGTH))
        }
    }

    /** Las sumas de la cabecera, o `null` si alguna pasa del total máximo de un documento. */
    private fun totalsOf(lines: List<PricedLine>): Totals? {
        val total = lines.map { it.split.lineTotal }.sumOrNull() ?: return null
        val tax = lines.map { it.split.taxAmount }.sumOrNull() ?: return null
        val cost = lines.map { it.split.costAmount }.sumOrNull() ?: return null
        val inLimit = listOf(total, tax, cost).all { Limit.DOCUMENT_TOTAL.allows(it.minor) }
        return if (inLimit) {
            Totals(
                net = total - tax,
                tax = tax,
                total = total,
                cost = cost,
            )
        } else {
            null
        }
    }

    private suspend fun write(
        supplierId: Long?,
        reference: String?,
        lines: List<PricedLine>,
        totals: Totals,
        time: RecordTime,
        now: Instant,
    ): Long {
        val location = checkNotNull(systemRows.findId(SystemRow.MAIN_LOCATION)) {
            "Falta una fila inicial de la base"
        }
        val purchaseId = purchases.insert(
            NewPurchase(
                supplierId = supplierId,
                invoiceReference = reference,
                netTotal = totals.net,
                taxTotal = totals.tax,
                total = totals.total,
                costTotal = totals.cost,
                // Pagada completa al registrarla: la compra a plazo llega con el libro del proveedor.
                amountPaid = totals.total,
                moneyAccountId = null,
                dueDate = null,
                locationId = location,
                foreignCurrency = null,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            ),
        )
        for (line in lines) {
            val lineId = purchases.insertLine(purchaseId, line.toNewLine())
            ledger.append(
                NewStockMovement(
                    movement = StockMovementDraft(
                        key = StockKey(line.productId, location, lotId = null),
                        type = StockMovementType.PURCHASE,
                        quantity = line.quantity,
                        reversesMovementId = null,
                    ),
                    reason = null,
                    unitCost = line.movementCost,
                    totalCost = line.split.costAmount,
                    balanceAfter = line.after.onHand,
                    averageCostAfter = line.after.averageCost,
                    stockValueAfter = line.after.stockValue,
                    source = StockMovementSource(purchaseLineId = lineId),
                    note = null,
                    occurredAt = time.occurredAt,
                    businessDate = time.businessDate,
                    createdAt = now,
                ),
            )
        }
        return purchaseId
    }

    private fun PricedLine.toNewLine(): NewPurchaseLine = NewPurchaseLine(
        productId = productId,
        unitId = unitId,
        unitFactor = QuantityMilli.ofUnits(1L),
        enteredQuantity = quantity,
        quantity = quantity,
        unitCost = amounts.unitCost,
        lineTotal = split.lineTotal,
        taxRate = tax.rate,
        taxNameSnapshot = tax.name,
        taxAmount = split.taxAmount,
        costAmount = split.costAmount,
        lotId = null,
        referenceUnitCost = null,
    )

    private fun reject(result: RegisterPurchaseResult): TxResult<RegisterPurchaseResult> =
        TxResult.Rollback(result)

    private fun rejected(index: Int, reason: PurchaseLineRejection): Priced =
        Priced.Rejected(RegisterPurchaseResult.LineRejected(index, reason))

    /** Una fila que la base garantiza y no está: la base está dañada, y no se escribe nada. */
    private fun damaged(): Nothing =
        throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

    /** La tasa que copia una línea: los puntos básicos y el nombre del momento. */
    private class AppliedTax(val rate: BasisPoints, val name: String?) {
        companion object {
            val NONE = AppliedTax(BasisPoints.ZERO, name = null)
        }
    }

    /** Una línea ya calculada, lista para guardarse. [after] es el costo del producto tras ella. */
    private class PricedLine(
        val productId: Long,
        val unitId: Long,
        val quantity: QuantityMilli,
        val amounts: PurchaseLineAmounts,
        val tax: AppliedTax,
        val split: PurchaseLineTax,
        val movementCost: Money,
        val after: CostState,
    )

    private class Totals(val net: Money, val tax: Money, val total: Money, val cost: Money)

    private sealed interface Priced {
        class Lines(
            val lines: List<PricedLine>,
            val costBefore: Map<Long, Money>,
            val costAfter: Map<Long, CostState>,
        ) : Priced

        class Rejected(val rejection: RegisterPurchaseResult) : Priced
    }

    private sealed interface LineAmounts {
        class Ready(val amounts: PurchaseLineAmounts) : LineAmounts

        class Rejected(val reason: PurchaseLineRejection) : LineAmounts
    }

    private companion object {
        /** Largo máximo de la referencia del documento del proveedor. */
        const val MAX_REFERENCE_LENGTH = 40

        /** Largo máximo del nombre de impuesto que copia una línea. */
        const val MAX_TAX_NAME_LENGTH = 40
    }
}
