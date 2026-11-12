package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierLastCost

/**
 * Lo último pagado a cada proveedor por [productId], como lo lee la base: la línea de id mayor en
 * una compra activa, una por proveedor y una por las compras sin proveedor, de la fecha más nueva
 * a la más antigua. Los falsos no conocen los nombres de los proveedores.
 */
internal fun lastCostsOf(
    purchases: Map<Long, Purchase>,
    lines: Map<Long, PurchaseLine>,
    productId: Long,
): List<SupplierLastCost> = lines.values
    .filter { line ->
        line.content.productId == productId &&
            purchases[line.purchaseId]?.status == DocumentStatus.ACTIVE
    }
    .groupBy { purchases.getValue(it.purchaseId).content.supplierId }
    .map { (_, ofSupplier) -> ofSupplier.maxBy { it.id } }
    .map { line ->
        val purchase = purchases.getValue(line.purchaseId)
        SupplierLastCost(
            supplierId = purchase.content.supplierId,
            supplierName = null,
            purchaseId = purchase.id,
            businessDate = purchase.content.businessDate,
            unitId = line.content.unitId,
            unitCost = line.content.unitCost,
        ) to line.id
    }
    .sortedWith(
        compareByDescending<Pair<SupplierLastCost, Long>> {
            it.first.businessDate
        }.thenByDescending { it.second },
    )
    .map { it.first }
