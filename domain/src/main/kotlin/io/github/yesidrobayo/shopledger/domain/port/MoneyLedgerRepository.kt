package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyMovement
import kotlinx.coroutines.flow.Flow

/**
 * El libro de dinero. Solo crece: ninguna función edita ni borra un movimiento.
 *
 * Toda lista sale en el orden del libro, por id ascendente, nunca por fecha. Las lecturas que
 * devuelven [Flow] vuelven a emitir con cada transacción confirmada que cambia lo leído. [append]
 * solo se llama dentro de [TransactionRunner.write].
 */
interface MoneyLedgerRepository {
    /** Los movimientos de una cuenta, en el orden del libro: su extracto. */
    fun observeByAccount(accountId: Long): Flow<List<MoneyMovement>>

    suspend fun findById(id: Long): MoneyMovement?

    /** Los movimientos de una cuenta, en el orden del libro. */
    suspend fun findByAccount(accountId: Long): List<MoneyMovement>

    /** Las partes de una transferencia, en el orden del libro. */
    suspend fun findByTransferGroup(groupId: String): List<MoneyMovement>

    /**
     * Los movimientos que salieron del pago a proveedor [supplierLedgerEntryId], en el orden del
     * libro: su `SUPPLIER_PAYMENT`, si se pagó desde una cuenta. La anulación no lleva el pago, solo
     * el movimiento que compensa.
     */
    suspend fun findBySupplierLedgerEntry(supplierLedgerEntryId: Long): List<MoneyMovement>

    /** Si el movimiento [movementId] ya tiene su anulación: uno se anula una sola vez. */
    suspend fun isCompensated(movementId: Long): Boolean

    /**
     * Agrega el movimiento y devuelve su id, que fija su lugar en el orden del libro.
     *
     * En la misma transacción deja a su cuenta con el saldo [NewMoneyMovement.balanceAfter]. Antes
     * comprueba que ese saldo sea el que la cuenta tenía más el monto: si no, el libro y el saldo
     * quedarían descuadrados para siempre, y la transacción se deshace.
     */
    suspend fun append(movement: NewMoneyMovement): Long
}
