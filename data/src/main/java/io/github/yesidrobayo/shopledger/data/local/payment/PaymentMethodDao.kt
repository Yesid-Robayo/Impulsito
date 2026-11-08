package io.github.yesidrobayo.shopledger.data.local.payment

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `payment_methods`. Las escrituras solo se llaman dentro de la
 * transacción del caso de uso.
 *
 * Las listas van por `sort_order`, el orden que eligió la persona, y después por `id`: el orden no
 * depende del idioma, aunque los iniciales se muestren traducidos. Sin `REPLACE`: un
 * `INSERT OR REPLACE` borraría el método que choca, y con ventas fallaría su llave a mitad del caso
 * de uso.
 */
@Dao
internal interface PaymentMethodDao {
    /** Los vigentes: los que se ofrecen al cobrar. */
    @Query("SELECT * FROM payment_methods WHERE archived_at IS NULL ORDER BY sort_order, id")
    fun observeActive(): Flow<List<PaymentMethodEntity>>

    /** Los archivados, que siguen en los reportes y se pueden reactivar. */
    @Query("SELECT * FROM payment_methods WHERE archived_at IS NOT NULL ORDER BY sort_order, id")
    fun observeArchived(): Flow<List<PaymentMethodEntity>>

    /** Los vigentes de una vez, para leerlos dentro de la transacción del caso de uso. */
    @Query("SELECT * FROM payment_methods WHERE archived_at IS NULL ORDER BY sort_order, id")
    suspend fun findActive(): List<PaymentMethodEntity>

    /** El mayor orden, archivados incluidos, o `null` sin métodos: uno nuevo va después. */
    @Query("SELECT MAX(sort_order) FROM payment_methods")
    suspend fun maxSortOrder(): Long?

    @Query("SELECT * FROM payment_methods WHERE id = :id")
    suspend fun findById(id: Long): PaymentMethodEntity?

    /** El método inicial de esa clave, como `CASH`, aunque la persona lo haya renombrado. */
    @Query("SELECT * FROM payment_methods WHERE system_key = :systemKey")
    suspend fun findBySystemKey(systemKey: String): PaymentMethodEntity?

    /**
     * El vigente con ese nombre normalizado. El nombre es único solo entre los vigentes, así que hay
     * a lo sumo uno: los archivados con el mismo nombre no cuentan.
     */
    @Query(
        "SELECT * FROM payment_methods " +
            "WHERE normalized_name = :normalizedName AND archived_at IS NULL",
    )
    suspend fun findActiveByNormalizedName(normalizedName: String): PaymentMethodEntity?

    /** Si tiene pagos de ventas, aunque estén anuladas. Con pagos ya no se borra: se archiva. */
    @Query("SELECT EXISTS (SELECT 1 FROM sale_payments WHERE payment_method_id = :id)")
    suspend fun hasSalePayments(id: Long): Boolean

    /** Devuelve el id nuevo. Falla si la clave ya existe, o si el nombre ya es de un vigente. */
    @Insert
    suspend fun insert(method: PaymentMethodEntity): Long

    /**
     * Cambia lo que la persona escribe, sin tocar la clave, el orden, el archivo ni `created_at`.
     * Devuelve las filas cambiadas. Las ventas pasadas no cambian: guardan su copia del nombre y
     * del tipo.
     */
    @Query(
        "UPDATE payment_methods SET name = :name, normalized_name = :normalizedName, " +
            "kind = :kind, fee_bp = :feeBp, fee_fixed = :feeFixed, " +
            "settlement_days = :settlementDays, price_adjustment_bp = :priceAdjustmentBp, " +
            "money_account_id = :moneyAccountId, instructions_text = :instructionsText, " +
            "instructions_media_id = :instructionsMediaId, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        name: String?,
        normalizedName: String?,
        kind: String,
        feeBp: Long,
        feeFixed: Long,
        settlementDays: Long,
        priceAdjustmentBp: Long,
        moneyAccountId: Long?,
        instructionsText: String?,
        instructionsMediaId: Long?,
        updatedAt: Long,
    ): Int

    /** Lo mueve a [sortOrder] en la lista. Devuelve 0 si no existe. */
    @Query(
        "UPDATE payment_methods SET sort_order = :sortOrder, updated_at = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun updateSortOrder(id: Long, sortOrder: Long, updatedAt: Long): Int

    /**
     * Lo archiva con [archivedAt], o lo reactiva con `null`. Devuelve 0 si no existe. Falla si al
     * reactivarlo ya hay un vigente con su nombre.
     */
    @Query(
        "UPDATE payment_methods SET archived_at = :archivedAt, updated_at = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * Elimina un método sin pagos. Con pagos, su llave `RESTRICT` hace fallar la sentencia y no
     * borra nada. Devuelve las filas borradas.
     */
    @Query("DELETE FROM payment_methods WHERE id = :id")
    suspend fun delete(id: Long): Int
}
