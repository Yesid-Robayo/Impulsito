package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `locations`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * Todo sale en el orden en que se crearon, por `id`: la inicial primero, y el orden no depende del
 * idioma aunque se muestre traducida. Sin `REPLACE`: un `INSERT OR REPLACE` borraría la ubicación
 * que choca, y con stock fallaría su llave a mitad del caso de uso.
 *
 * La de clave `MAIN` nunca se archiva ni se borra: las dos escrituras que lo harían la excluyen en
 * su condición y devuelven 0, y la guarda de la tabla la protege de cualquier otra sentencia.
 */
@Dao
internal interface LocationDao {
    /** Las vigentes. Con una sola, la interfaz no habla de ubicaciones. */
    @Query("SELECT * FROM locations WHERE archived_at IS NULL ORDER BY id")
    fun observeActive(): Flow<List<LocationEntity>>

    /** Las archivadas, que siguen explicando lo ya registrado y se pueden reactivar. */
    @Query("SELECT * FROM locations WHERE archived_at IS NOT NULL ORDER BY id")
    fun observeArchived(): Flow<List<LocationEntity>>

    /** Cuántas vigentes hay: con una sola, la interfaz oculta las ubicaciones. */
    @Query("SELECT COUNT(*) FROM locations WHERE archived_at IS NULL")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun findById(id: Long): LocationEntity?

    /** La ubicación inicial con esa clave estable, como `MAIN`. */
    @Query("SELECT * FROM locations WHERE system_key = :systemKey")
    suspend fun findBySystemKey(systemKey: String): LocationEntity?

    /**
     * La vigente con ese nombre normalizado. El nombre es único solo entre las vigentes, así que hay
     * a lo sumo una: las archivadas con el mismo nombre no cuentan.
     */
    @Query(
        "SELECT * FROM locations WHERE normalized_name = :normalizedName AND archived_at IS NULL",
    )
    suspend fun findActiveByNormalizedName(normalizedName: String): LocationEntity?

    /**
     * Si alguna fila del libro, algún saldo, alguna compra o alguna venta está en ella. Con uso ya no
     * se borra: solo se archiva.
     */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM stock_movements WHERE location_id = :id) " +
            "OR EXISTS (SELECT 1 FROM stock_balances WHERE location_id = :id) " +
            "OR EXISTS (SELECT 1 FROM purchases WHERE location_id = :id) " +
            "OR EXISTS (SELECT 1 FROM sales WHERE location_id = :id)",
    )
    suspend fun isInUse(id: Long): Boolean

    /** Devuelve el id nuevo. Falla si la clave ya existe, o si el nombre ya es de una vigente. */
    @Insert
    suspend fun insert(location: LocationEntity): Long

    /**
     * Cambia lo que la persona escribe, sin tocar la clave, el archivo ni `created_at`. Devuelve las
     * filas cambiadas.
     */
    @Query(
        "UPDATE locations SET name = :name, normalized_name = :normalizedName, kind = :kind, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        name: String?,
        normalizedName: String?,
        kind: String,
        updatedAt: Long,
    ): Int

    /**
     * La archiva con [archivedAt], o la reactiva con `null`. Devuelve 0 si no existe o es la de
     * clave `MAIN`, que nunca se archiva.
     */
    @Query(
        "UPDATE locations SET archived_at = :archivedAt, updated_at = :updatedAt " +
            "WHERE id = :id AND system_key IS NOT 'MAIN'",
    )
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * Elimina una ubicación sin uso. Con uso, una llave `RESTRICT` hace fallar la sentencia y no
     * borra nada. Devuelve 0 si no existe o es la de clave `MAIN`, que nunca se borra.
     */
    @Query("DELETE FROM locations WHERE id = :id AND system_key IS NOT 'MAIN'")
    suspend fun delete(id: Long): Int
}
