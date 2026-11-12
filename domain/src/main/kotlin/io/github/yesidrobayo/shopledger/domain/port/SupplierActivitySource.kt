package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierPurchase
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierTotals
import kotlinx.coroutines.flow.Flow

/**
 * Lo que un proveedor tiene alrededor: sus compras y los productos que lo tienen como habitual.
 * Solo lee, sobre las tablas de compras, devoluciones, su libro y productos, sin guardar ningún
 * contador aparte. Cada lectura vuelve a emitir cuando se confirma un cambio en esas tablas.
 */
interface SupplierActivitySource {
    /** Cuánto se le compró a [supplierId] y si tiene historia. */
    fun observeTotals(supplierId: Long): Flow<SupplierTotals>

    /** Las compras de [supplierId], también las anuladas, de la más nueva a la más antigua. */
    fun observePurchases(supplierId: Long): Flow<List<SupplierPurchase>>

    /** Los productos vigentes que tienen a [supplierId] como habitual, por nombre. */
    fun observeUsualProducts(supplierId: Long): Flow<List<SupplierProduct>>

    /**
     * Cuántos productos vigentes tiene cada proveedor como habitual, por id del proveedor. Un
     * proveedor sin ninguno no aparece.
     */
    fun observeUsualProductCounts(): Flow<Map<Long, Int>>
}
