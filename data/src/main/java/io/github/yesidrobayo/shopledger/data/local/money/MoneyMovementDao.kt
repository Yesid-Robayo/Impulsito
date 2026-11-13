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
