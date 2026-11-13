package io.github.yesidrobayo.shopledger.data.local.money

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * El libro de dinero: agregar y leer, nada más.
 *
 * No hay función que edite ni borre un movimiento, y no debe haberla en ningún DAO: el saldo de
 * cada cuenta es la suma de sus movimientos, y uno cambiado lo dejaría mal sin que nada lo
 * delatara. La inserción no usa `REPLACE`, que borraría el movimiento que choca.
 *
 * Todo sale en el orden del libro, por `id`. [insert] solo se llama dentro de la transacción del
 * caso de uso, la misma en que la cuenta queda con el `balance_after` del movimiento.
 */
@Dao
internal interface MoneyMovementDao {
    @Query("SELECT * FROM money_movements WHERE money_account_id = :accountId ORDER BY id")
    fun observeByAccount(accountId: Long): Flow<List<MoneyMovementEntity>>

    @Query("SELECT * FROM money_movements WHERE id = :id")
    suspend fun findById(id: Long): MoneyMovementEntity?

    /** Los movimientos de una cuenta en el orden del libro: lo que da su extracto y su saldo. */
    @Query("SELECT * FROM money_movements WHERE money_account_id = :accountId ORDER BY id")
    suspend fun findByAccount(accountId: Long): List<MoneyMovementEntity>

    /**
     * El saldo que dejó el último movimiento de la cuenta, o `null` si no tiene ninguno: la base
     * del `balance_after` del siguiente.
     */
    @Query(
        "SELECT balance_after FROM money_movements WHERE money_account_id = :accountId " +
            "ORDER BY id DESC LIMIT 1",
    )
    suspend fun lastBalanceAfter(accountId: Long): Long?

    /**
     * Los movimientos de un pago de venta: su `SALE_RECEIPT` y su `FEE`. Usa el índice de
     * `sale_payment_id`, que la anulación de la venta lee una vez por pago con cuenta.
     */
    @Query("SELECT * FROM money_movements WHERE sale_payment_id = :salePaymentId ORDER BY id")
    suspend fun findBySalePayment(salePaymentId: Long): List<MoneyMovementEntity>

    /**
     * Los movimientos de un gasto: su `EXPENSE_PAYMENT`. Solo se lee al anular un gasto que salió de
     * una cuenta, poco frecuente: por eso la columna no lleva índice propio.
     */
    @Query("SELECT * FROM money_movements WHERE expense_id = :expenseId ORDER BY id")
    suspend fun findByExpense(expenseId: Long): List<MoneyMovementEntity>

    /**
     * Los movimientos de una compra: su `PURCHASE_PAYMENT`. Solo se lee al anular una compra que se
     * pagó desde una cuenta, poco frecuente: por eso la columna no lleva índice propio.
     */
    @Query("SELECT * FROM money_movements WHERE purchase_id = :purchaseId ORDER BY id")
    suspend fun findByPurchase(purchaseId: Long): List<MoneyMovementEntity>

    /**
     * Los movimientos de una devolución a proveedor: su `SUPPLIER_REFUND`. Solo se lee al anularla,
     * poco frecuente: por eso la columna no lleva índice propio.
     */
    @Query(
        "SELECT * FROM money_movements WHERE purchase_return_id = :purchaseReturnId ORDER BY id",
    )
    suspend fun findByPurchaseReturn(purchaseReturnId: Long): List<MoneyMovementEntity>

    /**
     * Los movimientos de una devolución de venta: su `REFUND`. Solo se lee al anularla, poco
     * frecuente: por eso la columna no lleva índice propio.
     */
    @Query("SELECT * FROM money_movements WHERE sale_return_id = :saleReturnId ORDER BY id")
    suspend fun findBySaleReturn(saleReturnId: Long): List<MoneyMovementEntity>

    /** Los movimientos de un pago a proveedor: su `SUPPLIER_PAYMENT`, si se pagó desde una cuenta. */
    @Query(
        "SELECT * FROM money_movements WHERE supplier_ledger_entry_id = :entryId ORDER BY id",
    )
    suspend fun findBySupplierLedgerEntry(entryId: Long): List<MoneyMovementEntity>

