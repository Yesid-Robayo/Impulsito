package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnLine
import kotlinx.coroutines.flow.Flow

/**
 * Las devoluciones a proveedor y sus líneas. Una devolución confirmada no se edita: solo se anula.
 *
 * Toda lista sale en el orden en que se registraron, por id. Las lecturas que devuelven [Flow]
 * vuelven a emitir cada vez que se confirma una transacción que cambia lo leído. Las funciones que
 * escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface PurchaseReturnRepository {
    fun observeById(id: Long): Flow<PurchaseReturn?>

    /** Las líneas de una devolución, en el orden en que se guardaron. */
    fun observeLines(purchaseReturnId: Long): Flow<List<PurchaseReturnLine>>

    /** Las devoluciones de la compra [purchaseId], activas y anuladas. */
    fun observeByPurchase(purchaseId: Long): Flow<List<PurchaseReturn>>

    suspend fun findById(id: Long): PurchaseReturn?

    /** Las líneas de una devolución, en el orden en que se guardaron. */
    suspend fun findLines(purchaseReturnId: Long): List<PurchaseReturnLine>

    /**
     * Lo que ya se llevaron de cada línea de la compra [purchaseId] sus devoluciones activas, por id
     * de la línea de compra: la cantidad, lo que devolvió el proveedor como neto, sin impuesto
     * aparte, y el costo con que salió del stock. Una línea sin devoluciones no aparece. Una
     * devolución anulada no cuenta: lo que devolvía se puede devolver otra vez.
     */
    suspend fun takenByPurchase(purchaseId: Long): Map<Long, LineAmounts>

    /** Lo mismo que [takenByPurchase], y vuelve a emitir con cada devolución nueva o anulada. */
    fun observeTakenByPurchase(purchaseId: Long): Flow<Map<Long, LineAmounts>>

    /** Guarda la cabecera, activa, y devuelve su id. */
    suspend fun insert(purchaseReturn: NewPurchaseReturn): Long

    /** Guarda una línea de la devolución [purchaseReturnId] y devuelve su id. */
    suspend fun insertLine(purchaseReturnId: Long, line: NewPurchaseReturnLine): Long

    /** Anula una devolución activa. Es lo único que cambia en ella después de confirmarse. */
    suspend fun markVoided(id: Long, voiding: Voiding)
}
