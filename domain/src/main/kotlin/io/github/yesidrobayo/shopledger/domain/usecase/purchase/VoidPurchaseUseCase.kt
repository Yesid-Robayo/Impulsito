package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.VoidableDocument
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
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
 * Un producto archivado no impide anular: su stock sale igual. Por ahora toda compra va pagada al
 * registrarla y sin movimientos de dinero ni libro del proveedor, así que no hay nada más que
 * compensar.
 */
class VoidPurchaseUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val products: ProductRepository,
    private val purchases: PurchaseRepository,
    private val ledger: StockLedgerRepository,
    private val clock: Clock,
) {
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

        val now = clock.now()
        purchases.markVoided(purchaseId, Voiding(now, reason, note?.takeIf { it.isNotBlank() }))
        val purchaseRows = ledger.findByPurchase(purchaseId)
            .filter { it.movement.type == StockMovementType.PURCHASE }
        for ((productId, rows) in purchaseRows.groupBy { it.movement.key.productId }) {
            val compensated = compensate(productId, rows, now, calendar)
            if (!compensated) return TxResult.Rollback(VoidPurchaseResult.LedgerDamaged)
        }
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
