package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoPlan
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkUndoRules
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** Cómo termina deshacer una actualización masiva. Salvo [Undone], no se escribió nada. */
sealed interface UndoBulkPriceResult {
    /** Volvieron [reverted] precios; [kept] se dejaron como estaban porque cambiaron después. */
    data class Undone(val reverted: Int, val kept: Int) : UndoBulkPriceResult

    /** El lote ya no es el último que se puede deshacer: se deshizo, o llegó otro después. */
    data object NotUndoable : UndoBulkPriceResult

    /** Todos los productos del lote cambiaron después: no hay precio que vuelva. */
    data class NothingToRevert(val kept: Int) : UndoBulkPriceResult

    data object UnknownTimeZone : UndoBulkPriceResult

    data class StorageFailed(val failure: StorageFailure) : UndoBulkPriceResult
}

/**
 * Deshace la última actualización masiva, [invoke] con su lote, en una sola transacción: cada precio
 * que sigue igual al que dejó el lote vuelve al anterior, con una fila `BULK_UNDO` y un lote nuevo;
 * los que cambiaron después se dejan como están y se cuentan. Deshacer no se deshace, y ninguna
 * venta pasada cambia.
 */
class UndoBulkPriceUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val systemRows: SystemRowRepository,
    private val priceHistory: PriceHistoryRepository,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
    private val newBatchId: () -> Uuid = { Uuid.random() },
) {
    suspend operator fun invoke(batchId: Uuid): UndoBulkPriceResult =
        storageSafe(UndoBulkPriceResult::StorageFailed) {
            transactions.write { undo(batchId) }
        }

    private suspend fun undo(batchId: Uuid): TxResult<UndoBulkPriceResult> {
        if (priceHistory.findUndoableBatch() != batchId) {
            return TxResult.Rollback(UndoBulkPriceResult.NotUndoable)
        }
        val rows = priceHistory.findByBatch(batchId)
        val current = products
            .findByIds(rows.map { it.content.productId }.toSet())
            .filterNot { it.isArchived }
        val plan = BulkUndoRules.plan(batchId, rows, current)
        if (!plan.canUndo) return TxResult.Rollback(UndoBulkPriceResult.NothingToRevert(plan.kept))
        val time = calendar.stamp(clock.now())
            ?: return TxResult.Rollback(UndoBulkPriceResult.UnknownTimeZone)
        val undoBatch = newBatchId()
        val priceList = systemRows.requireId(SystemRow.DEFAULT_PRICE_LIST)
        for (revert in plan.reverts) {
            products.updateSalePrice(revert.productId, revert.to, time.occurredAt)
            priceHistory.append(
                NewPriceChange(
                    productId = revert.productId,
                    priceListId = priceList,
                    oldPrice = revert.from,
                    newPrice = revert.to,
                    source = PriceChangeSource.BULK_UNDO,
                    batchId = undoBatch,
                    bulkRule = null,
                    occurredAt = time.occurredAt,
                    businessDate = time.businessDate,
                ),
            )
        }
        return TxResult.Commit(UndoBulkPriceResult.Undone(plan.reverts.size, plan.kept))
    }
}

/**
 * Lo que deshacer haría ahora, para ofrecerlo en la pantalla: `null` si no hay lote que deshacer.
 * Vuelve a emitir con cada precio o fila del historial que cambie. Un archivado no está entre los
 * vigentes, así que se cuenta como dejado.
 */
class ObserveBulkUndoUseCase(
    private val priceHistory: PriceHistoryRepository,
    private val products: ProductRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<BulkUndoPlan?> = priceHistory
        .observeUndoableBatch()
        .flatMapLatest { batchId ->
            if (batchId == null) {
                flowOf<BulkUndoPlan?>(null)
            } else {
                combine(
                    flow { emit(priceHistory.findByBatch(batchId)) },
                    products.observe(ArchiveFilter.ACTIVE),
                ) { rows, active -> BulkUndoRules.plan(batchId, rows, active) }
            }
        }
        .distinctUntilChanged()
}
