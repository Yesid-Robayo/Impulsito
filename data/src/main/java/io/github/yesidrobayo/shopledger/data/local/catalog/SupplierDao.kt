package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `suppliers`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * Ninguna función escribe `balance`: es la suma del libro del proveedor, y solo la mueve la
 * escritura de ese libro, en su misma transacción. Las listas van por nombre normalizado y después
 * por `id`, porque un archivado puede repetir el nombre de otro proveedor.
 */
@Dao
internal interface SupplierDao {
    @Query("SELECT * FROM suppliers WHERE archived_at IS NULL ORDER BY normalized_name, id")
    fun observeActive(): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM suppliers WHERE archived_at IS NOT NULL ORDER BY normalized_name, id")
    fun observeArchived(): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM suppliers WHERE id = :id")
    suspend fun findById(id: Long): SupplierEntity?

    /** El proveedor sin archivar con ese nombre: un archivado no reserva el suyo. */
    @Query(
        "SELECT * FROM suppliers WHERE normalized_name = :normalizedName AND archived_at IS NULL " +
            "ORDER BY id LIMIT 1",
    )
    suspend fun findActiveByNormalizedName(normalizedName: String): SupplierEntity?

    /** Devuelve el id nuevo. El saldo inicial lo pone el repositorio, en cero. */
    @Insert
    suspend fun insert(supplier: SupplierEntity): Long

    /** Cambia lo que la persona escribe. No toca el saldo, el archivado ni `created_at`. */
    @Query(
        "UPDATE suppliers SET name = :name, normalized_name = :normalizedName, phone = :phone, " +
            "email = :email, notes = :notes, payment_terms_days = :paymentTermsDays, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        name: String,
        normalizedName: String,
        phone: String?,
        email: String?,
        notes: String?,
        paymentTermsDays: Long?,
        updatedAt: Long,
    ): Int

    /** Lo archiva con un instante, o lo reactiva con `null`. */
    @Query("UPDATE suppliers SET archived_at = :archivedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * Elimina un proveedor sin historia. Su llave foránea deja sin proveedor habitual a sus
     * productos en la misma sentencia. Devuelve las filas borradas.
     */
    @Query("DELETE FROM suppliers WHERE id = :id")
    suspend fun delete(id: Long): Int
}
