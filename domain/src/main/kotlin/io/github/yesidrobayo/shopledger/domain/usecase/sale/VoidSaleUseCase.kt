package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
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
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
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
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockChangeRecorder
import kotlin.time.Instant

/**
 * Anula una venta activa, en una sola transacción. La venta, sus líneas y su pago no se editan ni
 * se borran: la venta pasa a anulada, con el instante, el motivo y la nota, y cada fila de venta
 * del libro recibe su compensatorio.
 *
 * - **Compensa una sola vez.** Cada fila `SALE` recibe una `SALE_VOID` con la cantidad opuesta, el
 *   mismo costo unitario, el costo total opuesto y la fila que anula. Se mide por fila, no por
 *   línea, y una fila que ya tiene su compensatorio no recibe otro.
 * - **El costo se reproduce.** El costo promedio y el valor del stock de cada producto salen de
 *   reproducir su libro efectivo desde el principio, como si la venta no hubiera existido. Una
 *   resta arrastraría el redondeo y dependería del orden de las anulaciones. Nada ya guardado se
 *   recalcula: las ventas pasadas conservan su costo.
 * - **El stock vuelve hoy.** Los compensatorios llevan la fecha de la anulación, porque el libro es
 *   cronológico. La venta, en cambio, deja de contar en su propia fecha.
 *
 * - **El crédito se compensa.** Cada cargo `CREDIT_SALE` de la venta recibe su `ENTRY_VOID` en el
 *   libro del cliente, que no lleva la venta: apunta al cargo que compensa. Lo ya abonado queda
 *   como saldo a favor.
 *
 * Una venta con una devolución activa no se anula: lo que falta se devuelve. Un producto archivado
 * no impide anular: su stock vuelve igual.
 *
 * Con [alerts], evalúa en la misma transacción la alerta de stock bajo de cada producto que mueve,
 * una sola vez por producto. Sin él no la evalúa: solo lo omiten las pruebas que no miran alertas.
 */
class VoidSaleUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val products: ProductRepository,
    private val sales: SaleRepository,
    ledger: StockLedgerRepository,
    private val clock: Clock,
    private val alerts: LowStockAlertRepository? = null,
    private val customerLedger: CustomerLedgerRepository? = null,
) {
    private val ledger = StockChangeRecorder(ledger)

    suspend operator fun invoke(saleId: Long, reason: VoidReason, note: String?): VoidSaleResult =
        try {
            transactions.write { void(saleId, reason, note) }
        } catch (failure: StorageFailureException) {
            VoidSaleResult.StorageFailed(failure.failure)
        }

    private suspend fun void(
        saleId: Long,
        reason: VoidReason,
        note: String?,
    ): TxResult<VoidSaleResult> {
        ledger.start()
        if (!VoidableDocument.SALE.allows(reason)) {
            return TxResult.Rollback(VoidSaleResult.ReasonNotAllowed)
        }
        if ((note?.length ?: 0) > MAX_NOTE_LENGTH) {
            return TxResult.Rollback(VoidSaleResult.NoteTooLong)
        }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return TxResult.Rollback(VoidSaleResult.UnknownTimeZone(result.storedZoneId))
        }
        val sale = sales.findById(saleId)
            ?: return TxResult.Rollback(VoidSaleResult.SaleNotFound)
        if (sale.status == DocumentStatus.VOIDED) {
            return TxResult.Rollback(VoidSaleResult.AlreadyVoided)
        }
        if (sales.hasActiveReturns(saleId)) {
            return TxResult.Rollback(VoidSaleResult.HasActiveReturns)
        }

        val now = clock.now()
        sales.markVoided(saleId, Voiding(now, reason, note?.takeIf { it.isNotBlank() }))
        val saleRows = ledger.findBySale(saleId)
            .filter { it.movement.type == StockMovementType.SALE }
        for ((productId, rows) in saleRows.groupBy { it.movement.key.productId }) {
            val compensated = compensate(productId, rows, now, calendar)
            if (!compensated) return TxResult.Rollback(VoidSaleResult.LedgerDamaged)
        }
        if (!reverseCredit(saleId, calendar.stamp(now))) {
            return TxResult.Rollback(VoidSaleResult.LedgerDamaged)
        }
        alerts?.let { ledger.applyAlerts(it, products, calendar.stamp(now)) }
        return TxResult.Commit(VoidSaleResult.Voided)
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

                // Ya volvió al stock: anular de nuevo lo descontaría dos veces.
                StockCompensation.AlreadyCompensated -> continue

                // Una fila de venta siempre se compensa.
                StockCompensation.NotCompensable -> error("Una fila de venta siempre se anula")
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
     * Compensa cada cargo a crédito de la venta en el libro de su cliente, una sola vez. Devuelve
     * `false` si el saldo del cliente desbordaría: la transacción se deshace y no se anula nada.
     */
    private suspend fun reverseCredit(saleId: Long, time: RecordTime): Boolean {
        val book = customerLedger ?: return true
        val writer = CustomerLedgerWriter(book)
        for (charge in book.findBySale(saleId)) {
            when (writer.reverse(charge.customerId, charge.id, note = null, time = time)) {
                is CustomerEntryOutcome.Appended -> Unit

                // Ya se compensó: anular de nuevo lo devolvería dos veces.
                CustomerEntryOutcome.AlreadyCompensated -> Unit

                is CustomerEntryOutcome.Rejected -> return false

                CustomerEntryOutcome.EntryNotFound, CustomerEntryOutcome.NotReversible ->
                    error("Un cargo de la venta siempre existe y se puede compensar")
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

    private companion object {
        /** Largo máximo de la nota de una anulación. */
        const val MAX_NOTE_LENGTH = 200
    }
}
