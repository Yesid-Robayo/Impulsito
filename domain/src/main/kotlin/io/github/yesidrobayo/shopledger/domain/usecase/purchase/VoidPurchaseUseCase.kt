package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.VoidableDocument
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SupplierLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.CostReplayResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierCompensation
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierLedgerRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockCompensation
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementRules
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.payable.appendChained
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockChangeRecorder
import kotlin.time.Instant

/**
 * Anula una compra activa, en una sola transacción. La compra y sus líneas no se editan ni se
 * borran: la compra pasa a anulada, con el instante, el motivo y la nota, y cada fila de compra del
 * libro recibe su compensatorio.
 *
 * - **Compensa una sola vez.** Cada fila `PURCHASE` recibe una `PURCHASE_VOID` con la cantidad
 *   opuesta, el mismo costo unitario, el costo total opuesto y la fila que anula.
 * - **El costo se reproduce, no se resta.** El costo promedio y el valor del stock de cada producto
 *   salen de reproducir su libro efectivo desde el principio, como si la compra no hubiera
 *   existido. Una resta arrastraría el redondeo, podría dar un valor negativo si parte de lo
 *   comprado ya se vendió, y dependería del orden de las anulaciones.
 * - **Las ventas ya hechas no cambian.** Conservan el costo que congelaron, aunque haya salido de
 *   esta compra: lo vendido sí ocurrió.
 * - **El stock puede quedar negativo.** No se bloquea, porque la compra era un error y lo vendido
 *   es real. La pantalla lo avisa antes de confirmar.
 * - **El stock sale hoy.** Los compensatorios llevan la fecha de la anulación, porque el libro es
 *   cronológico. La compra, en cambio, deja de contar en su propia fecha.
 *
 * - **La deuda con el proveedor se compensa.** El cargo que dejó una compra a plazo recibe su
 *   compensación en el libro del proveedor, con la fecha de la anulación, y baja lo que se le debe.
 *   Los pagos posteriores siguen vigentes: si ya se pagó algo, el saldo puede quedar negativo, y es
 *   crédito con el proveedor.
 *
 * - **El dinero vuelve a su cuenta.** Si lo pagado salió de una cuenta, su `PURCHASE_PAYMENT`
 *   recibe un `MOVEMENT_VOID` con la fecha de la anulación. Los pagos posteriores al proveedor
 *   siguen vigentes: se anulan aparte.
 *
 * - **Con una devolución al proveedor activa no se anula.** Lo devuelto ya salió del stock con su
 *   costo, y anular la compra lo sacaría otra vez. Se devuelve el resto, o se anula primero la
 *   devolución.
 *
 * Un producto archivado no impide anular: su stock sale igual.
 *
 * Con [alerts], evalúa en la misma transacción la alerta de stock bajo de cada producto que mueve,
 * una sola vez por producto. Sin él no la evalúa: solo lo omiten las pruebas que no miran alertas.
 * [supplierLedger] solo hace falta para anular una compra a plazo, y [money] para una pagada desde una
 * cuenta.
 */
class VoidPurchaseUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val products: ProductRepository,
    private val purchases: PurchaseRepository,
    ledger: StockLedgerRepository,
    private val clock: Clock,
    private val alerts: LowStockAlertRepository? = null,
    private val supplierLedger: SupplierLedgerRepository? = null,
    private val money: DocumentMoney? = null,
) {
    private val ledger = StockChangeRecorder(ledger)

    suspend operator fun invoke(
        purchaseId: Long,
        reason: VoidReason,
        note: String?,
    ): VoidPurchaseResult = try {
        transactions.write { void(purchaseId, reason, note) }
    } catch (failure: StorageFailureException) {
        VoidPurchaseResult.StorageFailed(failure.failure)
    }

    private suspend fun void(
        purchaseId: Long,
        reason: VoidReason,
        note: String?,
    ): TxResult<VoidPurchaseResult> {
        ledger.start()
        if (!VoidableDocument.PURCHASE.allows(reason)) {
            return TxResult.Rollback(VoidPurchaseResult.ReasonNotAllowed)
        }
        if ((note?.length ?: 0) > MAX_NOTE_LENGTH) {
            return TxResult.Rollback(VoidPurchaseResult.NoteTooLong)
        }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return TxResult.Rollback(VoidPurchaseResult.UnknownTimeZone(result.storedZoneId))
        }
        val purchase = purchases.findById(purchaseId)
            ?: return TxResult.Rollback(VoidPurchaseResult.PurchaseNotFound)
        if (purchase.status == DocumentStatus.VOIDED) {
            return TxResult.Rollback(VoidPurchaseResult.AlreadyVoided)
        }
        if (purchases.hasActiveReturns(purchaseId)) {
            return TxResult.Rollback(VoidPurchaseResult.HasActiveReturns)
        }

        val now = clock.now()
        purchases.markVoided(purchaseId, Voiding(now, reason, note?.takeIf { it.isNotBlank() }))
        val purchaseRows = ledger.findByPurchase(purchaseId)
            .filter { it.movement.type == StockMovementType.PURCHASE }
        for ((productId, rows) in purchaseRows.groupBy { it.movement.key.productId }) {
            val compensated = compensate(productId, rows, now, calendar)
            if (!compensated) return TxResult.Rollback(VoidPurchaseResult.LedgerDamaged)
        }
        if (!compensateSupplier(purchase, now, calendar)) {
            return TxResult.Rollback(VoidPurchaseResult.LedgerDamaged)
        }
        if (purchase.content.moneyAccountId != null) {
            val documentMoney =
                checkNotNull(money) { "Anular el pago de una compra pide sus cuentas" }
            if (!documentMoney.reversePurchase(purchaseId, calendar.stamp(now), now)) {
                return TxResult.Rollback(VoidPurchaseResult.LedgerDamaged)
            }
        }
        alerts?.let { ledger.applyAlerts(it, products, calendar.stamp(now)) }
        return TxResult.Commit(VoidPurchaseResult.Voided)
    }

    /**
     * Agrega el compensatorio de cada fila de [rows], todas del producto [productId], en el orden
     * del libro. Devuelve `false` si el libro del producto no se puede reproducir: la transacción se
     * deshace y no se anula nada.
     */
    private suspend fun compensate(
        productId: Long,
        rows: List<StockMovementRecord>,
        now: Instant,
        calendar: BusinessCalendar,
    ): Boolean {
        val product = checkNotNull(products.findById(productId)) {
            "Una fila del libro siempre tiene su producto"
        }
        // El libro del producto se lee una vez; cada compensatorio se suma a la copia en memoria,
        // con el id que le dio la base, para reproducir el siguiente con todos los anteriores.
        val book = ledger.findByProduct(productId).mapTo(ArrayList(), ::costMovementOf)
        var onHand = product.onHand
        val today = calendar.businessDateOf(now)
        for (row in rows.sortedBy { it.id }) {
            val alreadyCompensated = ledger.isCompensated(row.id)
            val draft = when (
                val compensation = StockMovementRules.compensationOf(
                    row.movement,
                    alreadyCompensated,
                )
            ) {
                is StockCompensation.Ready -> compensation.draft

                // Ya salió del stock: anular de nuevo lo descontaría dos veces.
                StockCompensation.AlreadyCompensated -> continue

                // Una fila de compra siempre se compensa.
                StockCompensation.NotCompensable -> error("Una fila de compra siempre se anula")
            }
            val totalCost = -row.totalCost
            // El id definitivo lo da la base; para reproducir basta uno mayor que todos, y el
            // compensatorio queda fuera del libro efectivo junto con la fila que anula.
            val provisional = CostMovement(
                id = Long.MAX_VALUE,
                type = draft.type,
                quantity = draft.quantity,
                unitCost = row.unitCost,
                totalCost = totalCost,
                reversesMovementId = row.id,
            )
            val state = when (
                val replay = WeightedAverageCost.replayCostState(
                    product.initialCost,
                    book + provisional,
                )
            ) {
                is CostReplayResult.Replayed -> replay.state
                is CostReplayResult.InvalidMovement, is CostReplayResult.Overflow -> return false
            }
            onHand += draft.quantity
            val id = ledger.append(
                NewStockMovement(
                    movement = draft,
                    reason = null,
                    unitCost = row.unitCost,
                    totalCost = totalCost,
                    balanceAfter = onHand,
                    averageCostAfter = state.averageCost,
                    stockValueAfter = state.stockValue,
                    source = row.source,
                    note = null,
                    occurredAt = now,
                    businessDate = today,
                    createdAt = now,
                ),
            )
            book += provisional.copy(id = id)
        }
        return true
    }

    /**
     * Agrega la compensación de cada fila que la compra dejó en el libro de su proveedor: el cargo
     * de lo que quedó debiendo, si fue a plazo. Una compra pagada completa no tiene ninguna. Devuelve
     * `false` si el saldo que dejaría no cabe: la transacción se deshace y no se anula nada.
     */
    private suspend fun compensateSupplier(
        purchase: Purchase,
        now: Instant,
        calendar: BusinessCalendar,
    ): Boolean {
        if (purchase.content.amountPaid >= purchase.content.total) return true
        val ledger = requireNotNull(supplierLedger) { "Anular una compra a plazo pide su libro" }
        val today = calendar.businessDateOf(now)
        for (entry in ledger.findByPurchase(purchase.id)) {
            val amount = when (
                val compensation = SupplierLedgerRules.compensationOf(
                    entry,
                    ledger.isCompensated(entry.id),
                )
            ) {
                is SupplierCompensation.Ready -> compensation.amount

                // Ya se compensó, o es una compensación: anular de nuevo restaría dos veces.
                SupplierCompensation.AlreadyCompensated,
                SupplierCompensation.NotCompensable,
                -> continue
            }
            val supplierId = entry.content.supplierId
            val id = ledger.appendChained(supplierId, amount) { balanceAfter ->
                NewSupplierLedgerEntry(
                    supplierId = supplierId,
                    type = SupplierLedgerEntryType.ENTRY_VOID,
                    amount = amount,
                    balanceAfter = balanceAfter,
                    reversesEntryId = entry.id,
                    occurredAt = now,
                    businessDate = today,
                    createdAt = now,
                )
            }
            if (id == null) return false
        }
        return true
    }

    private fun costMovementOf(record: StockMovementRecord): CostMovement = CostMovement(
        id = record.id,
        type = record.movement.type,
        quantity = record.movement.quantity,
        unitCost = record.unitCost,
        totalCost = record.totalCost,
        reversesMovementId = record.movement.reversesMovementId,
    )

    private companion object {
        /** Largo máximo de la nota de una anulación. */
        const val MAX_NOTE_LENGTH = 200
    }
}
