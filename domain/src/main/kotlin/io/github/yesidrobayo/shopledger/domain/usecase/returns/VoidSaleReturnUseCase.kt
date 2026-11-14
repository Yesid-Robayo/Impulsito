package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.VoidableDocument
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleReturnRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.CostReplayResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockCompensation
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementRules
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerEntryOutcome
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockChangeRecorder
import io.github.yesidrobayo.shopledger.domain.usecase.stock.lotShortfallOf
import kotlin.time.Instant

/**
 * Anula una devolución de venta activa, en una sola transacción. La devolución y sus líneas no se
 * editan ni se borran: la devolución pasa a anulada, con el instante, el motivo y la nota, y todo
 * lo que dejó recibe su compensación.
 *
 * - **La mercancía vuelve a salir.** Cada fila `SALE_RETURN` recibe una `SALE_RETURN_VOID` con la
 *   cantidad opuesta, el mismo costo unitario, el costo total opuesto y la fila que anula. Lo que
 *   no volvió al inventario no dejó fila, y no hay nada que compensar.
 * - **El costo se reproduce, no se resta.** El costo promedio y el valor del stock de cada
 *   producto salen de reproducir su libro efectivo desde el principio, como si la devolución no
 *   hubiera existido. Así da igual en qué orden se anulen dos devoluciones de la misma venta.
 * - **El dinero o el saldo a favor se revierte.** Un reembolso que salió de una cuenta recibe su
 *   `MOVEMENT_VOID`, y un saldo a favor su `ENTRY_VOID` en el libro del cliente, que vuelve a
 *   subir su saldo. Si ya lo gastó en una venta a crédito, queda debiéndolo.
 * - **Los puntos no cambian**, como tampoco cambiaron al devolver.
 * - **Todo con la fecha de la anulación**, porque los libros son cronológicos. La devolución, en
 *   cambio, deja de contar en su propia fecha.
 * - **Lo devuelto se puede devolver otra vez**, y sin devoluciones activas la venta se puede
 *   anular.
 *
 * Un cambio no se deshace a medias: mientras su venta nueva siga activa, su devolución no se anula
 * sola, porque el dinero del cambio quedaría contado dos veces. Se anula primero la venta nueva, y
 * entonces la devolución se anula como cualquier reembolso.
 *
 * Con [alerts], evalúa en la misma transacción la alerta de stock bajo de cada producto que mueve.
 * [money] solo hace falta para anular un reembolso que salió de una cuenta, y [customerLedger]
 * para anular un saldo a favor.
 */
class VoidSaleReturnUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val products: ProductRepository,
    private val sales: SaleRepository,
    private val returns: SaleReturnRepository,
    ledger: StockLedgerRepository,
    private val clock: Clock,
    private val money: DocumentMoney? = null,
    private val customerLedger: CustomerLedgerRepository? = null,
    private val alerts: LowStockAlertRepository? = null,
    private val lots: StockLotRepository? = null,
) {
    private val ledger = StockChangeRecorder(ledger)

    suspend operator fun invoke(
        saleReturnId: Long,
        reason: VoidReason,
        note: String?,
    ): VoidSaleReturnResult = try {
        transactions.write { void(saleReturnId, reason, note) }
    } catch (failure: StorageFailureException) {
        VoidSaleReturnResult.StorageFailed(failure.failure)
    }

    private suspend fun void(
        saleReturnId: Long,
        reason: VoidReason,
        note: String?,
    ): TxResult<VoidSaleReturnResult> {
        ledger.start()
        if (!VoidableDocument.SALE_RETURN.allows(reason)) {
            return TxResult.Rollback(VoidSaleReturnResult.ReasonNotAllowed)
        }
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        if ((cleanNote?.length ?: 0) > MAX_NOTE_LENGTH) {
            return TxResult.Rollback(VoidSaleReturnResult.NoteTooLong)
        }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone -> return TxResult.Rollback(
                VoidSaleReturnResult.UnknownTimeZone(result.storedZoneId),
            )
        }
        val found = returns.findById(saleReturnId)
            ?: return TxResult.Rollback(VoidSaleReturnResult.NotFound)
        if (found.status == DocumentStatus.VOIDED) {
            return TxResult.Rollback(VoidSaleReturnResult.AlreadyVoided)
        }
        found.content.replacementSaleId?.let { replacementId ->
            // La venta nueva de un cambio siempre existe: su llave no deja borrarla.
            val replacement = sales.findById(replacementId) ?: damaged()
            if (replacement.status == DocumentStatus.ACTIVE) {
                return TxResult.Rollback(VoidSaleReturnResult.PartOfExchange(replacementId))
            }
        }

        val rows = ledger.findBySaleReturn(saleReturnId)
            .filter { it.movement.type == StockMovementType.SALE_RETURN }
        ledger.lotShortfallOf(rows, lots)?.let { short ->
            return TxResult.Rollback(
                VoidSaleReturnResult.LotAlreadyUsed(short.productId, short.lotId, short.missing),
            )
        }
        val now = clock.now()
        returns.markVoided(saleReturnId, Voiding(now, reason, cleanNote))
        for ((productId, productRows) in rows.groupBy { it.movement.key.productId }) {
            if (!compensate(productId, productRows, now, calendar)) {
                return TxResult.Rollback(VoidSaleReturnResult.LedgerDamaged)
            }
        }
        val time = calendar.stamp(now)
        if (!reverseCredit(saleReturnId, time)) {
            return TxResult.Rollback(VoidSaleReturnResult.LedgerDamaged)
        }
        if (found.content.moneyAccountId != null) {
            val documentMoney =
                checkNotNull(money) { "Anular un reembolso desde una cuenta pide sus cuentas" }
            if (!documentMoney.reverseSaleReturn(saleReturnId, time, now)) {
                return TxResult.Rollback(VoidSaleReturnResult.LedgerDamaged)
            }
        }
        alerts?.let { ledger.applyAlerts(it, products, time) }
        return TxResult.Commit(VoidSaleReturnResult.Voided)
    }

    /**
     * Agrega el compensatorio de cada fila de [rows], todas del producto [productId], en el orden
     * del libro, con el costo que deja reproducir su libro sin ellas. Devuelve `false` si el libro
     * no se puede reproducir: la transacción se deshace y no se anula nada.
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

                // Ya volvió a salir: anular de nuevo lo descontaría dos veces.
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
     * Compensa el saldo a favor que la devolución dejó al cliente, si se resolvió así, una sola
     * vez. Devuelve `false` si el saldo del cliente no cabría: la transacción se deshace.
     */
    private suspend fun reverseCredit(saleReturnId: Long, time: RecordTime): Boolean {
        val book = customerLedger ?: return true
        val writer = CustomerLedgerWriter(book)
        for (entry in book.findBySaleReturn(saleReturnId)) {
            when (writer.reverse(entry.customerId, entry.id, note = null, time = time)) {
                is CustomerEntryOutcome.Appended -> Unit

                // Ya se compensó: anular de nuevo lo devolvería dos veces.
                CustomerEntryOutcome.AlreadyCompensated -> Unit

                is CustomerEntryOutcome.Rejected -> return false

                // Un saldo a favor de la devolución siempre existe y no es un compensatorio.
                CustomerEntryOutcome.EntryNotFound, CustomerEntryOutcome.NotReversible ->
                    damaged()
            }
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
 * Resultado de [VoidSaleReturnUseCase]. Cualquier rechazo deshace la transacción entera: la
 * devolución sigue activa y ningún libro recibe compensación.
 */
sealed interface VoidSaleReturnResult {
    /** La devolución quedó anulada: la mercancía salió otra vez y el dinero o el saldo volvió. */
    data object Voided : VoidSaleReturnResult

    /** La devolución no existe. */
    data object NotFound : VoidSaleReturnResult

    /** Ya estaba anulada: se anula una sola vez, y no se reactiva. */
    data object AlreadyVoided : VoidSaleReturnResult

    /** El motivo no vale para una devolución, como una devolución de cliente. */
    data object ReasonNotAllowed : VoidSaleReturnResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : VoidSaleReturnResult

    /**
     * La devolución es parte de un cambio, y su venta nueva [replacementSaleId] sigue activa: se
     * anula primero esa venta, y después la devolución.
     */
    data class PartOfExchange(val replacementSaleId: Long) : VoidSaleReturnResult

    /** El teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fechar nada. */
    data class UnknownTimeZone(val storedZoneId: String) : VoidSaleReturnResult

    /**
     * Un libro no se puede compensar: el de stock no se reproduce, o un saldo no cabe. La app no
     * escribe libros así; puede venir de una copia dañada.
     */
    data object LedgerDamaged : VoidSaleReturnResult

    /**
     * Lo que volvió al lote [lotId] del producto [productId] ya salió otra vez. Anular lo sacaría
     * del mismo lote y lo dejaría negativo, y un lote con código nunca queda negativo. [missing] es
     * lo que falta en ese lote para anular.
     */
    data class LotAlreadyUsed(val productId: Long, val lotId: Long, val missing: QuantityMilli) :
        VoidSaleReturnResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : VoidSaleReturnResult
}
