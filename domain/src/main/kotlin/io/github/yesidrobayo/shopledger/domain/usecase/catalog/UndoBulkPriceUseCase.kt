package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoPlan
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoRevert
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkUndoRules
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.time.Instant
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
    private val productPrices: ProductPriceRepository? = null,
    // Al final, para que el lote de una prueba se pueda dar como lambda.
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
        val ids = rows.map { it.content.productId }.toSet()
        val current = products.findByIds(ids).filterNot { it.isArchived }
        val defaultList = systemRows.requireId(SystemRow.DEFAULT_PRICE_LIST)
        val own = productPrices?.let { prices -> ids.associateWith { prices.findByProduct(it) } }
        val plan = BulkUndoRules.plan(batchId, rows, current, defaultList, own.orEmpty())
        if (!plan.canUndo) return TxResult.Rollback(UndoBulkPriceResult.NothingToRevert(plan.kept))
        val time = calendar.stamp(clock.now())
            ?: return TxResult.Rollback(UndoBulkPriceResult.UnknownTimeZone)
        val undoBatch = newBatchId()
        for (revert in plan.reverts) {
            val listId = revert.priceListId
            if (listId == null) {
                products.updateSalePrice(revert.productId, revert.to, time.occurredAt)
            } else {
                writeOwnPrice(listId, revert, own.orEmpty(), time.occurredAt)
            }
            priceHistory.append(
                NewPriceChange(
                    productId = revert.productId,
                    priceListId = listId ?: defaultList,
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

    /**
     * Devuelve el precio propio de una lista fija. Si el lote lo creó, porque el producto tomaba el
     * de venta, queda con el precio de antes como propio: el precio que se ve es el mismo.
     */
    private suspend fun writeOwnPrice(
        listId: Long,
        revert: BulkUndoRevert,
        own: Map<Long, Map<Long, Money>>,
        at: Instant,
    ) {
        val prices = requireNotNull(productPrices) { "Una lista fija necesita sus precios" }
        if (own[revert.productId]?.containsKey(listId) == true) {
            prices.update(listId, revert.productId, revert.to, at)
        } else {
            prices.insert(listId, revert.productId, revert.to, at)
        }
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
    private val productPrices: ProductPriceRepository? = null,
    private val systemRows: SystemRowRepository? = null,
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
                    productPrices?.observeAll() ?: flowOf(emptyMap()),
                    flow { emit(systemRows?.findId(SystemRow.DEFAULT_PRICE_LIST)) },
                ) { rows, active, byList, defaultList ->
                    BulkUndoRules.plan(batchId, rows, active, defaultList, byProduct(byList))
                }
            }
        }
        .distinctUntilChanged()
}

/** Los precios propios por lista y producto, dados vuelta: por producto y lista. */
private fun byProduct(byList: Map<Long, Map<Long, Money>>): Map<Long, Map<Long, Money>> {
    val result = mutableMapOf<Long, MutableMap<Long, Money>>()
    byList.forEach { (list, prices) ->
        prices.forEach { (product, price) ->
            result.getOrPut(product) { mutableMapOf() }[list] =
                price
        }
    }
    return result
}
