package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.VoidableDocument
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseReturnRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
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
 * Anula una devolución a proveedor activa, en una sola transacción. La devolución y sus líneas no se
 * editan ni se borran: la devolución pasa a anulada, con el instante, el motivo y la nota, y todo lo
 * que dejó recibe su compensación.
 *
 * - **El stock vuelve.** Cada fila `PURCHASE_RETURN` recibe una `PURCHASE_RETURN_VOID` con la
 *   cantidad opuesta, el mismo costo unitario, el costo total opuesto y la fila que anula.
 * - **El costo se reproduce, no se resta.** El costo promedio y el valor del stock de cada producto
 *   salen de reproducir su libro efectivo desde el principio, como si la devolución no hubiera
 *   existido. Así da igual en qué orden se anulen dos devoluciones, o una devolución y su compra.
 * - **El dinero o el crédito se revierte.** Un reembolso que entró a una cuenta recibe su
 *   `MOVEMENT_VOID`, y un crédito con el proveedor su `ENTRY_VOID`, que vuelve a subir lo que se le
 *   debe. Los pagos posteriores al proveedor siguen vigentes.
 * - **Todo con la fecha de la anulación**, porque los libros son cronológicos. La devolución, en
 *   cambio, deja de contar en su propia fecha.
 * - **Lo devuelto se puede devolver otra vez**, y con la devolución anulada, su compra se puede
 *   anular.
 *
 * Con [alerts], evalúa en la misma transacción la alerta de stock bajo de cada producto que mueve.
 * [money] solo hace falta para anular un reembolso que entró a una cuenta.
 */
class VoidSupplierReturnUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val products: ProductRepository,
    private val purchaseReturns: PurchaseReturnRepository,
    private val supplierLedger: SupplierLedgerRepository,
    ledger: StockLedgerRepository,
    private val clock: Clock,
    private val money: DocumentMoney? = null,
    private val alerts: LowStockAlertRepository? = null,
) {
    private val ledger = StockChangeRecorder(ledger)

    suspend operator fun invoke(
        purchaseReturnId: Long,
        reason: VoidReason,
        note: String?,
    ): VoidSupplierReturnResult = try {
        transactions.write { void(purchaseReturnId, reason, note) }
    } catch (failure: StorageFailureException) {
        VoidSupplierReturnResult.StorageFailed(failure.failure)
    }

    private suspend fun void(
        purchaseReturnId: Long,
        reason: VoidReason,
        note: String?,
    ): TxResult<VoidSupplierReturnResult> {
        ledger.start()
        if (!VoidableDocument.PURCHASE_RETURN.allows(reason)) {
            return TxResult.Rollback(VoidSupplierReturnResult.ReasonNotAllowed)
        }
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        if ((cleanNote?.length ?: 0) > MAX_NOTE_LENGTH) {
            return TxResult.Rollback(VoidSupplierReturnResult.NoteTooLong)
        }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone -> return TxResult.Rollback(
                VoidSupplierReturnResult.UnknownTimeZone(result.storedZoneId),
            )
        }
        val found = purchaseReturns.findById(purchaseReturnId)
            ?: return TxResult.Rollback(VoidSupplierReturnResult.NotFound)
        if (found.status == DocumentStatus.VOIDED) {
            return TxResult.Rollback(VoidSupplierReturnResult.AlreadyVoided)
        }

        val now = clock.now()
        purchaseReturns.markVoided(purchaseReturnId, Voiding(now, reason, cleanNote))
        val rows = ledger.findByPurchaseReturn(purchaseReturnId)
            .filter { it.movement.type == StockMovementType.PURCHASE_RETURN }
        for ((productId, productRows) in rows.groupBy { it.movement.key.productId }) {
            if (!compensate(productId, productRows, now, calendar)) {
                return TxResult.Rollback(VoidSupplierReturnResult.LedgerDamaged)
            }
        }
        val time = calendar.stamp(now)
        if (!compensateSupplier(purchaseReturnId, now, calendar)) {
            return TxResult.Rollback(VoidSupplierReturnResult.LedgerDamaged)
        }
        if (found.content.moneyAccountId != null) {
            val documentMoney =
                checkNotNull(money) { "Anular un reembolso a una cuenta pide sus cuentas" }
            if (!documentMoney.reversePurchaseReturn(purchaseReturnId, time, now)) {
                return TxResult.Rollback(VoidSupplierReturnResult.LedgerDamaged)
            }
        }
        alerts?.let { ledger.applyAlerts(it, products, time) }
        return TxResult.Commit(VoidSupplierReturnResult.Voided)
    }

    /**
     * Agrega el compensatorio de cada fila de [rows], todas del producto [productId], en el orden
     * del libro, con el costo que deja reproducir su libro sin ellas. Devuelve `false` si el libro no
     * se puede reproducir: la transacción se deshace y no se anula nada.
     */
    private suspend fun compensate(
        productId: Long,
        rows: List<StockMovementRecord>,
        now: Instant,
        calendar: BusinessCalendar,
    ): Boolean {
        // Una fila del libro siempre tiene su producto: su llave no deja borrarlo.
        val product = products.findById(productId) ?: damaged()
        val book = ledger.findByProduct(productId).mapTo(ArrayList(), ::costMovementOf)
        var onHand = product.onHand
        val today = calendar.businessDateOf(now)
        for (row in rows.sortedBy { it.id }) {
            val draft = when (
                val compensation =
                    StockMovementRules.compensationOf(row.movement, ledger.isCompensated(row.id))
            ) {
                is StockCompensation.Ready -> compensation.draft

                // Ya volvió al stock: anular de nuevo lo sumaría dos veces.
                StockCompensation.AlreadyCompensated -> continue

                // Una fila de devolución siempre se compensa.
                StockCompensation.NotCompensable -> damaged()
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
     * Compensa el crédito que la devolución dejó con el proveedor, si se resolvió así, y vuelve a
     * subir lo que se le debe. Devuelve `false` si el saldo que dejaría no cabe.
     */
    private suspend fun compensateSupplier(
        purchaseReturnId: Long,
        now: Instant,
        calendar: BusinessCalendar,
    ): Boolean {
        val today = calendar.businessDateOf(now)
        for (entry in supplierLedger.findByPurchaseReturn(purchaseReturnId)) {
            val amount = when (
                val compensation = SupplierLedgerRules.compensationOf(
                    entry,
                    supplierLedger.isCompensated(entry.id),
                )
            ) {
                is SupplierCompensation.Ready -> compensation.amount

                // Ya se compensó, o es una compensación: anular de nuevo sumaría dos veces.
                SupplierCompensation.AlreadyCompensated,
                SupplierCompensation.NotCompensable,
                -> continue
            }
            val supplierId = entry.content.supplierId
            val id = supplierLedger.appendChained(supplierId, amount) { balanceAfter ->
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

    /** Una fila que la base garantiza y no está: la base está dañada, y no se escribe nada. */
    private fun damaged(): Nothing =
        throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

    private companion object {
        /** Largo máximo de la nota de una anulación. */
        const val MAX_NOTE_LENGTH = 200
    }
}

/**
 * Resultado de [VoidSupplierReturnUseCase]. Cualquier rechazo deshace la transacción entera: la
 * devolución sigue activa y ningún libro recibe compensación.
 */
sealed interface VoidSupplierReturnResult {
    /** La devolución quedó anulada: el stock volvió y el dinero o el crédito se revirtió. */
    data object Voided : VoidSupplierReturnResult

    /** La devolución no existe. */
    data object NotFound : VoidSupplierReturnResult

    /** Ya estaba anulada: se anula una sola vez, y no se reactiva. */
    data object AlreadyVoided : VoidSupplierReturnResult

    /** El motivo no vale para una devolución, como una devolución de cliente. */
    data object ReasonNotAllowed : VoidSupplierReturnResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : VoidSupplierReturnResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fechar la anulación. */
    data class UnknownTimeZone(val storedZoneId: String) : VoidSupplierReturnResult

    /**
     * Un libro no se puede compensar: el de stock no se reproduce, o un saldo no cabe. La app no
     * escribe libros así; puede venir de una copia dañada.
     */
    data object LedgerDamaged : VoidSupplierReturnResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : VoidSupplierReturnResult
}
