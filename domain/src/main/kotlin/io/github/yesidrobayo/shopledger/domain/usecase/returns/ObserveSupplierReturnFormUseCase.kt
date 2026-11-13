package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseReturnRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.returns.ReturnShares
import io.github.yesidrobayo.shopledger.domain.rule.returns.SupplierReturnRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Lo que la devolución a proveedor necesita para armarse en pantalla.
 *
 * @param products los productos que se pueden devolver sin compra: vigentes, con stock propio y sin
 *   lotes, en el orden del catálogo.
 * @param units todas las unidades, también las archivadas, por id.
 * @param suppliers los proveedores vigentes, para elegir uno.
 * @param supplier el proveedor elegido, aunque esté archivado, con su saldo de hoy.
 * @param purchase la compra de origen, si se eligió una y sigue activa.
 */
data class SupplierReturnForm(
    val products: List<Product>,
    val units: Map<Long, MeasureUnit>,
    val suppliers: List<Supplier>,
    val supplier: Supplier?,
    val purchase: ReturnablePurchase?,
)

/** Una compra activa de la que se puede devolver, con cada línea y lo que ya se devolvió de ella. */
data class ReturnablePurchase(val purchase: Purchase, val lines: List<ReturnablePurchaseLine>)

/**
 * Una línea de compra con su producto de hoy, aunque esté archivado, y [taken], lo que ya se llevaron
 * de ella sus devoluciones activas.
 */
data class ReturnablePurchaseLine(
    val line: PurchaseLine,
    val product: Product,
    val taken: LineAmounts,
) {
    /** Lo ya devuelto de esta línea. */
    val returned: QuantityMilli get() = taken.quantity

    /** Lo que todavía se puede devolver de esta línea. */
    val remaining: QuantityMilli
        get() = ReturnShares.remaining(SupplierReturnRules.boughtOf(line.content), taken)
}

/**
 * Lee lo que la devolución a proveedor muestra, y vuelve a emitir con cada cambio: el stock y el
 * costo de los productos, lo devuelto de la compra y el saldo del proveedor. Una compra anulada o que
 * ya no existe no se ofrece: llega sin compra de origen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveSupplierReturnFormUseCase(
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val suppliers: SupplierRepository,
    private val purchases: PurchaseRepository,
    private val purchaseReturns: PurchaseReturnRepository,
) {
    operator fun invoke(purchaseId: Long?, supplierId: Long?): Flow<SupplierReturnForm> = combine(
        products.observe(ArchiveFilter.ACTIVE).map { list ->
            list.filter {
                it.details.stockMode == ProductStockMode.TRACKED && !it.details.tracksLots
            }
        },
        units.observeAll().map { list -> list.associateBy { it.id } },
        suppliers.observe(ArchiveFilter.ACTIVE),
        supplierId?.let(suppliers::observeById) ?: flowOf(null),
        purchaseId?.let(::observePurchase) ?: flowOf(null),
        ::SupplierReturnForm,
    )

    private fun observePurchase(purchaseId: Long): Flow<ReturnablePurchase?> = combine(
        purchases.observeById(purchaseId),
        purchases.observeLines(purchaseId),
        purchaseReturns.observeTakenByPurchase(purchaseId),
        ::Triple,
    ).flatMapLatest { (purchase, lines, taken) ->
        if (purchase == null ||
            purchase.status == DocumentStatus.VOIDED
        ) {
            return@flatMapLatest flowOf(null)
        }
        val productIds = lines.map { it.content.productId }.distinct()
        val productsFlow = if (productIds.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(productIds.map(products::observeById)) { found ->
                found.filterNotNull().associateBy { it.id }
            }
        }
        productsFlow.map { byId ->
            ReturnablePurchase(
                purchase = purchase,
                // La llave de la línea no deja borrar su producto: siempre está.
                lines = lines.mapNotNull { line ->
                    byId[line.content.productId]?.let { product ->
                        ReturnablePurchaseLine(
                            line = line,
                            product = product,
                            taken = taken[line.id] ?: LineAmounts.NONE,
                        )
                    }
                },
            )
        }
    }
}