    /**
     * La suma de los otros ingresos vigentes con fecha de negocio entre [start] y [end], ambas
     * incluidas, de las cuentas en esa moneda y con esos decimales. Uno anulado no suma.
     */
    @Query(
        "SELECT COALESCE(SUM(m.amount), 0) FROM money_movements m " +
            "JOIN money_accounts a ON a.id = m.money_account_id " +
            "WHERE m.type = 'OTHER_INCOME' AND m.business_date BETWEEN :start AND :end " +
            "AND a.currency_code = :currencyCode AND a.currency_decimals = :currencyDecimals " +
            "AND NOT EXISTS (SELECT 1 FROM money_movements v WHERE v.reverses_movement_id = m.id)",
    )
    fun observeOtherIncome(
        start: String,
        end: String,
        currencyCode: String,
        currencyDecimals: Long,
    ): Flow<Long>

    /**
     * Lo que falta por llegar a cada cuenta: la suma de los movimientos vigentes cuya fecha de
     * disponibilidad es posterior a [today]. Uno anulado no suma, y una cuenta sin nada pendiente no
     * sale. Solo la lee la pantalla de cuentas mientras está abierta: por eso `available_date` no
     * lleva índice propio, y recorre el libro.
     */
    @Query(
        "SELECT m.money_account_id AS account_id, SUM(m.amount) AS amount FROM money_movements m " +
            "WHERE m.available_date > :today " +
            "AND NOT EXISTS " +
            "(SELECT 1 FROM money_movements v WHERE v.reverses_movement_id = m.id) " +
            "GROUP BY m.money_account_id ORDER BY m.money_account_id",
    )
    fun observePendingSettlement(today: String): Flow<List<PendingSettlementRow>>

    /**
     * Los documentos que un movimiento nombra a través de otra tabla: la venta de su pago o de su
     * devolución, el cliente de su abono, el proveedor de su pago, y la compra o el proveedor de su
     * devolución. `null` si el movimiento no existe.
     */
    @Query(
        "SELECT " +
            "(SELECT sp.sale_id FROM sale_payments sp WHERE sp.id = m.sale_payment_id) " +
            "AS sale_id, " +
            "(SELECT sr.sale_id FROM sale_returns sr WHERE sr.id = m.sale_return_id) " +
            "AS returned_sale_id, " +
            "(SELECT c.customer_id FROM customer_ledger_entries c " +
            "WHERE c.id = m.customer_ledger_entry_id) AS customer_id, " +
            "(SELECT s.supplier_id FROM supplier_ledger_entries s " +
            "WHERE s.id = m.supplier_ledger_entry_id) AS supplier_id, " +
            "(SELECT pr.purchase_id FROM purchase_returns pr WHERE pr.id = m.purchase_return_id) " +
            "AS returned_purchase_id, " +
            "(SELECT pr.supplier_id FROM purchase_returns pr WHERE pr.id = m.purchase_return_id) " +
            "AS returned_supplier_id " +
            "FROM money_movements m WHERE m.id = :movementId",
    )
    suspend fun findDocumentLinks(movementId: Long): MovementDocumentLinks?

    /** Las dos filas de una transferencia, la que sale y la que entra. */

    @Query("SELECT * FROM money_movements WHERE transfer_group_id = :groupId ORDER BY id")
    suspend fun findByTransferGroup(groupId: String): List<MoneyMovementEntity>

    /**
     * El dinero que entró con un abono del libro del cliente. Es una lectura de cada anulación de
     * abono, poco frecuente: por eso la columna no lleva índice propio.
     */
    @Query(
        "SELECT * FROM money_movements WHERE customer_ledger_entry_id = :customerLedgerEntryId " +
            "ORDER BY id",
    )
    suspend fun findByCustomerLedgerEntry(customerLedgerEntryId: Long): List<MoneyMovementEntity>

    /** Si algún movimiento ya anula a [movementId]: uno se anula una sola vez. */
    @Query("SELECT EXISTS (SELECT 1 FROM money_movements WHERE reverses_movement_id = :movementId)")
    suspend fun isCompensated(movementId: Long): Boolean

    /** Agrega el movimiento y devuelve su id, que fija su lugar en el orden del libro. */
    @Insert
    suspend fun insert(movement: MoneyMovementEntity): Long
}
