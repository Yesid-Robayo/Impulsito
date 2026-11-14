package io.github.yesidrobayo.shopledger.domain.usecase.production

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
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductionBatchRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.CostReplayResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockCompensation
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockMovementRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockChangeRecorder
import io.github.yesidrobayo.shopledger.domain.usecase.stock.lotShortfallOf

/**
 * Anula una producción en una sola transacción: la marca anulada y cada fila suya del libro recibe
 * su compensación. Si algo falla, no queda nada: la producción sigue activa y ningún libro cambia.
 *
 * - **Los insumos vuelven y lo producido sale.** Cada `PRODUCTION_OUT` y el `PRODUCTION_IN`
 * reciben   un `PRODUCTION_VOID` con la cantidad opuesta, el mismo costo unitario, el costo total
 * opuesto y   la fila que anulan.
 * - **El costo se reproduce, no se resta.** El costo promedio y el valor del stock de cada
 * producto   salen de reproducir su libro efectivo desde el principio, como si la producción no
 * hubiera   existido. Así da igual en qué orden se anulen dos producciones, o una producción y la
 * compra de   un insumo.
 * - **Se compensa una sola vez:** una producción anulada no se anula otra vez, y una fila que ya
 *   tiene su compensatorio no recibe otro.
 * - **Puede dejar lo producido negativo**, si ya se vendió: la venta conserva el costo con que
 * salió,   y la pantalla lo avisa antes de confirmar.
 * - **Todo con la fecha de la anulación**, porque el libro es cronológico.
 * - **Cada fila vuelve a su lote.** Un insumo con lotes regresa al lote de donde salió, y lo
 *   producido sale del lote al que entró. Si parte de un lote con código ya salió, anular se
 *   rechaza con lo que falta, en vez de dejarlo negativo: solo el lote sin asignar puede quedar
 *   así. Con [lots] se reconoce el lote sin asignar; sin él, todo lote se revisa.
 *
 * En la misma transacción evalúa la alerta de stock bajo de cada producto que mueve.
 */
class VoidProductionUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val batches: ProductionBatchRepository,
    ledger: StockLedgerRepository,
    private val alerts: LowStockAlertRepository,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
    private val lots: StockLotRepository? = null,
) {
    private val ledger = StockChangeRecorder(ledger)

    suspend operator fun invoke(
        batchId: Long,
        reason: VoidReason,
        note: String?,
    ): VoidProductionResult = storageSafe(VoidProductionResult::StorageFailed) {
        transactions.write { void(batchId, reason, note) }
    }

    private suspend fun void(
        batchId: Long,
        reason: VoidReason,
        note: String?,
    ): TxResult<VoidProductionResult> {
        ledger.start()
        if (!VoidableDocument.PRODUCTION.allows(reason)) {
            return reject(VoidProductionResult.ReasonNotAllowed)
        }
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        if ((cleanNote?.length ?: 0) > MAX_NOTE_LENGTH) {
            return reject(VoidProductionResult.NoteTooLong)
        }
        val batch = batches.findById(batchId) ?: return reject(VoidProductionResult.NotFound)
        if (batch.status == DocumentStatus.VOIDED) {
            return reject(VoidProductionResult.AlreadyVoided)
        }
        val now = clock.now()
        val time = calendar.stamp(now) ?: return reject(VoidProductionResult.UnknownTimeZone)
        val rows = ledger.findByProductionBatch(batchId).filter {
            it.movement.type == StockMovementType.PRODUCTION_OUT ||
                it.movement.type == StockMovementType.PRODUCTION_IN
        }
        val entries = rows.filter { it.movement.type == StockMovementType.PRODUCTION_IN }
        ledger.lotShortfallOf(entries, lots)?.let { short ->
            return reject(
                VoidProductionResult.LotAlreadyUsed(short.productId, short.lotId, short.missing),
            )
        }
        batches.markVoided(batchId, Voiding(now, reason, cleanNote))
        for ((productId, productRows) in rows.groupBy { it.movement.key.productId }) {
            if (!compensate(productId, productRows, time)) {
                return reject(VoidProductionResult.LedgerDamaged)
            }
        }
        ledger.applyAlerts(alerts, products, time)
        return TxResult.Commit(VoidProductionResult.Voided)
    }

    /**
     * Agrega el compensatorio de cada fila de [rows], todas del producto [productId], en el orden
     * del libro, con el costo que deja reproducir su libro sin ellas. Devuelve `false` si el libro
     * no se puede reproducir: la transacción se deshace y no se anula nada.
     */
    private suspend fun compensate(
        productId: Long,
        rows: List<StockMovementRecord>,
        time: RecordTime,
    ): Boolean {
        // Una fila del libro siempre tiene su producto: su llave no deja borrarlo.
        val product = products.findById(productId) ?: damaged()
        val book = ledger.findByProduct(productId).mapTo(ArrayList(), ::costMovementOf)
        var onHand = product.onHand
        for (row in rows.sortedBy { it.id }) {
            val draft = when (
                val compensation =
                    StockMovementRules.compensationOf(row.movement, ledger.isCompensated(row.id))
            ) {
                is StockCompensation.Ready -> compensation.draft

                // Ya tiene su compensatorio: anular de nuevo lo movería dos veces.
                StockCompensation.AlreadyCompensated -> continue

                // Una fila de producción siempre se compensa.
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
                    occurredAt = time.occurredAt,
                    businessDate = time.businessDate,
                    createdAt = time.occurredAt,
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

    private fun reject(result: VoidProductionResult): TxResult<VoidProductionResult> =
        TxResult.Rollback(result)

    /** Una fila que la base garantiza y no está: la base está dañada, y no se escribe nada. */
    private fun damaged(): Nothing =
        throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

    companion object {
        /** Largo máximo de la nota de una anulación, el mismo que admite la base. */
        const val MAX_NOTE_LENGTH: Int = 200
    }
}

/**
 * Resultado de [VoidProductionUseCase]. Cualquier rechazo deshace la transacción entera: la
 * producción sigue activa y ningún libro recibe compensación.
 */
sealed interface VoidProductionResult {
    /** La producción quedó anulada: los insumos volvieron y lo producido salió. */
    data object Voided : VoidProductionResult

    /** La producción no existe. */
    data object NotFound : VoidProductionResult

    /** Ya estaba anulada: se anula una sola vez, y no se reactiva. */
    data object AlreadyVoided : VoidProductionResult

    /** El motivo no vale para una producción, como una devolución de cliente. */
    data object ReasonNotAllowed : VoidProductionResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : VoidProductionResult

    /** Este teléfono no reconoce la zona del negocio: no hay cómo fechar la anulación. */
    data object UnknownTimeZone : VoidProductionResult

    /**
     * Parte de lo producido que entró al lote [lotId] de [productId] ya salió, por una venta o un
     * ajuste: anular dejaría ese lote con código en negativo. Faltan [missing] milésimas.
     */
    data class LotAlreadyUsed(val productId: Long, val lotId: Long, val missing: QuantityMilli) :
        VoidProductionResult

    /**
     * El libro de un producto no se puede reproducir. La app no escribe libros así; puede venir de
     * una copia dañada.
     */
    data object LedgerDamaged : VoidProductionResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : VoidProductionResult
}
