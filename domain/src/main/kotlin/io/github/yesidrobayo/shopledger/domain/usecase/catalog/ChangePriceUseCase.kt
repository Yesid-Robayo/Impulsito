package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
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
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock

/** Cómo termina cambiar el precio de un producto. Salvo [Changed], no se escribió nada. */
sealed interface ChangePriceResult {
    /** El precio quedó en el nuevo, con la fila [historyId] de su historial. */
    data class Changed(val historyId: Long) : ChangePriceResult

    /** El precio nuevo es el mismo de ahora: no se crea fila. */
    data object Unchanged : ChangePriceResult

    data object NotFound : ChangePriceResult

    /** Archivado: hay que reactivarlo antes de cambiar su precio. */
    data object ProductArchived : ChangePriceResult

    /** El precio es negativo o pasa del tope de dinero por unidad. */
    data object PriceOutOfRange : ChangePriceResult

    /** La zona del negocio no se reconoce: la fila del historial no tendría fecha. */
    data object UnknownTimeZone : ChangePriceResult

    /** La moneda del negocio no está confirmada, y un precio es dinero. */
    data object CurrencyUnconfirmed : ChangePriceResult

    data class StorageFailed(val failure: StorageFailure) : ChangePriceResult
}

/**
 * Cambia el precio de venta de un producto y deja la fila `MANUAL` de su historial, con el precio de
 * antes y el nuevo, en una sola transacción.
 *
 * Ninguna venta pasada cambia: cada línea congeló su precio al registrarse. El mismo precio no
 * escribe nada, ni cuenta un cambio sin respaldar.
 */
class ChangePriceUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val systemRows: SystemRowRepository,
    private val priceHistory: PriceHistoryRepository,
    private val calendar: BusinessCalendarSource,
    private val currencyLock: CurrencyLock,
    private val clock: Clock,
) {
    suspend operator fun invoke(productId: Long, newPrice: Money): ChangePriceResult =
        storageSafe(ChangePriceResult::StorageFailed) {
            transactions.write { change(productId, newPrice) }
        }

    private suspend fun change(productId: Long, newPrice: Money): TxResult<ChangePriceResult> {
        if (newPrice < Money.ZERO || !Limit.UNIT_MONEY.allows(newPrice.minor)) {
            return TxResult.Rollback(ChangePriceResult.PriceOutOfRange)
        }
        val product = products.findById(productId)
            ?: return TxResult.Rollback(ChangePriceResult.NotFound)
        if (product.isArchived) return TxResult.Rollback(ChangePriceResult.ProductArchived)
        if (newPrice == product.salePrice) return TxResult.Rollback(ChangePriceResult.Unchanged)
        val time = calendar.stamp(clock.now())
            ?: return TxResult.Rollback(ChangePriceResult.UnknownTimeZone)
        // Bajar a cero no es un monto nuevo, pero el de antes ya fijó la moneda: se exige igual.
        if (!currencyLock.lock(time.occurredAt)) {
            return TxResult.Rollback(ChangePriceResult.CurrencyUnconfirmed)
        }
        products.updateSalePrice(product.id, newPrice, time.occurredAt)
        val historyId = priceHistory.append(
            NewPriceChange(
                productId = product.id,
                priceListId = systemRows.requireId(SystemRow.DEFAULT_PRICE_LIST),
                oldPrice = product.salePrice,
                newPrice = newPrice,
                source = PriceChangeSource.MANUAL,
                batchId = null,
                bulkRule = null,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
            ),
        )
        return TxResult.Commit(ChangePriceResult.Changed(historyId))
    }
}
