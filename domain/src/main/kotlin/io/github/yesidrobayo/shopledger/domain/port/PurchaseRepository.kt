package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierLastCost
import kotlinx.coroutines.flow.Flow

/**
 * Las compras y sus líneas. Una compra confirmada no se edita: solo se anula.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface PurchaseRepository {
    /** Las compras de [period], por fecha de negocio, de la más nueva a la más antigua. */
    fun observeIn(period: ReportPeriod): Flow<List<Purchase>>

    fun observeById(id: Long): Flow<Purchase?>

    /** Las líneas de una compra, en el orden en que se guardaron. */
    fun observeLines(purchaseId: Long): Flow<List<PurchaseLine>>

    /**
     * Por cada proveedor, y una vez por las compras sin proveedor, la línea de [productId] en su
     * compra activa más reciente, de la más nueva a la más antigua. Una compra anulada no cuenta.
     */
    fun observeLastCosts(productId: Long): Flow<List<SupplierLastCost>>

    suspend fun findById(id: Long): Purchase?

    /** Las líneas de una compra, en el orden en que se guardaron. */
    suspend fun findLines(purchaseId: Long): List<PurchaseLine>

    /** Guarda la cabecera, activa, y devuelve su id. */
    suspend fun insert(purchase: NewPurchase): Long

    /** Guarda una línea de la compra [purchaseId] y devuelve su id. */
    suspend fun insertLine(purchaseId: Long, line: NewPurchaseLine): Long

    /** Anula una compra activa. Es lo único que cambia en ella después de confirmarse. */
    suspend fun markVoided(id: Long, voiding: Voiding)
}
