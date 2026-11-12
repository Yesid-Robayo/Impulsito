package io.github.yesidrobayo.shopledger.domain.model.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo último que se le pagó a un proveedor por un producto: la línea de la compra activa más reciente
 * con ese proveedor. [supplierId] y [supplierName] faltan en las compras sin proveedor, que cuentan
 * como uno más. [unitCost] va por unidad entera de [unitId], como en el documento: una caja, si se
 * compró por caja.
 */
data class SupplierLastCost(
    val supplierId: Long?,
    val supplierName: String?,
    val purchaseId: Long,
    val businessDate: BusinessDate,
    val unitId: Long,
    val unitCost: Money,
)
