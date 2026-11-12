package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseDetail
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseDetailLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.VoidStockEffect
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * El detalle de una compra: cabecera, líneas con sus productos y lo que anularla haría con el stock.
 * Vuelve a emitir cuando cambian la compra, sus productos, su proveedor o las unidades, como al
 * anularla o al vender uno de sus productos. Emite `null` si la compra no existe. No escribe nada.
 *
 * Lo que sale al anular es la suma de las líneas de cada producto, y lo que quedaría parte del stock
 * de hoy: así el aviso de stock negativo dice lo mismo que dejará la anulación.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservePurchaseDetailUseCase(
    private val purchases: PurchaseRepository,
    private val products: ProductRepository,
    private val suppliers: SupplierRepository,
    private val units: UnitRepository,
    private val calendar: BusinessCalendarSource,
) {
    operator fun invoke(purchaseId: Long): Flow<PurchaseDetail?> = combine(
        purchases.observeById(purchaseId),
        purchases.observeLines(purchaseId),
        ::Pair,
    ).flatMapLatest { (purchase, lines) ->
        if (purchase == null) return@flatMapLatest flowOf(null)
        val productIds = lines.map { it.content.productId }.distinct()
        val productFlows = productIds.map { products.observeById(it) }
        val productsFlow = if (productFlows.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(productFlows) { it.toList() }
        }
        val supplierFlow = purchase.content.supplierId?.let(suppliers::observeById) ?: flowOf(null)
        combine(
            productsFlow,
            supplierFlow,
            units.observeAll(),
            calendar.observe(),
        ) { found, supplier, allUnits, zone ->
            val voidedOn = purchase.voiding?.let { voiding ->
                when (zone) {
                    is BusinessCalendarResult.Ready -> zone.calendar.businessDateOf(
                        voiding.voidedAt,
                    )

                    is BusinessCalendarResult.UnknownZone -> null
                }
            }
            detailOf(
                purchase,
                lines,
                found.filterNotNull().associateBy { it.id },
                supplier?.details?.name,
                allUnits.associateBy { it.id },
            ).copy(voidedOn = voidedOn)
        }
    }

    private fun detailOf(
        purchase: Purchase,
        lines: List<PurchaseLine>,
        byId: Map<Long, Product>,
        supplierName: String?,
        unitsById: Map<Long, MeasureUnit>,
    ): PurchaseDetail {
        val detailLines = lines.map { line ->
            val product = byId[line.content.productId]
            PurchaseDetailLine(
                line = line,
                productName = product?.details?.name.orEmpty(),
                productArchived = product?.isArchived ?: false,
                unit = unitsById[line.content.unitId],
            )
        }
        val effects = lines.groupBy { it.content.productId }.mapNotNull { (productId, ofProduct) ->
            // Una línea siempre tiene su producto: su llave no deja borrarlo.
            val product = byId[productId] ?: return@mapNotNull null
            val leaving = ofProduct.fold(QuantityMilli.ZERO) { sum, it ->
                sum + it.content.quantity
            }
            VoidStockEffect(
                productId = productId,
                productName = product.details.name,
                unit = unitsById[product.details.baseUnitId],
                leaving = leaving,
                onHandAfter = product.onHand - leaving,
            )
        }
        return PurchaseDetail(purchase, supplierName, detailLines, effects)
    }
}
