package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnLine
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseReturnRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Una devolución a proveedor con lo que hace falta para mostrarla y anularla: el nombre de hoy de
 * su proveedor, o `null` sin él, y cada línea con el nombre de hoy de su producto y su unidad base.
 */
data class SupplierReturnDetail(
    val purchaseReturn: PurchaseReturn,
    val supplierName: String?,
    val lines: List<SupplierReturnDetailLine>,
)

/** Una línea de la devolución con su producto y su unidad. */
data class SupplierReturnDetailLine(
    val line: PurchaseReturnLine,
    val productName: String,
    val unit: MeasureUnit?,
)

/**
 * Lee el detalle de una devolución a proveedor, y vuelve a emitir con cada cambio, como su
 * anulación. Emite `null` si la devolución no existe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveSupplierReturnDetailUseCase(
    private val purchaseReturns: PurchaseReturnRepository,
    private val products: ProductRepository,
    private val suppliers: SupplierRepository,
    private val units: UnitRepository,
) {
    operator fun invoke(purchaseReturnId: Long): Flow<SupplierReturnDetail?> = combine(
        purchaseReturns.observeById(purchaseReturnId),
        purchaseReturns.observeLines(purchaseReturnId),
        ::Pair,
    ).flatMapLatest { (found, lines) ->
        if (found == null) return@flatMapLatest flowOf(null)
        val productIds = lines.map { it.content.productId }.distinct()
        val productsFlow = if (productIds.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(productIds.map(products::observeById)) { it.filterNotNull() }
        }
        combine(
            productsFlow,
            found.content.supplierId?.let(suppliers::observeById) ?: flowOf(null),
            units.observeAll(),
        ) { productList, supplier, unitList ->
            val byId = productList.associateBy { it.id }
            val unitById = unitList.associateBy { it.id }
            SupplierReturnDetail(
                purchaseReturn = found,
                supplierName = supplier?.details?.name,
                // La llave de la línea no deja borrar su producto: siempre está.
                lines = lines.mapNotNull { line ->
                    byId[line.content.productId]?.let { product ->
                        SupplierReturnDetailLine(
                            line = line,
                            productName = product.details.name,
                            unit = unitById[product.details.baseUnitId],
                        )
                    }
                },
            )
        }
    }
}
