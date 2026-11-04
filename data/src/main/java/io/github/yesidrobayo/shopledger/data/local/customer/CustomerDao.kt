package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `customers`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * La edición de lo que la persona escribe no toca los saldos: `balance` y `points_balance` solo los
 * mueven [setBalance] y [setPointsBalance], en la transacción que agrega la fila del libro. Las
 * listas van por nombre normalizado y después por `id`, porque dos clientes pueden llamarse igual.
 */
@Dao
internal interface CustomerDao {
    /** Los vigentes con nombre: ni archivados ni anonimizados. */
    @Query(
        "SELECT * FROM customers WHERE archived_at IS NULL AND anonymized_at IS NULL " +
            "ORDER BY normalized_name, id",
    )
    fun observeActive(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun findById(id: Long): CustomerEntity?

    /** Devuelve el id nuevo. Los saldos iniciales los pone el repositorio, en cero. */
    @Insert
    suspend fun insert(customer: CustomerEntity): Long

    /**
     * Cambia lo que la persona escribe. No toca los saldos, el archivo, la anonimización ni
     * `created_at`. Un cliente anonimizado no cambia: la condición lo excluye y devuelve 0.
     */
    @Query(
        "UPDATE customers SET name = :name, normalized_name = :normalizedName, phone = :phone, " +
            "email = :email, notes = :notes, price_list_id = :priceListId, " +
            "updated_at = :updatedAt WHERE id = :id AND anonymized_at IS NULL",
    )
    suspend fun update(
        id: Long,
        name: String,
        normalizedName: String,
        phone: String?,
        email: String?,
        notes: String?,
        priceListId: Long?,
        updatedAt: Long,
    ): Int

    /** Lo archiva con un instante, o lo reactiva con `null`. */
    @Query("UPDATE customers SET archived_at = :archivedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * Borra sus datos personales y fija el instante de la anonimización, que no se deshace. Su
     * libro, sus saldos y sus ventas no cambian. Uno ya anonimizado no cambia, ni su instante:
     * devuelve 0.
     */
    @Query(
        "UPDATE customers SET name = NULL, normalized_name = NULL, phone = NULL, email = NULL, " +
            "notes = NULL, anonymized_at = :anonymizedAt, updated_at = :anonymizedAt " +
            "WHERE id = :id AND anonymized_at IS NULL",
    )
    suspend fun anonymize(id: Long, anonymizedAt: Long): Int

    /**
     * El saldo que deja una fila nueva de su libro, su `balance_after`. Solo la llama la escritura
     * del libro, en la misma transacción que agrega la fila.
     */
    @Query("UPDATE customers SET balance = :balance, updated_at = :updatedAt WHERE id = :id")
    suspend fun setBalance(id: Long, balance: Long, updatedAt: Long): Int

    /**
     * El saldo de puntos que deja una fila nueva de su libro de puntos, su `balance_after`. Solo la
     * llama la escritura de ese libro, en la misma transacción.
     */
    @Query(
        "UPDATE customers SET points_balance = :pointsBalance, updated_at = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun setPointsBalance(id: Long, pointsBalance: Long, updatedAt: Long): Int

    /**
     * Elimina un cliente sin historia. Con ventas o filas en sus libros, la llave lo impide y
     * falla: se archiva. Devuelve las filas borradas.
     */
    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun delete(id: Long): Int
}
