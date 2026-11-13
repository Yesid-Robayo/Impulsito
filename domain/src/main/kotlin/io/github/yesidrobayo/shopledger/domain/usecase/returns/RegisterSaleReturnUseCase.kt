package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleReturnRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerEntryIssue
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.returns.ReturnShare
import io.github.yesidrobayo.shopledger.domain.rule.returns.ReturnShareCheck
import io.github.yesidrobayo.shopledger.domain.rule.returns.ReturnShares
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerEntryOutcome
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockChangeRecorder
import kotlin.time.Instant

/**
 * Devuelve parte de una venta activa, en una sola transacción: la devolución, sus líneas, las
 * entradas del libro de stock y el dinero, o nada.
 *
 * - **El costo es el de la venta.** Cada línea devuelta se lleva su parte de lo neto, del impuesto y
 *   del costo que la línea vendida congeló, con cantidades acumuladas: devolver de a poco suma
 *   exactamente lo de la línea. Nunca sale al costo promedio de hoy, que cambiaría la ganancia de
 *   la venta.
 * - **Lo que vuelve al stock entra al libro** con un `SALE_RETURN` por línea, al costo devuelto,
 *   como una entrada: el costo promedio y el valor del stock suben con él. Lo que no vuelve, porque
 *   se perdió, no deja fila, y su costo sigue en el costo de lo vendido. Un producto sin stock no
 *   deja fila nunca, pero su costo sí sale si la persona lo da por recuperado.
 * - **Nunca más de lo vendido.** Por línea, lo devuelto en devoluciones activas no pasa de lo que se
 *   vendió, y la cantidad respeta si la unidad base admite fracciones.
 * - **El dinero sale de su cuenta.** Con una cuenta elegida, un `REFUND` por el total devuelto, en
 *   la moneda del negocio y en la caja abierta de esa cuenta si la hay. La cuenta tiene que existir,
 *   estar vigente y llevar la moneda del negocio: si no, no se registra nada, porque la persona la
 *   eligió y puede elegir otra. Sin cuenta, el dinero se entrega por fuera y no se escribe nada.
 * - **O queda a favor del cliente.** Con saldo a favor, un `RETURN_CREDIT` por el total en el libro
 *   del cliente de la venta, o del que se elija si la venta no tiene: su saldo baja, y lo consume
 *   una venta a crédito. Sin cliente no hay saldo a favor, y uno archivado o anonimizado ya no lo
 *   recibe. Una venta a crédito devuelta así reduce su deuda.
 * - **Los puntos no cambian**: una devolución no recalcula lo ganado ni lo canjeado en la venta.
 * - **Cuenta hoy.** La devolución es un hecho nuevo y lleva la fecha en que se registra, no la de la
 *   venta: el mes de la venta conserva sus cifras.
 *
 * Un producto archivado se devuelve igual, y su stock entra aunque siga archivado.
 *
 * Con [alerts], evalúa en la misma transacción la alerta de stock bajo de cada producto que vuelve,
 * una sola vez por producto.
 */
class RegisterSaleReturnUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val products: ProductRepository,
    private val sales: SaleRepository,
    private val returns: SaleReturnRepository,
    private val units: UnitRepository,
    ledger: StockLedgerRepository,
    private val clock: Clock,
    private val alerts: LowStockAlertRepository? = null,
    moneyAccounts: MoneyAccountRepository? = null,
    moneyLedger: MoneyLedgerRepository? = null,
    private val customers: CustomerRepository? = null,
    customerLedger: CustomerLedgerRepository? = null,
) {
    private val customerLedger = customerLedger?.let(::CustomerLedgerWriter)
    private val ledger = StockChangeRecorder(ledger)
    private val money = moneyAccounts?.let { accounts ->
        moneyLedger?.let { book -> DocumentMoney(accounts, book) }
    }

    suspend operator fun invoke(request: SaleReturnRequest): RegisterSaleReturnResult = try {
        transactions.write { register(request) }
    } catch (failure: StorageFailureException) {
        RegisterSaleReturnResult.StorageFailed(failure.failure)
    }

    private suspend fun register(request: SaleReturnRequest): TxResult<RegisterSaleReturnResult> {
        ledger.start()
        shapeRejection(request)?.let { return TxResult.Rollback(it) }
        val current = settings.get()
        val calendar = when (val result = BusinessCalendar.of(current.regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone -> return TxResult.Rollback(
                RegisterSaleReturnResult.UnknownTimeZone(result.storedZoneId),
            )
        }
        val sale = sales.findById(request.saleId)
            ?: return TxResult.Rollback(RegisterSaleReturnResult.SaleNotFound)
        if (sale.status == DocumentStatus.VOIDED) {
            return TxResult.Rollback(RegisterSaleReturnResult.SaleVoided)
        }
        val returned = when (val result = shareLines(sale, request.lines)) {
            is Shared.Lines -> result.lines
            is Shared.Rejected -> return TxResult.Rollback(result.rejection)
        }
        val totals = Totals.of(returned)
        val settlement = request.settlement
        val accountId = when (settlement) {
            is ReturnSettlement.Refund ->
                settlement.moneyAccountId.takeIf { totals.total > Money.ZERO }

            is ReturnSettlement.StoreCredit -> null
        }
        val creditCustomerId = when (settlement) {
            is ReturnSettlement.Refund -> null

            is ReturnSettlement.StoreCredit -> {
                val chosen = creditCustomerOf(sale, settlement)
                when (chosen) {
                    is CreditCustomer.Chosen -> chosen.customerId
                    is CreditCustomer.Rejected -> return TxResult.Rollback(chosen.rejection)
                }
            }
        }
        if (accountId != null) {
            val issue = checkNotNull(money) { "Devolver desde una cuenta necesita las cuentas" }
                .issueOf(accountId, current.regional.currency)
            if (issue != null) {
                return TxResult.Rollback(RegisterSaleReturnResult.AccountUnavailable(issue))
            }
        }

        val now = clock.now()
        val time = calendar.stamp(now)
        val returnId = returns.insert(
            NewSaleReturn(
                saleId = sale.id,
                resolution = when (settlement) {
                    is ReturnSettlement.Refund -> ReturnResolution.REFUND
                    is ReturnSettlement.StoreCredit -> ReturnResolution.STORE_CREDIT
                },
                netRefund = totals.net,
                taxRefund = totals.tax,
                totalRefund = totals.total,
                costReturned = totals.costReturned,
                moneyAccountId = accountId,
                replacementSaleId = null,
                note = request.note?.takeIf { it.isNotBlank() },
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            ),
        )
        for (line in returned) {
            val lineId = returns.insertLine(returnId, line.toNewLine())
            if (line.request.restock && line.sold.content.stockEffect == LineStockEffect.DIRECT) {
                if (!restock(sale, line, lineId, time, now)) {
                    return TxResult.Rollback(RegisterSaleReturnResult.LedgerDamaged)
                }
            }
        }
        if (accountId != null && !refund(accountId, returnId, totals.total, time, now)) {
            return TxResult.Rollback(RegisterSaleReturnResult.LedgerDamaged)
        }
        if (creditCustomerId != null && totals.total > Money.ZERO &&
            !credit(creditCustomerId, returnId, totals.total, time)
        ) {
            return TxResult.Rollback(RegisterSaleReturnResult.LedgerDamaged)
        }
        alerts?.let { ledger.applyAlerts(it, products, time) }
        return TxResult.Commit(RegisterSaleReturnResult.Registered(returnId, totals.total))
    }

    /** Lo que se rechaza sin leer la base: la forma de la devolución. */
    private fun shapeRejection(request: SaleReturnRequest): RegisterSaleReturnResult? {
        val lines = request.lines
        return when {
            lines.isEmpty() -> RegisterSaleReturnResult.NoLines

            lines.size > Limit.DOCUMENT_LINES.max -> RegisterSaleReturnResult.TooManyLines

            (request.note?.length ?: 0) > MAX_NOTE_LENGTH -> RegisterSaleReturnResult.NoteTooLong

            else -> {
                val seen = HashSet<Long>()
                lines.indexOfFirst { !seen.add(it.saleLineId) }.takeIf { it >= 0 }?.let {
                    RegisterSaleReturnResult.LineRejected(it, ReturnLineRejection.DUPLICATE)
                }
            }
        }
    }

    /**
     * La parte de cada línea pedida, leída dentro de la transacción: lo vendido, lo que ya
     * devolvieron las devoluciones activas y la unidad base de cada producto son los que hay al
     * confirmar.
     */
    private suspend fun shareLines(sale: Sale, requested: List<ReturnLineRequest>): Shared {
        val soldLines = sales.findLines(sale.id).associateBy { it.id }
        val taken = returns.takenBySale(sale.id)
        val productIds = requested.mapNotNull { soldLines[it.saleLineId]?.content?.productId }
        val byId = products.findByIds(productIds.toSet()).associateBy { it.id }
        val fractionByUnit = HashMap<Long, Boolean>()
        val lines = ArrayList<ReturnedLine>(requested.size)
        for ((index, request) in requested.withIndex()) {
            val sold = soldLines[request.saleLineId]
                ?: return rejected(index, ReturnLineRejection.NOT_IN_SALE)
            val content = sold.content
            if (content.stockEffect == LineStockEffect.COMPONENTS) {
                return rejected(index, ReturnLineRejection.COMPONENTS_NOT_SUPPORTED)
            }
            // La línea apunta a su producto con una llave que no deja borrarlo.
            val product = byId[content.productId] ?: damaged()
            val allowsFraction = fractionByUnit.getOrPut(product.details.baseUnitId) {
                // La unidad base de un producto siempre existe: su llave no deja borrarla.
                (units.findById(product.details.baseUnitId) ?: damaged()).allowsFraction
            }
            when (UnitQuantityRule.check(request.quantity, allowsFraction)) {
                UnitQuantityCheck.Valid -> Unit

                UnitQuantityCheck.NotPositive ->
                    return rejected(index, ReturnLineRejection.INVALID_QUANTITY)

                UnitQuantityCheck.FractionNotAllowed ->
                    return rejected(index, ReturnLineRejection.FRACTION_NOT_ALLOWED)
            }
            val amounts =
                LineAmounts(
                    content.quantity,
                    content.netAmount,
                    content.taxAmount,
                    content.lineCost,
                )
            val already = taken[sold.id] ?: LineAmounts.NONE
            val share = when (val check = ReturnShares.of(amounts, already, request.quantity)) {
                is ReturnShareCheck.Share -> check.share

                is ReturnShareCheck.AboveRemaining -> return Shared.Rejected(
                    RegisterSaleReturnResult.AboveRemaining(index, check.remaining),
                )

                // La regla de la unidad ya rechazó una cantidad que no es positiva.
                ReturnShareCheck.NotPositive -> error("La cantidad ya se comprobó")
            }
            lines += ReturnedLine(request, sold, product, share)
        }
        return Shared.Lines(lines)
    }

    /**
     * Agrega el `SALE_RETURN` de [line] al libro de su producto: entra lo devuelto, al costo que
     * congeló la venta, en la ubicación de la venta. Devuelve `false` si el costo no cabe.
     */
    private suspend fun restock(
        sale: Sale,
        line: ReturnedLine,
        returnLineId: Long,
        time: RecordTime,
        now: Instant,
    ): Boolean {
        // Se lee de nuevo: otra línea del mismo producto ya pudo moverlo en esta transacción.
        val product = products.findById(line.product.id) ?: damaged()
        val quantity = line.request.quantity
        val before = CostState(product.onHand, product.averageCost, product.stockValue)
        val after = when (
            val entry = WeightedAverageCost.entry(
                before,
                quantity,
                line.share.cost,
            )
        ) {
            is Checked.Value -> entry.value
            Checked.Overflow, is Checked.AboveLimit -> return false
        }
        ledger.append(
            NewStockMovement(
                movement = StockMovementDraft(
                    key = StockKey(product.id, sale.content.locationId, lotId = null),
                    type = StockMovementType.SALE_RETURN,
                    quantity = quantity,
                    reversesMovementId = null,
                ),
                reason = null,
                unitCost = line.sold.content.unitCost,
                totalCost = line.share.cost,
                balanceAfter = after.onHand,
                averageCostAfter = after.averageCost,
                stockValueAfter = after.stockValue,
                source = StockMovementSource(saleReturnLineId = returnLineId),
                note = null,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            ),
        )
        return true
    }

    /**
     * Saca [total] de la cuenta [accountId] con un `REFUND` ligado a la devolución. Devuelve `false`
     * si el saldo de la cuenta no cabría.
     */
    private suspend fun refund(
        accountId: Long,
        returnId: Long,
        total: Money,
        time: RecordTime,
        now: Instant,
    ): Boolean {
        val draft = MoneyMovementDraft(
            accountId = accountId,
            type = MoneyMovementType.REFUND,
            amount = -total,
            origin = MoneyOrigin.SaleReturn(returnId),
        )
        return when (checkNotNull(money).append(draft, time, now)) {
            is MoneyAppendResult.Appended -> true

            MoneyAppendResult.Overflow -> false

            // Se revisó en esta misma transacción: no puede haber desaparecido.
            MoneyAppendResult.AccountNotFound -> damaged()
        }
    }

    /**
     * El cliente que recibe el saldo a favor: el de la venta, o el elegido si la venta no tiene.
     * Leído dentro de la transacción: uno que se archivó o se anonimizó mientras tanto no recibe
     * saldo nuevo.
     */
    private suspend fun creditCustomerOf(
        sale: Sale,
        settlement: ReturnSettlement.StoreCredit,
    ): CreditCustomer {
        val ofSale = sale.content.customerId
        val chosen = settlement.customerId
        if (ofSale != null && chosen != null && chosen != ofSale) {
            return CreditCustomer.Rejected(RegisterSaleReturnResult.CustomerNotOfSale)
        }
        val customerId = ofSale ?: chosen
            ?: return CreditCustomer.Rejected(RegisterSaleReturnResult.CustomerRequired)
        val customer = checkNotNull(customers) { "Un saldo a favor necesita los clientes" }
            .findById(customerId)
        if (customer == null || customer.isArchived || customer.isAnonymized) {
            return CreditCustomer.Rejected(RegisterSaleReturnResult.CustomerUnavailable)
        }
        return CreditCustomer.Chosen(customerId)
    }

    /**
     * Deja [total] a favor de [customerId] con un `RETURN_CREDIT` ligado a la devolución: su saldo
     * baja, y lo consume una venta a crédito. Devuelve `false` si el saldo no cabría.
     */
    private suspend fun credit(
        customerId: Long,
        returnId: Long,
        total: Money,
        time: RecordTime,
    ): Boolean {
        val writer =
            checkNotNull(customerLedger) { "Un saldo a favor necesita el libro del cliente" }
        val draft = CustomerEntryDraft(
            customerId = customerId,
            type = CustomerLedgerEntryType.RETURN_CREDIT,
            amount = -total,
            source = CustomerEntrySource.SaleReturn(returnId),
            dueDate = null,
            note = null,
        )
        return when (val outcome = writer.append(draft, time)) {
            is CustomerEntryOutcome.Appended -> true

            is CustomerEntryOutcome.Rejected -> when (outcome.issue) {
                CustomerEntryIssue.BALANCE_OVERFLOW -> false

                // La fila se arma aquí con su tipo, su signo y su devolución: otro rechazo es un
                // error de programación, no algo que la persona pueda corregir.
                CustomerEntryIssue.ZERO_AMOUNT, CustomerEntryIssue.WRONG_SIGN,
                CustomerEntryIssue.WRONG_SOURCE, CustomerEntryIssue.NOTE_REQUIRED,
                CustomerEntryIssue.NOTE_TOO_LONG,
                -> error("Saldo a favor mal armado: " + outcome.issue)
            }

            CustomerEntryOutcome.EntryNotFound, CustomerEntryOutcome.AlreadyCompensated,
            CustomerEntryOutcome.NotReversible,
            -> error("Agregar un saldo a favor no compensa ninguna fila")
        }
    }

    /**
     * Una fila que la base siempre tiene y no está: la base está dañada, y la devolución no se
     * escribe. Sale como cualquier falla de la base, sin texto.
     */
    private fun damaged(): Nothing =
        throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

    private fun rejected(index: Int, reason: ReturnLineRejection): Shared =
        Shared.Rejected(RegisterSaleReturnResult.LineRejected(index, reason))

    /** Una línea pedida, con la línea vendida, su producto y la parte que devuelve. */
    private class ReturnedLine(
        val request: ReturnLineRequest,
        val sold: SaleLine,
        val product: Product,
        val share: ReturnShare,
    ) {
        fun toNewLine() = NewSaleReturnLine(
            saleLineId = sold.id,
            quantity = request.quantity,
            netAmount = share.net,
            taxAmount = share.tax,
            lineTotal = share.total,
            lineCost = share.cost,
            restock = request.restock,
        )
    }

    /** El cliente del saldo a favor, o por qué no hay uno. */
    private sealed interface CreditCustomer {
        class Chosen(val customerId: Long) : CreditCustomer

        class Rejected(val rejection: RegisterSaleReturnResult) : CreditCustomer
    }

    private sealed interface Shared {
        class Lines(val lines: List<ReturnedLine>) : Shared

        class Rejected(val rejection: RegisterSaleReturnResult) : Shared
    }

    /**
     * Las sumas de la cabecera. Cada parte sale de una línea de la misma venta, así que ninguna suma
     * pasa del total de la venta y todas caben en el tope de un documento.
     */
    private class Totals(val net: Money, val tax: Money, val costReturned: Money) {
        val total: Money get() = net + tax

        companion object {
            fun of(lines: List<ReturnedLine>): Totals = Totals(
                net = lines.fold(Money.ZERO) { sum, line -> sum + line.share.net },
                tax = lines.fold(Money.ZERO) { sum, line -> sum + line.share.tax },
                costReturned = lines.filter { it.request.restock }
                    .fold(Money.ZERO) { sum, line -> sum + line.share.cost },
            )
        }
    }

    private companion object {
        /** Largo máximo de la nota de una devolución. */
        const val MAX_NOTE_LENGTH = 200
    }
}
