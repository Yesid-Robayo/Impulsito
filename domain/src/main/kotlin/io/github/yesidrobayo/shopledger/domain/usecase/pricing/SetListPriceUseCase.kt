package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.lock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock

/**
 * Cambia el precio de un producto en una lista, y deja la fila `MANUAL` de su historial con esa
 * lista, en una sola transacción.
 *
 * - En la inicial, el precio es el precio de venta del producto: se cambia ahí, y no se quita.
 * - En una fija, el precio queda propio de la lista. Con `null` se quita, y el producto vuelve a
 *   tomar el precio de venta: la fila del historial va del precio propio al de venta.
 * - Una relativa no se escribe: se calcula desde su base.
 *
 * El historial cuenta el precio que se ve: un producto sin precio propio en una fija tenía el precio
 * de venta, y ese es su precio anterior. Pedir el precio que ya tiene no escribe nada. Ninguna venta
 * pasada cambia: cada línea congeló su precio y su lista.
 */
class SetListPriceUseCase(
    private val transactions: TransactionRunner,
    private val priceLists: PriceListRepository,
    private val productPrices: ProductPriceRepository,
    private val products: ProductRepository,
    private val priceHistory: PriceHistoryRepository,
    private val calendar: BusinessCalendarSource,
    private val currencyLock: CurrencyLock,
    private val clock: Clock,
) {
    /** Deja [price] como precio de [productId] en [priceListId], o quita el propio con `null`. */
    suspend operator fun invoke(
        priceListId: Long,
        productId: Long,
        price: Money?,
    ): SetListPriceResult = storageSafe(SetListPriceResult::StorageFailed) {
        transactions.write { set(priceListId, productId, price) }
    }

    private suspend fun set(
        priceListId: Long,
        productId: Long,
        price: Money?,
    ): TxResult<SetListPriceResult> {
        if (price != null && (price < Money.ZERO || !Limit.UNIT_MONEY.allows(price.minor))) {
            return TxResult.Rollback(SetListPriceResult.PriceOutOfRange)
        }
        val list = priceLists.findById(priceListId)
            ?: return TxResult.Rollback(SetListPriceResult.ListNotFound)
        val refused =
            when {
                list.isArchived -> SetListPriceResult.ListArchived
                list.pricing is PriceListPricing.Relative -> SetListPriceResult.ListIsCalculated
                list.isDefault && price == null -> SetListPriceResult.DefaultPriceRequired
                !list.isDefault && price == Money.ZERO -> SetListPriceResult.ZeroOwnPrice
                else -> null
            }
        if (refused != null) return TxResult.Rollback(refused)
        val product = products.findById(productId)
            ?: return TxResult.Rollback(SetListPriceResult.ProductNotFound)
        if (product.isArchived) return TxResult.Rollback(SetListPriceResult.ProductArchived)
        val own = if (list.isDefault) null else productPrices.find(list.id, product.id)
        val before = own ?: product.salePrice
        val after = price ?: product.salePrice
        // Sin precio propio que quitar, o con el mismo precio que ya se ve, no hay nada que hacer.
        if ((price == null && own == null) || (price != null && price == before)) {
            return TxResult.Rollback(SetListPriceResult.Unchanged)
        }
        val time = calendar.stamp(clock.now())
            ?: return TxResult.Rollback(SetListPriceResult.UnknownTimeZone)
        if (!currencyLock.lock(time.occurredAt)) {
            return TxResult.Rollback(SetListPriceResult.CurrencyUnconfirmed)
        }
        store(list, product, own, price, time)
        // Quitar un precio propio igual al de venta no cambia el precio que se ve: no va al historial.
        if (after == before) return TxResult.Commit(SetListPriceResult.Changed(historyId = null))
        val historyId = priceHistory.append(
            NewPriceChange(
                productId = product.id,
                priceListId = list.id,
                oldPrice = before,
                newPrice = after,
                source = PriceChangeSource.MANUAL,
                batchId = null,
                bulkRule = null,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
            ),
        )
        return TxResult.Commit(SetListPriceResult.Changed(historyId))
    }

    /** Escribe el precio donde vive: el de venta en la inicial, o el propio de la lista fija. */
    private suspend fun store(
        list: PriceList,
        product: Product,
        own: Money?,
        price: Money?,
        time: RecordTime,
    ) {
        when {
            list.isDefault -> {
                products.updateSalePrice(product.id, requireNotNull(price), time.occurredAt)
                // Cambiar el precio de venta es revisarlo: sale de la cola de costo nuevo.
                if (product.priceReviewSince != null) {
                    products.setPriceReviewSince(
                        product.id,
                        since = null,
                        updatedAt = time.occurredAt,
                    )
                }
            }

            price == null -> productPrices.delete(list.id, product.id)

            own == null -> productPrices.insert(list.id, product.id, price, time.occurredAt)

            else -> productPrices.update(list.id, product.id, price, time.occurredAt)
        }
    }
}
