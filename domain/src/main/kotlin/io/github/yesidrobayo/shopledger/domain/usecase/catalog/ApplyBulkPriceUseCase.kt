package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRule
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPriceOutcome
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPricePreviewer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPriceRules
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkTarget
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlin.uuid.Uuid

/** Cómo termina aplicar una actualización masiva. Salvo [Applied], no se escribió nada. */
sealed interface ApplyBulkPriceResult {
    /** Cambiaron [changed] precios, todos con el lote [batchId]. */
    data class Applied(val batchId: Uuid, val changed: Int) : ApplyBulkPriceResult

    /** Ninguno de los elegidos cambia ya: otra pantalla los cambió o los archivó mientras tanto. */
    data object NothingToChange : ApplyBulkPriceResult

    /**
     * El cálculo está fuera de sus topes, el paso es negativo, o una lista elegida no existe, está
     * archivada o es relativa, que se calcula y no se escribe.
     */
    data object InvalidRequest : ApplyBulkPriceResult

    data object UnknownTimeZone : ApplyBulkPriceResult

    data object CurrencyUnconfirmed : ApplyBulkPriceResult

    data class StorageFailed(val failure: StorageFailure) : ApplyBulkPriceResult
}

/**
 * Aplica una actualización masiva a los productos [productIds] que la vista previa mostró y la
 * persona no quitó: todos o ninguno, en una sola transacción, también con varias listas.
 *
 * Cada precio se vuelve a calcular con el producto y sus precios propios leídos dentro de la
 * transacción, así que un precio cambiado mientras tanto se calcula sobre su valor de ahora, y uno
 * archivado o que ya no cambia se salta. El precio de venta va al producto; el de una lista fija, a
 * su precio propio. Cada fila del historial lleva `BULK`, su lista, el mismo lote y la regla: el
 * modo, su dato y el paso. Ninguna venta pasada cambia.
 */
class ApplyBulkPriceUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val systemRows: SystemRowRepository,
    private val priceHistory: PriceHistoryRepository,
    private val calendar: BusinessCalendarSource,
    private val currencyLock: CurrencyLock,
    private val clock: Clock,
    private val priceLists: PriceListRepository? = null,
    private val productPrices: ProductPriceRepository? = null,
    // Al final, para que el lote de una prueba se pueda dar como lambda.
    private val newBatchId: () -> Uuid = { Uuid.random() },
) {
    /** La actualización por porcentaje de siempre, sobre el precio de venta. */
    suspend operator fun invoke(
        productIds: Collection<Long>,
        percent: BasisPoints,
        roundingStep: Money,
    ): ApplyBulkPriceResult = invoke(
        productIds,
        BulkPriceRequest(BulkPriceScope.All, BulkPriceAdjustment.Percent(percent), roundingStep),
    )

    /**
     * Aplica [request] a [productIds]. De [request] valen el cálculo, el paso y las listas; el
     * alcance ya lo resolvió la vista previa. [context] trae lo que fija el negocio para el margen
     * objetivo y los decimales de la moneda de referencia; las listas y los precios propios se leen
     * aquí.
     */
    suspend operator fun invoke(
        productIds: Collection<Long>,
        request: BulkPriceRequest,
        context: BulkPriceContext = BulkPriceContext(),
    ): ApplyBulkPriceResult = storageSafe(ApplyBulkPriceResult::StorageFailed) {
        transactions.write { apply(productIds, request, context) }
    }

    private suspend fun apply(
        productIds: Collection<Long>,
        request: BulkPriceRequest,
        given: BulkPriceContext,
    ): TxResult<ApplyBulkPriceResult> {
        if (!BulkPriceRules.isValid(request.adjustment) || request.roundingStep < Money.ZERO) {
            return TxResult.Rollback(ApplyBulkPriceResult.InvalidRequest)
        }
        val ids = productIds.toSet()
        val context = readContext(request, ids, given)
            ?: return TxResult.Rollback(ApplyBulkPriceResult.InvalidRequest)
        val targets = BulkPricePreviewer.targetsOf(request, context)
        val changes = products.findByIds(ids)
            .sortedBy { it.id }
            .filterNot { it.isArchived }
            .flatMap { product ->
                targets.mapNotNull { target ->
                    val subject = BulkPricePreviewer.subjectOf(product, target, context)
                    val outcome = BulkPriceRules.outcomeOf(
                        subject,
                        request.adjustment,
                        request.roundingStep,
                        context.scale,
                    )
                    (outcome as? BulkPriceOutcome.Changes)?.let { Planned(product.id, target, it) }
                }
            }
        if (changes.isEmpty()) return TxResult.Rollback(ApplyBulkPriceResult.NothingToChange)
        val time = calendar.stamp(clock.now())
            ?: return TxResult.Rollback(ApplyBulkPriceResult.UnknownTimeZone)
        if (!currencyLock.lock(time.occurredAt)) {
            return TxResult.Rollback(ApplyBulkPriceResult.CurrencyUnconfirmed)
        }
        val batchId = newBatchId()
        val defaultList = systemRows.requireId(SystemRow.DEFAULT_PRICE_LIST)
        val rule = BulkPriceRule(request.adjustment, request.roundingStep)
        for (planned in changes) {
            val change = planned.change
            val listId = planned.target.listId ?: defaultList
            when (val target = planned.target) {
                is BulkTarget.SalePrice ->
                    products.updateSalePrice(planned.productId, change.newPrice, time.occurredAt)

                is BulkTarget.OwnPrice -> {
                    val own =
                        requireNotNull(productPrices) { "Una lista fija necesita sus precios" }
                    val existing = context.ownPrices[planned.productId]?.get(target.listId)
                    if (existing == null) {
                        own.insert(
                            target.listId,
                            planned.productId,
                            change.newPrice,
                            time.occurredAt,
                        )
                    } else {
                        own.update(
                            target.listId,
                            planned.productId,
                            change.newPrice,
                            time.occurredAt,
                        )
                    }
                }
            }
            priceHistory.append(
                NewPriceChange(
                    productId = planned.productId,
                    priceListId = listId,
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

    /**
     * [given] con las listas y los precios propios leídos aquí, o `null` si una lista elegida no
     * existe, está archivada o es relativa. Sin listas elegidas no lee nada más.
     */
    private suspend fun readContext(
        request: BulkPriceRequest,
        ids: Set<Long>,
        given: BulkPriceContext,
    ): BulkPriceContext? {
        if (request.priceListIds.isEmpty()) return given
        val lists = priceLists ?: return null
        val own = productPrices ?: return null
        val all = lists.findAll().associateBy { it.id }
        val usable = request.priceListIds.all { id ->
            val list = all[id]
            list != null && !list.isArchived && list.pricing !is PriceListPricing.Relative
        }
        if (!usable) return null
        return given.copy(lists = all, ownPrices = ids.associateWith { own.findByProduct(it) })
    }

    /** Un precio que cambia: de qué producto, dónde se escribe y de cuánto a cuánto. */
    private class Planned(
        val productId: Long,
        val target: BulkTarget,
        val change: BulkPriceOutcome.Changes,
    )
}
