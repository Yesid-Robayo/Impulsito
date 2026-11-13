package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
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

    /**
     * Los movimientos que salieron del pago de venta [salePaymentId], en el orden del libro: su
     * `SALE_RECEIPT` y su `FEE`, si el pago entró en una cuenta. La anulación no lleva el pago, solo
     * el movimiento que compensa.
     */
    suspend fun findBySalePayment(salePaymentId: Long): List<MoneyMovement>

    /**
     * Los movimientos del gasto [expenseId], en el orden del libro: su `EXPENSE_PAYMENT`, si salió de
     * una cuenta.
     */
    suspend fun findByExpense(expenseId: Long): List<MoneyMovement>

    /**
     * Los movimientos de la compra [purchaseId], en el orden del libro: su `PURCHASE_PAYMENT`, si se
     * pagó algo al confirmarla desde una cuenta.
     */
    suspend fun findByPurchase(purchaseId: Long): List<MoneyMovement>

    /**
     * Los movimientos de la devolución a proveedor [purchaseReturnId], en el orden del libro: su
     * `SUPPLIER_REFUND`, si el reembolso entró a una cuenta.
     */
    suspend fun findByPurchaseReturn(purchaseReturnId: Long): List<MoneyMovement>

    /** Los movimientos del abono [customerLedgerEntryId] del libro del cliente, en el orden del libro. */
    suspend fun findByCustomerLedgerEntry(customerLedgerEntryId: Long): List<MoneyMovement>

    /**
     * La suma de los otros ingresos vigentes de [period], por fecha de negocio, de las cuentas en
     * [currency] con sus mismos decimales: uno anulado no cuenta, y uno de otra moneda tampoco.
     */
    fun observeOtherIncome(period: ReportPeriod, currency: AccountCurrency): Flow<Money>

    /**
     * Lo que todavía no llega de cada cuenta, por su id: la suma de los movimientos vigentes con
     * fecha de disponibilidad después de [today], como el cobro con tarjeta que se acredita en dos
     * días y su comisión. Uno anulado no cuenta, y una cuenta sin nada por llegar no aparece. Cada
     * suma va en la moneda de su cuenta. Vuelve a emitir con cada cambio confirmado del libro.
     */
    fun observePendingSettlement(today: BusinessDate): Flow<Map<Long, Money>>

    /**
     * El documento del que salió el movimiento [movementId], o `null` si se registró a mano o ya no
     * existe. Una anulación lleva al documento del movimiento que compensa.
     */
    suspend fun findDocument(movementId: Long): MoneyDocument?

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
