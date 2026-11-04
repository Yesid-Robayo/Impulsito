package io.github.yesidrobayo.shopledger.data.local.tax

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `tax_rates`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * Las listas salen en el orden en que se crearon, por `id`. Sin `REPLACE`: un `INSERT OR REPLACE`
 * borraría la tasa que choca por nombre, y sus productos quedarían sin tasa sin que nadie lo pida.
 * Cambiar una tasa no toca las ventas ni las compras pasadas: sus líneas guardan su propia copia.
 */
@Dao
internal interface TaxRateDao {
    /** Las vigentes: las que se ofrecen como tasa de un producto o tasa por defecto. */
    @Query("SELECT * FROM tax_rates WHERE archived_at IS NULL ORDER BY id")
    fun observeActive(): Flow<List<TaxRateEntity>>

    /** Las archivadas, que se pueden reactivar. */
    @Query("SELECT * FROM tax_rates WHERE archived_at IS NOT NULL ORDER BY id")
    fun observeArchived(): Flow<List<TaxRateEntity>>

    @Query("SELECT * FROM tax_rates WHERE id = :id")
    suspend fun findById(id: Long): TaxRateEntity?

    /**
     * La tasa con ese nombre normalizado, vigente o archivada: el nombre es único en toda la tabla,
     * así que hay a lo sumo una.
     */
    @Query("SELECT * FROM tax_rates WHERE normalized_name = :normalizedName")
    suspend fun findByNormalizedName(normalizedName: String): TaxRateEntity?

    /**
     * Cuántos productos la usan, también los archivados: es lo que se avisa antes de eliminarla,
     * porque quedarían sin tasa.
     */
    @Query("SELECT COUNT(*) FROM products WHERE tax_rate_id = :id")
    suspend fun countProducts(id: Long): Int

    /** Devuelve el id nuevo. Falla si el nombre ya es de otra tasa, aunque esté archivada. */
    @Insert
    suspend fun insert(rate: TaxRateEntity): Long

    /**
     * Cambia lo que la persona escribe, sin tocar el archivo ni `created_at`. Devuelve las filas
     * cambiadas.
     */
    @Query(
        "UPDATE tax_rates SET name = :name, normalized_name = :normalizedName, " +
            "rate_bp = :rateBp, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        name: String,
        normalizedName: String,
        rateBp: Long,
        updatedAt: Long,
    ): Int

    /** La archiva con [archivedAt], o la reactiva con `null`. Devuelve 0 si no existe. */
    @Query("UPDATE tax_rates SET archived_at = :archivedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * Elimina una tasa. Su llave foránea deja sin tasa a los productos que la usaban, en la misma
     * sentencia, y las líneas de ventas y compras conservan su copia. Devuelve las filas borradas.
     */
    @Query("DELETE FROM tax_rates WHERE id = :id")
    suspend fun delete(id: Long): Int
}
