package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `units`. Las escrituras solo se llaman dentro de la transacción del caso
 * de uso.
 *
 * Todo sale en el orden en que se crearon, por `id`: el orden no depende del idioma, aunque las
 * iniciales se muestren traducidas. Sin `REPLACE`: un `INSERT OR REPLACE` borraría la unidad que
 * choca, y con productos o líneas fallaría su llave a mitad del caso de uso.
 *
 * La de clave `UNIT` nunca se archiva ni se borra: las dos escrituras que lo harían la excluyen en
 * su condición y devuelven 0, y la guarda de la tabla la protege de cualquier otra sentencia.
 */
@Dao
internal interface UnitDao {
    /** Las vigentes: las que se ofrecen al elegir la unidad de un producto o de una línea. */
    @Query("SELECT * FROM units WHERE archived_at IS NULL ORDER BY id")
    fun observeActive(): Flow<List<UnitEntity>>

    /** Las archivadas, que siguen explicando lo ya registrado y se pueden reactivar. */
    @Query("SELECT * FROM units WHERE archived_at IS NOT NULL ORDER BY id")
    fun observeArchived(): Flow<List<UnitEntity>>

    @Query("SELECT * FROM units WHERE id = :id")
    suspend fun findById(id: Long): UnitEntity?

    /** La unidad inicial con esa clave estable, como `UNIT`. */
    @Query("SELECT * FROM units WHERE system_key = :systemKey")
    suspend fun findBySystemKey(systemKey: String): UnitEntity?

    /**
     * La vigente con ese nombre normalizado. El nombre es único solo entre las vigentes, así que hay
     * a lo sumo una: las archivadas con el mismo nombre no cuentan.
     */
    @Query("SELECT * FROM units WHERE normalized_name = :normalizedName AND archived_at IS NULL")
    suspend fun findActiveByNormalizedName(normalizedName: String): UnitEntity?

    /**
     * Si algún producto la usa como unidad base, alguna conversión la usa, o alguna línea de
     * compra, de venta, de cotización, de pedido o de una venta en espera se registró en ella. Con
     * uso ya no se borra: solo se archiva.
     */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM products WHERE base_unit_id = :id) " +
            "OR EXISTS (SELECT 1 FROM product_units WHERE unit_id = :id) " +
            "OR EXISTS (SELECT 1 FROM purchase_lines WHERE unit_id = :id) " +
            "OR EXISTS (SELECT 1 FROM sale_lines WHERE unit_id = :id) " +
            "OR EXISTS (SELECT 1 FROM quote_lines WHERE unit_id = :id) " +
            "OR EXISTS (SELECT 1 FROM order_lines WHERE unit_id = :id) " +
            "OR EXISTS (SELECT 1 FROM sale_draft_lines WHERE unit_id = :id)",
    )
    suspend fun isInUse(id: Long): Boolean

    /**
     * Los ids de las unidades con uso, con la misma definición que [isInUse]. Room vuelve a emitir
     * con cualquier cambio de esas tablas.
     */
    @Query(
        "SELECT base_unit_id FROM products " +
            "UNION SELECT unit_id FROM product_units " +
            "UNION SELECT unit_id FROM purchase_lines " +
            "UNION SELECT unit_id FROM sale_lines " +
            "UNION SELECT unit_id FROM quote_lines " +
            "UNION SELECT unit_id FROM order_lines " +
            "UNION SELECT unit_id FROM sale_draft_lines",
    )
    fun observeInUseIds(): Flow<List<Long>>

    /** Devuelve el id nuevo. Falla si la clave ya existe, o si el nombre ya es de una vigente. */
    @Insert
    suspend fun insert(unit: UnitEntity): Long

    /**
     * Cambia lo que la persona escribe, sin tocar la clave, el archivo ni `created_at`. Devuelve las
     * filas cambiadas.
     */
    @Query(
        "UPDATE units SET name = :name, normalized_name = :normalizedName, symbol = :symbol, " +
            "kind = :kind, allows_fraction = :allowsFraction, updated_at = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        name: String?,
        normalizedName: String?,
        symbol: String?,
        kind: String,
        allowsFraction: Long,
        updatedAt: Long,
    ): Int

    /**
     * La archiva con [archivedAt], o la reactiva con `null`. Devuelve 0 si no existe o es la de
     * clave `UNIT`, que nunca se archiva.
     */
    @Query(
        "UPDATE units SET archived_at = :archivedAt, updated_at = :updatedAt " +
            "WHERE id = :id AND system_key IS NOT 'UNIT'",
    )
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * Elimina una unidad sin uso. Con uso, una llave `RESTRICT` hace fallar la sentencia y no borra
     * nada. Devuelve 0 si no existe o es la de clave `UNIT`, que nunca se borra.
     */
    @Query("DELETE FROM units WHERE id = :id AND system_key IS NOT 'UNIT'")
    suspend fun delete(id: Long): Int
}
