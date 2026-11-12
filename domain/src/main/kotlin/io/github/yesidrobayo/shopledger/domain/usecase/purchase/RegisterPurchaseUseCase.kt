package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
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
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PricedPurchaseLine
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchasePricing
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchasePricingResult
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchaseSubject
import io.github.yesidrobayo.shopledger.domain.rule.purchase.PurchaseTotals
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
        val subjects = subjectsOf(request.lines, current.taxes)
        val priced = when (
            val result = PurchasePricing.price(request.lines, subjects, current.taxes)
        ) {
            is PurchasePricingResult.Priced -> result

            is PurchasePricingResult.LineRejected ->
                return reject(RegisterPurchaseResult.LineRejected(result.index, result.reason))

            PurchasePricingResult.TotalTooLarge ->
                return reject(RegisterPurchaseResult.PurchaseTotalTooLarge)
        }

        // Justo antes de escribir: la primera compra bloquea la moneda en esta misma transacción,
        // y si la compra se deshace, el bloqueo también.
        when (currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> Unit

            CurrencyLockResult.Unconfirmed ->
                return reject(RegisterPurchaseResult.CurrencyNotConfirmed)
        }
        val purchaseId =
            write(request.supplierId, reference, priced.lines, priced.totals, time, now)
        return TxResult.Commit(RegisterPurchaseResult.Registered(purchaseId, priced.costChanges))
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
     * Lo que el cálculo necesita de cada producto de la compra, leído una vez dentro de la
     * transacción: el producto con su costo, si su unidad admite fracciones y la tasa que paga. Un
     * producto que no existe queda fuera, y el cálculo rechaza su línea.
     */
    private suspend fun subjectsOf(
        lines: List<PurchaseLineRequest>,
        taxes: TaxSettings,
    ): Map<Long, PurchaseSubject> {
        val fractionByUnit = HashMap<Long, Boolean>()
        val taxByRate = HashMap<Long, AppliedTax>()
        return products.findByIds(lines.map { it.productId }.toSet()).associate { product ->
            val allowsFraction = fractionByUnit.getOrPut(product.details.baseUnitId) {
                // La unidad base de un producto siempre existe: su llave no deja borrarla.
                (units.findById(product.details.baseUnitId) ?: damaged()).allowsFraction
            }
            val tax = PurchasePricing.taxRateIdOf(product, taxes)?.let { rateId ->
                taxByRate.getOrPut(rateId) {
                    // Su llave deja sin tasa al producto y al negocio si se elimina: nunca falta.
                    val rate = taxRates.findById(rateId) ?: damaged()
                    AppliedTax(rate.details.rate, rate.details.name.take(MAX_TAX_NAME_LENGTH))
                }
            } ?: AppliedTax.NONE
            product.id to PurchaseSubject(product, allowsFraction, tax.rate, tax.name)
        }
    }

    private suspend fun write(
        supplierId: Long?,
        reference: String?,
        lines: List<PricedPurchaseLine>,
        totals: PurchaseTotals,
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
                        key = StockKey(line.subject.product.id, location, lotId = null),
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

    private fun PricedPurchaseLine.toNewLine(): NewPurchaseLine = NewPurchaseLine(
        productId = subject.product.id,
        unitId = subject.product.details.baseUnitId,
        unitFactor = QuantityMilli.ofUnits(1L),
        enteredQuantity = quantity,
        quantity = quantity,
        unitCost = amounts.unitCost,
        lineTotal = split.lineTotal,
        taxRate = subject.taxRate,
        taxNameSnapshot = subject.taxName,
        taxAmount = split.taxAmount,
        costAmount = split.costAmount,
        lotId = null,
        referenceUnitCost = null,
    )

    private fun reject(result: RegisterPurchaseResult): TxResult<RegisterPurchaseResult> =
        TxResult.Rollback(result)

    /** Una fila que la base garantiza y no está: la base está dañada, y no se escribe nada. */
    private fun damaged(): Nothing =
        throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

    /** La tasa que copia una línea: los puntos básicos y el nombre del momento. */
    private class AppliedTax(val rate: BasisPoints, val name: String?) {
        companion object {
            val NONE = AppliedTax(BasisPoints.ZERO, name = null)
        }
    }

    private companion object {
        /** Largo máximo de la referencia del documento del proveedor. */
        const val MAX_REFERENCE_LENGTH = 40

        /** Largo máximo del nombre de impuesto que copia una línea. */
        const val MAX_TAX_NAME_LENGTH = 40
    }
}
