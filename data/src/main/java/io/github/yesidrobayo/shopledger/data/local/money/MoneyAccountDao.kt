package io.github.yesidrobayo.shopledger.data.local.money

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `money_accounts`. Las escrituras solo se llaman dentro de la transacción
 * del caso de uso.
 *
 * Las listas van en el orden en que se crearon, por `id`: el orden no depende del idioma, aunque
 * la cuenta inicial se muestre traducida. La edición no toca el saldo: `balance` solo lo mueve
 * [setBalance], en la transacción que agrega el movimiento. Sin `REPLACE`: borraría la cuenta que
 * choca, y con movimientos fallaría su llave a mitad del caso de uso.
 */
@Dao
internal interface MoneyAccountDao {
    /** Las vigentes: las que se ofrecen al registrar dinero. */
    @Query("SELECT * FROM money_accounts WHERE archived_at IS NULL ORDER BY id")
    fun observeActive(): Flow<List<MoneyAccountEntity>>

    /** Las archivadas: siguen con su libro, pero no reciben dinero nuevo. */
    @Query("SELECT * FROM money_accounts WHERE archived_at IS NOT NULL ORDER BY id")
    fun observeArchived(): Flow<List<MoneyAccountEntity>>

    /**
     * La cuenta vigente con ese nombre normalizado, si la hay: la que chocaría con uno nuevo. Una
     * archivada no reserva su nombre.
     */
    @Query(
        "SELECT * FROM money_accounts WHERE normalized_name = :normalizedName " +
            "AND archived_at IS NULL ORDER BY id LIMIT 1",
    )
    suspend fun findActiveByNormalizedName(normalizedName: String): MoneyAccountEntity?

    @Query("SELECT * FROM money_accounts WHERE id = :id")
    suspend fun findById(id: Long): MoneyAccountEntity?

    /** La cuenta que nació con la base con esa clave, como `CASH`. */
    @Query("SELECT * FROM money_accounts WHERE system_key = :systemKey")
    suspend fun findBySystemKey(systemKey: String): MoneyAccountEntity?

    /** Devuelve el id nuevo. Falla si el nombre normalizado ya es de otra cuenta vigente. */
    @Insert
    suspend fun insert(account: MoneyAccountEntity): Long

    /**
     * Cambia lo que la persona escribe, sin tocar la clave, la moneda, el saldo, el archivo ni
     * `created_at`. Devuelve las filas cambiadas.
     */
    @Query(
        "UPDATE money_accounts SET name = :name, normalized_name = :normalizedName, " +
            "kind = :kind, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        name: String?,
        normalizedName: String?,
        kind: String,
        updatedAt: Long,
    ): Int

    /**
     * La moneda de la cuenta. Solo cambia mientras no tiene movimientos: con ellos, la guarda lo
     * rechaza. Confirmar la moneda del negocio la escribe en la cuenta inicial, en la misma
     * transacción.
     */
    @Query(
        "UPDATE money_accounts SET currency_code = :currencyCode, " +
            "currency_decimals = :currencyDecimals, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun setCurrency(
        id: Long,
        currencyCode: String,
        currencyDecimals: Long,
        updatedAt: Long,
    ): Int

    /** La archiva con un instante, o la reactiva con `null`. */
    @Query(
        "UPDATE money_accounts SET archived_at = :archivedAt, updated_at = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * El saldo que deja un movimiento nuevo, su `balance_after`. Solo la llama la escritura del
     * libro de dinero, en la misma transacción que agrega el movimiento.
     */
    @Query("UPDATE money_accounts SET balance = :balance, updated_at = :updatedAt WHERE id = :id")
    suspend fun setBalance(id: Long, balance: Long, updatedAt: Long): Int

    /**
     * Elimina una cuenta sin historia. Con movimientos, o si una compra, un gasto o un método de
     * pago la usa, su llave lo impide y falla: se archiva. Devuelve las filas borradas.
     */
    @Query("DELETE FROM money_accounts WHERE id = :id")
    suspend fun delete(id: Long): Int
}
