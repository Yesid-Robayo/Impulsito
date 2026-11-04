package io.github.yesidrobayo.shopledger.data.local.pricing

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `price_lists`. Las escrituras solo se llaman dentro de la transacción
 * del caso de uso.
 *
 * Las listas salen en el orden en que se crearon, por `id`, así que la inicial va primero. La
 * inicial, de clave `DEFAULT`, nunca se archiva, nunca deja de ser fija y nunca se borra: lo
 * impone la guarda de la tabla. Sin `REPLACE`: un `INSERT OR REPLACE` borraría la lista que choca.
 *
 * Una lista relativa se calcula desde su lista base, que puede ser relativa a otra. Esa cadena
 * nunca vuelve a la misma lista: [update] no cambia una lista si su nueva base la tiene a ella en
 * su cadena, y la guarda rechaza que una lista sea su propia base.
 */
@Dao
internal interface PriceListDao {
    /** Las vigentes: las que se ofrecen en una venta o para un cliente. */
    @Query("SELECT * FROM price_lists WHERE archived_at IS NULL ORDER BY id")
    fun observeActive(): Flow<List<PriceListEntity>>

    /** Las archivadas, que se pueden reactivar. */
    @Query("SELECT * FROM price_lists WHERE archived_at IS NOT NULL ORDER BY id")
    fun observeArchived(): Flow<List<PriceListEntity>>

    @Query("SELECT * FROM price_lists WHERE id = :id")
    suspend fun findById(id: Long): PriceListEntity?

    /** La lista inicial de esa clave, como `DEFAULT`, aunque la persona la haya renombrado. */
    @Query("SELECT * FROM price_lists WHERE system_key = :systemKey")
    suspend fun findBySystemKey(systemKey: String): PriceListEntity?

    /**
     * La vigente con ese nombre normalizado. El nombre es único solo entre las vigentes, así que
     * hay a lo sumo una: las archivadas con el mismo nombre no cuentan.
     */
    @Query(
        "SELECT * FROM price_lists WHERE normalized_name = :normalizedName AND archived_at IS NULL",
    )
    suspend fun findActiveByNormalizedName(normalizedName: String): PriceListEntity?

    /**
     * Si tomar [basePriceListId] como base de [id] formaría un ciclo: es decir, si [id] ya está en
     * la cadena de bases de [basePriceListId], o es ella misma. Sirve para avisar antes de guardar.
     */
    @Query(CYCLE_SQL)
    suspend fun wouldCreateCycle(id: Long, basePriceListId: Long): Boolean

    /** Devuelve el id nuevo. Falla si la clave ya existe, o si el nombre ya es de una vigente. */
    @Insert
    suspend fun insert(list: PriceListEntity): Long

    /**
     * Cambia lo que la persona escribe: el nombre, el tipo, la base y el ajuste, sin tocar la
     * clave, el archivo ni `created_at`. Devuelve las filas cambiadas, y 0 si la lista no existe o
     * si la nueva base formaría un ciclo: en ese caso no cambia nada.
     */
    @Query(
        "UPDATE price_lists SET name = :name, normalized_name = :normalizedName, kind = :kind, " +
            "base_price_list_id = :basePriceListId, adjustment_bp = :adjustmentBp, " +
            "updated_at = :updatedAt WHERE id = :id AND (:basePriceListId IS NULL OR NOT (" +
            CYCLE_SQL_WITH_NAMED_BASE + "))",
    )
    suspend fun update(
        id: Long,
        name: String?,
        normalizedName: String?,
        kind: String,
        basePriceListId: Long?,
        adjustmentBp: Long?,
        updatedAt: Long,
    ): Int

    /**
     * La archiva con [archivedAt], o la reactiva con `null`. Devuelve 0 si no existe. Falla si al
     * reactivarla ya hay una vigente con su nombre.
     */
    @Query(
        "UPDATE price_lists SET archived_at = :archivedAt, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * Elimina una lista sin uso. Si una venta, otra lista o un precio la usan, sus llaves
     * `RESTRICT` hacen fallar la sentencia y no se borra nada; la inicial la protege su guarda.
     * Devuelve las filas borradas.
     */
    @Query("DELETE FROM price_lists WHERE id = :id")
    suspend fun delete(id: Long): Int
}

/**
 * Recorre la cadena de bases desde la base propuesta y dice si llega a la lista que cambia. La
 * cadena es corta y la tabla, pequeña: no necesita más índice que la llave primaria.
 */
private const val CYCLE_BODY =
    "WITH RECURSIVE chain(id) AS (SELECT :basePriceListId UNION " +
        "SELECT l.base_price_list_id FROM price_lists l JOIN chain c ON l.id = c.id " +
        "WHERE l.base_price_list_id IS NOT NULL) SELECT 1 FROM chain WHERE chain.id = :id"

private const val CYCLE_SQL = "SELECT EXISTS ($CYCLE_BODY)"

private const val CYCLE_SQL_WITH_NAMED_BASE = "EXISTS ($CYCLE_BODY)"
