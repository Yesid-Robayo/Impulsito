package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import kotlinx.coroutines.flow.Flow

/**
 * El libro de los proveedores: lo que el negocio les debe es la suma de sus filas. Solo crece:
 * ninguna función edita ni borra una fila, y un error se corrige con una fila que lo compensa.
 *
 * Toda lista sale en el orden del libro, por id ascendente, nunca por fecha. Las lecturas que
 * devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que cambia lo leído.
 * [append] solo se llama dentro de [TransactionRunner.write].
 */
interface SupplierLedgerRepository {
    /** Las filas de un proveedor, en el orden del libro: su extracto. */
    fun observeBySupplier(supplierId: Long): Flow<List<SupplierLedgerEntry>>

    suspend fun findById(id: Long): SupplierLedgerEntry?

    /** Lo que dejó la última fila del proveedor, o `null` si todavía no tiene ninguna. */
    suspend fun lastBalanceAfter(supplierId: Long): Money?

    /** Las filas que llevan la compra [purchaseId]: su cargo, si quedó a plazo. */
    suspend fun findByPurchase(purchaseId: Long): List<SupplierLedgerEntry>

    /** Las filas que llevan la devolución [purchaseReturnId]: su crédito, si se resolvió así. */
    suspend fun findByPurchaseReturn(purchaseReturnId: Long): List<SupplierLedgerEntry>

    /** Si la fila [entryId] ya tiene su compensación: una fila se anula una sola vez. */
    suspend fun isCompensated(entryId: Long): Boolean

    /**
     * Agrega la fila al libro y devuelve su id, que fija su lugar en el orden.
     *
     * En la misma transacción deja al proveedor con el saldo que la fila dice tener después. Si ese
     * saldo no es el de la fila anterior más el monto, la fila descuadraría el libro para siempre:
     * sale como regla rota y la transacción se deshace.
     */
    suspend fun append(entry: NewSupplierLedgerEntry): Long
}
