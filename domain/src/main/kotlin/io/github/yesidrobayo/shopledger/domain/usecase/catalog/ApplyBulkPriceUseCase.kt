package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRule
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
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPriceOutcome
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPricePreviewer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPriceRules
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlin.uuid.Uuid

/** Cómo termina aplicar una actualización masiva. Salvo [Applied], no se escribió nada. */
sealed interface ApplyBulkPriceResult {
    /** Cambiaron [changed] precios, todos con el lote [batchId]. */
    data class Applied(val batchId: Uuid, val changed: Int) : ApplyBulkPriceResult

    /** Ninguno de los elegidos cambia ya: otra pantalla los cambió o los archivó mientras tanto. */
    data object NothingToChange : ApplyBulkPriceResult

    /** El porcentaje es cero o está fuera de los topes, o el paso es negativo. */
    data object InvalidRequest : ApplyBulkPriceResult

    data object UnknownTimeZone : ApplyBulkPriceResult

    data object CurrencyUnconfirmed : ApplyBulkPriceResult

    data class StorageFailed(val failure: StorageFailure) : ApplyBulkPriceResult
}

/**
 * Aplica una actualización masiva por porcentaje a los productos [productIds] que la vista previa
 * mostró y la persona no quitó: todos o ninguno, en una sola transacción.
 *
 * Cada precio se vuelve a calcular con el producto leído dentro de la transacción, así que un precio
 * cambiado mientras tanto se calcula sobre su valor de ahora, y uno archivado o que ya no cambia
 * se salta. Cada fila del historial lleva `BULK`, el mismo lote y la regla: el porcentaje y el paso.
 * Ninguna venta pasada cambia.
 */
class ApplyBulkPriceUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val systemRows: SystemRowRepository,
    private val priceHistory: PriceHistoryRepository,
    private val calendar: BusinessCalendarSource,
    private val currencyLock: CurrencyLock,
    private val clock: Clock,
    private val newBatchId: () -> Uuid = { Uuid.random() },
) {
    suspend operator fun invoke(
        productIds: Collection<Long>,
        percent: BasisPoints,
        roundingStep: Money,
    ): ApplyBulkPriceResult = storageSafe(ApplyBulkPriceResult::StorageFailed) {
        transactions.write { apply(productIds, percent, roundingStep) }
    }

    private suspend fun apply(
        productIds: Collection<Long>,
        percent: BasisPoints,
        roundingStep: Money,
    ): TxResult<ApplyBulkPriceResult> {
        if (!BulkPriceRules.isValidPercent(percent) || roundingStep < Money.ZERO) {
            return TxResult.Rollback(ApplyBulkPriceResult.InvalidRequest)
        }
        val changes = products.findByIds(productIds.toSet())
            .sortedBy { it.id }
            .filterNot { it.isArchived }
            .mapNotNull { product ->
                val outcome = BulkPriceRules.outcome(
                    product.salePrice,
                    BulkPricePreviewer.catalogCost(product),
                    percent,
                    roundingStep,
                )
                (outcome as? BulkPriceOutcome.Changes)?.let { product.id to it }
            }
        if (changes.isEmpty()) return TxResult.Rollback(ApplyBulkPriceResult.NothingToChange)
        val time = calendar.stamp(clock.now())
            ?: return TxResult.Rollback(ApplyBulkPriceResult.UnknownTimeZone)
        if (!currencyLock.lock(time.occurredAt)) {
            return TxResult.Rollback(ApplyBulkPriceResult.CurrencyUnconfirmed)
        }
        val batchId = newBatchId()
        val priceList = systemRows.requireId(SystemRow.DEFAULT_PRICE_LIST)
        val rule = BulkPriceRule(BulkPriceAdjustment.Percent(percent), roundingStep)
        for ((productId, change) in changes) {
            products.updateSalePrice(productId, change.newPrice, time.occurredAt)
            priceHistory.append(
                NewPriceChange(
                    productId = productId,
                    priceListId = priceList,
                    oldPrice = change.oldPrice,
                    newPrice = change.newPrice,
                    source = PriceChangeSource.BULK,
                    batchId = batchId,
                    bulkRule = rule,
                    occurredAt = time.occurredAt,
                    businessDate = time.businessDate,
                ),
            )
        }
        return TxResult.Commit(ApplyBulkPriceResult.Applied(batchId, changes.size))
    }
}
