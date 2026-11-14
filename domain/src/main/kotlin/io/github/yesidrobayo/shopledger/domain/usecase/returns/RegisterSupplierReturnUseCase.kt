package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRejection
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseReturnRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SupplierLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.returns.SupplierReturnLineAmounts
import io.github.yesidrobayo.shopledger.domain.rule.returns.SupplierReturnLinePricing
import io.github.yesidrobayo.shopledger.domain.rule.returns.SupplierReturnPricingLine
import io.github.yesidrobayo.shopledger.domain.rule.returns.SupplierReturnRules
import io.github.yesidrobayo.shopledger.domain.usecase.money.AccountIssue
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.appendChained
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockChangeRecorder
import kotlin.time.Instant

/**
 * Registra una devolución a un proveedor: cabecera, líneas, una salida del libro de stock por línea
 * y, según cómo se resuelva, el dinero que entra o el crédito con el proveedor, todo en una sola
 * transacción. Si cualquier paso falla o una regla rechaza, no queda nada: una devolución a medias
 * dejaría el stock, el costo o lo que se le debe al proveedor diciendo algo que no pasó.
 *
 * - **No anula la compra.** La compra sigue activa, y la devolución es un hecho nuevo con su fecha,
 *   que es la de hoy: el libro es cronológico.
 * - **Con compra, sale al costo de su línea** (`devolucionAProveedor`): el valor del stock baja en lo
 *   que costó esa parte, y el costo promedio de lo que queda sale del valor que queda. Por eso, a
 *   diferencia de una venta, sí cambia el promedio. De cada línea de compra se devuelve a lo sumo
 *   lo comprado, contando las devoluciones activas anteriores y las líneas anteriores de esta.
 * - **Sin compra, sale con `costoDeSalida`**, como una venta, y el proveedor devuelve ese costo.
 * - **Puede dejar el stock negativo.** No se bloquea, como cualquier salida: la pantalla lo avisa.
 * - **No toca ganancias.** Lo devuelto nunca se vendió, así que su costo no llegó a ningún reporte.
 * - **Con reembolso** y una cuenta elegida, entra a esa cuenta un `SUPPLIER_REFUND` por el total.
 *   Sin cuenta, o con total cero, no se escribe dinero.
 * - **Con crédito**, el libro del proveedor recibe un `RETURN_CREDIT` por el total, en negativo, y
 *   baja lo que se le debe. Puede quedar negativo: es crédito con el proveedor. Exige proveedor.
 * - **Un proveedor o un producto archivados** no impiden devolver: archivar no borra la historia.
 * - **La moneda se bloquea** con la primera devolución, si todavía no lo estaba.
 *
 * Por ahora no devuelve productos con lotes ni sin stock propio: los rechaza con su motivo.
 *
 * Con [alerts], evalúa en la misma transacción la alerta de stock bajo de cada producto que mueve.
 * [money] solo hace falta para un reembolso a una cuenta.
 */
class RegisterSupplierReturnUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val purchases: PurchaseRepository,
    private val purchaseReturns: PurchaseReturnRepository,
    private val suppliers: SupplierRepository,
    private val supplierLedger: SupplierLedgerRepository,
    private val systemRows: SystemRowRepository,
    ledger: StockLedgerRepository,
    private val clock: Clock,
    private val money: DocumentMoney? = null,
    private val alerts: LowStockAlertRepository? = null,
    private val lots: StockLotRepository? = null,
) {
    private val currencyLock = CurrencyLock(settings)
    private val ledger = StockChangeRecorder(ledger)

    suspend operator fun invoke(request: SupplierReturnRequest): RegisterSupplierReturnResult =
        try {
            transactions.write { register(request) }
        } catch (failure: StorageFailureException) {
            RegisterSupplierReturnResult.StorageFailed(failure.failure)
        }

    private suspend fun register(
        request: SupplierReturnRequest,
    ): TxResult<RegisterSupplierReturnResult> {
        ledger.start()
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        shapeRejection(request, note)?.let { return reject(it) }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return reject(RegisterSupplierReturnResult.UnknownTimeZone(result.storedZoneId))
        }
        val purchase = request.purchaseId?.let { id ->
            purchases.findById(id) ?: return reject(RegisterSupplierReturnResult.PurchaseNotFound)
        }
        if (purchase?.status == DocumentStatus.VOIDED) {
            return reject(RegisterSupplierReturnResult.PurchaseVoided)
        }
        val supplierId = when (val result = supplierOf(request, purchase)) {
            is SupplierChoice.Chosen -> result.supplierId
            is SupplierChoice.Rejected -> return reject(result.reason)
        }
        val (priced, total) = when (val result = price(request.lines, purchase)) {
            is Pricing.Priced -> result.lines to result.total
            is Pricing.Rejected -> return reject(result.reason)
        }

        // Justo antes de escribir: la primera devolución bloquea la moneda en esta misma
        // transacción, y si la devolución se deshace, el bloqueo también.
        val now = clock.now()
        val currency = when (val lock = currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> lock.currency

            CurrencyLockResult.Unconfirmed ->
                return reject(RegisterSupplierReturnResult.CurrencyNotConfirmed)
        }
        // Un crédito no mueve ninguna cuenta, y un reembolso de cero tampoco: no guardan la cuenta.
        val accountId = request.moneyAccountId?.takeIf {
            request.resolution == PurchaseReturnResolution.CASH_REFUND && total > Money.ZERO
        }
        val payer = accountId?.let {
            val documentMoney =
                checkNotNull(money) { "Un reembolso a una cuenta necesita el dinero" }
            documentMoney.issueOf(it, currency)?.let { issue ->
                return reject(RegisterSupplierReturnResult.AccountUnavailable(issue))
            }
            documentMoney
        }
        val time = calendar.stamp(now)
        val location = locationOf(purchase)
        val returnId = purchaseReturns.insert(
            NewPurchaseReturn(
                purchaseId = purchase?.id,
                supplierId = supplierId,
                resolution = request.resolution,
                total = total,
                moneyAccountId = accountId,
                note = note,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            ),
        )
        for (line in priced) write(returnId, line, location, time, now)
        if (payer != null) {
            val draft = MoneyMovementDraft(
                accountId = accountId,
                type = MoneyMovementType.SUPPLIER_REFUND,
                amount = total,
                origin = MoneyOrigin.PurchaseReturn(returnId),
            )
            when (payer.append(draft, time, now)) {
                is MoneyAppendResult.Appended -> Unit

                // Se revisó en esta misma transacción: no puede haber desaparecido.
                MoneyAppendResult.AccountNotFound -> return reject(
                    RegisterSupplierReturnResult.AccountUnavailable(AccountIssue.NOT_FOUND),
                )

                MoneyAppendResult.Overflow ->
                    return reject(RegisterSupplierReturnResult.AccountOverflow)
            }
        }
        if (request.resolution == PurchaseReturnResolution.SUPPLIER_CREDIT && total > Money.ZERO) {
            // Con crédito, el proveedor es obligatorio: ya se revisó al elegirlo.
            val credited = credit(checkNotNull(supplierId), returnId, -total, time, now)
            if (!credited) return reject(RegisterSupplierReturnResult.SupplierBalanceTooLarge)
        }
        alerts?.let { ledger.applyAlerts(it, products, time) }
        return TxResult.Commit(RegisterSupplierReturnResult.Registered(returnId, total))
    }

    /**
     * El proveedor de la devolución: el de la compra, o el elegido si la compra no tiene o no hay
     * compra. Elegir otro que el de la compra se rechaza, y dejar crédito exige uno. Un proveedor
     * archivado sirve: archivar no borra lo que se le debe.
     */
    private suspend fun supplierOf(
        request: SupplierReturnRequest,
        purchase: Purchase?,
    ): SupplierChoice {
        val ofPurchase = purchase?.content?.supplierId
        val chosen = request.supplierId
        if (ofPurchase != null && chosen != null && chosen != ofPurchase) {
            return SupplierChoice.Rejected(RegisterSupplierReturnResult.SupplierMismatch)
        }
        val supplierId = ofPurchase ?: chosen
        if (supplierId != null && suppliers.findById(supplierId) == null) {
            return SupplierChoice.Rejected(RegisterSupplierReturnResult.SupplierNotFound)
        }
        if (supplierId == null && request.resolution == PurchaseReturnResolution.SUPPLIER_CREDIT) {
            return SupplierChoice.Rejected(RegisterSupplierReturnResult.SupplierRequiredForCredit)
        }
        return SupplierChoice.Chosen(supplierId)
    }

    /**
     * Lo que vale cada línea y cómo deja el costo de su producto, en orden, con el mismo cálculo que
     * muestra la pantalla. Todo se lee dentro de la transacción, para devolver con el stock y lo ya
     * devuelto de este momento.
     */
    private suspend fun price(
        lines: List<SupplierReturnLineRequest>,
        purchase: Purchase?,
    ): Pricing {
        val purchaseLines = purchase?.let { found ->
            purchases.findLines(found.id).associateBy { it.id }
        }.orEmpty()
        // Cada producto se lee una vez: dos líneas del mismo producto lo comparten.
        val read = HashMap<Long, Product>()
        // Lo que ya toman de cada lote las líneas anteriores de esta devolución.
        val lotTaken = HashMap<Long, QuantityMilli>()
        val pricingLines = ArrayList<SupplierReturnPricingLine>(lines.size)
        for ((index, line) in lines.withIndex()) {
            val purchaseLine = when (line) {
                is SupplierReturnLineRequest.FromPurchaseLine -> when {
                    purchase == null ->
                        return lineRejected(index, SupplierReturnLineRejection.PURCHASE_REQUIRED)

                    else -> purchaseLines[line.purchaseLineId]
                        ?: return lineRejected(index, SupplierReturnLineRejection.NOT_IN_PURCHASE)
                }

                is SupplierReturnLineRequest.Loose -> when {
                    purchase != null ->
                        return lineRejected(index, SupplierReturnLineRejection.NOT_IN_PURCHASE)

                    else -> null
                }
            }
            val productId = purchaseLine?.content?.productId
                ?: (line as SupplierReturnLineRequest.Loose).productId
            val product = read[productId]
                ?: products.findById(productId)?.also { read[productId] = it }
                ?: return lineRejected(index, SupplierReturnLineRejection.PRODUCT_NOT_FOUND)
            productRejection(product, line.quantity)?.let { return lineRejected(index, it) }
            lotRejection(purchase, purchaseLine, line.quantity, lotTaken)?.let {
                return lineRejected(index, it)
            }
            pricingLines += SupplierReturnPricingLine(productId, purchaseLine, line.quantity)
        }
        val taken = purchase?.let { purchaseReturns.takenByPurchase(it.id) }.orEmpty()
        val pricing = SupplierReturnRules.price(
            pricingLines,
            stateOf = { id ->
                read.getValue(id).let { CostState(it.onHand, it.averageCost, it.stockValue) }
            },
            takenOf = { id -> taken[id] ?: LineAmounts.NONE },
        )
        val priced = pricing.lines.mapIndexed { index, linePricing ->
            when (linePricing) {
                is SupplierReturnLinePricing.Priced -> {
                    val line = pricingLines[index]
                    PricedReturnLine(
                        product = read.getValue(line.productId),
                        purchaseLine = line.purchaseLine,
                        quantity = line.quantity,
                        amounts = linePricing.amounts,
                        after = linePricing.after,
                    )
                }

                is SupplierReturnLinePricing.AboveRemaining -> return Pricing.Rejected(
                    RegisterSupplierReturnResult.AboveRemaining(index, linePricing.remaining),
                )

                SupplierReturnLinePricing.TooLarge ->
                    return lineRejected(index, SupplierReturnLineRejection.AMOUNT_TOO_LARGE)

                // La cantidad ya se revisó con el producto: no llega aquí.
                SupplierReturnLinePricing.InvalidQuantity ->
                    return lineRejected(index, SupplierReturnLineRejection.INVALID_QUANTITY)
            }
        }
        val total = pricing.total
            ?: return Pricing.Rejected(RegisterSupplierReturnResult.TotalTooLarge)
        return Pricing.Priced(priced, total)
    }

    /** Por qué no se puede devolver [quantity] milésimas de [product], o `null` si se puede. */
    private suspend fun productRejection(
        product: Product,
        quantity: QuantityMilli,
    ): SupplierReturnLineRejection? = when {
        product.details.stockMode != ProductStockMode.TRACKED ->
            SupplierReturnLineRejection.NOT_STOCKED

        quantity.milli <= 0L || !Limit.LINE_QUANTITY_MILLI.allows(quantity.milli) ->
            SupplierReturnLineRejection.INVALID_QUANTITY

        !quantity.isWholeUnits && !allowsFraction(product) ->
            SupplierReturnLineRejection.FRACTION_NOT_ALLOWED

        else -> null
    }

    /**
     * Por qué no se puede sacar [quantity] del lote de [purchaseLine], o `null` si se puede. Lo
     * devuelto de una línea de compra con lote sale de ese lote, en la ubicación de la compra, y un
     * lote con código nunca da más de lo que tiene. El lote sin asignar sí puede quedar negativo.
     * [taken] suma lo que ya sacaron de cada lote las líneas anteriores.
     */
    private suspend fun lotRejection(
        purchase: Purchase?,
        purchaseLine: PurchaseLine?,
        quantity: QuantityMilli,
        taken: MutableMap<Long, QuantityMilli>,
    ): SupplierReturnLineRejection? {
        val lotId = purchaseLine?.content?.lotId ?: return null
        val lot = lots?.findById(lotId)
        if (lot?.isUnassigned == true) return null
        val location = locationOf(purchase)
        val already = taken[lotId] ?: QuantityMilli.ZERO
        val available = ledger.onHandAt(StockKey(purchaseLine.content.productId, location, lotId))
        if (already + quantity > available) return SupplierReturnLineRejection.ABOVE_LOT_STOCK
        taken[lotId] = already + quantity
        return null
    }

    /** La ubicación de la que sale lo devuelto: la de la compra, o la inicial sin compra. */
    private suspend fun locationOf(purchase: Purchase?): Long = purchase?.content?.locationId
        ?: checkNotNull(systemRows.findId(SystemRow.MAIN_LOCATION)) {
            "Falta una fila inicial de la base"
        }

    private suspend fun allowsFraction(product: Product): Boolean =
        // La unidad base de un producto siempre existe: su llave no deja borrarla.
        (units.findById(product.details.baseUnitId) ?: damaged()).allowsFraction

    /** Guarda la línea y su salida del libro de stock, con el costo que deja en su producto. */
    private suspend fun write(
        returnId: Long,
        line: PricedReturnLine,
        location: Long,
        time: RecordTime,
        now: Instant,
    ) {
        // Sale del lote de su línea de compra; sin él, en un producto con lotes, del sin asignar.
        val lotId = line.purchaseLine?.content?.lotId
            ?: if (line.product.details.tracksLots) {
                ledger.unassignedLot(line.product.id, now)
            } else {
                null
            }
        val lineId = purchaseReturns.insertLine(
            returnId,
            NewPurchaseReturnLine(
                purchaseLineId = line.purchaseLine?.id,
                productId = line.product.id,
                lotId = lotId,
                quantity = line.quantity,
                unitCost = line.amounts.unitCost,
                lineTotal = line.amounts.lineTotal,
            ),
        )
        ledger.append(
            NewStockMovement(
                movement = StockMovementDraft(
                    key = StockKey(line.product.id, location, lotId),
                    type = StockMovementType.PURCHASE_RETURN,
                    quantity = -line.quantity,
                    reversesMovementId = null,
                ),
                reason = null,
                unitCost = line.amounts.unitCost,
                totalCost = -line.amounts.cost,
                balanceAfter = line.after.onHand,
                averageCostAfter = line.after.averageCost,
                stockValueAfter = line.after.stockValue,
                source = StockMovementSource(purchaseReturnLineId = lineId),
                note = null,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            ),
        )
    }

    /**
     * Agrega al libro del proveedor el crédito [amount], negativo, que deja la devolución, y baja
     * lo que se le debe. Devuelve `false` si ese saldo no cabe.
     */
    private suspend fun credit(
        supplierId: Long,
        returnId: Long,
        amount: Money,
        time: RecordTime,
        now: Instant,
    ): Boolean {
        val id = supplierLedger.appendChained(supplierId, amount) { balanceAfter ->
            NewSupplierLedgerEntry(
                supplierId = supplierId,
                type = SupplierLedgerEntryType.RETURN_CREDIT,
                amount = amount,
                balanceAfter = balanceAfter,
                purchaseReturnId = returnId,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            )
        }
        return id != null
    }

    /** Lo que se rechaza sin leer la base: la forma de la devolución. */
    private fun shapeRejection(
        request: SupplierReturnRequest,
        note: String?,
    ): RegisterSupplierReturnResult? = when {
        request.lines.isEmpty() -> RegisterSupplierReturnResult.EmptyReturn
        request.lines.size > Limit.DOCUMENT_LINES.max -> RegisterSupplierReturnResult.TooManyLines
        (note?.length ?: 0) > MAX_NOTE_LENGTH -> RegisterSupplierReturnResult.NoteTooLong
        else -> null
    }

    private fun lineRejected(index: Int, reason: SupplierReturnLineRejection): Pricing =
        Pricing.Rejected(RegisterSupplierReturnResult.LineRejected(index, reason))

    private fun reject(
        result: RegisterSupplierReturnResult,
    ): TxResult<RegisterSupplierReturnResult> = TxResult.Rollback(result)

    /** Una fila que la base garantiza y no está: la base está dañada, y no se escribe nada. */
    private fun damaged(): Nothing =
        throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

    /** Una línea con lo que vale y el costo que deja en su producto. */
    private class PricedReturnLine(
        val product: Product,
        val purchaseLine: PurchaseLine?,
        val quantity: QuantityMilli,
        val amounts: SupplierReturnLineAmounts,
        val after: CostState,
    )

    private sealed interface Pricing {
        class Priced(val lines: List<PricedReturnLine>, val total: Money) : Pricing

        class Rejected(val reason: RegisterSupplierReturnResult) : Pricing
    }

    private sealed interface SupplierChoice {
        class Chosen(val supplierId: Long?) : SupplierChoice

        class Rejected(val reason: RegisterSupplierReturnResult) : SupplierChoice
    }

    private companion object {
        /** Largo máximo de la nota de una devolución. */
        const val MAX_NOTE_LENGTH = 200
    }
}
