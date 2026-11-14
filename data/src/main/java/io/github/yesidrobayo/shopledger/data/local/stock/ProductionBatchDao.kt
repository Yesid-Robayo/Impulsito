package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `production_batches` y de sus insumos. Las escrituras solo se llaman
 * dentro de la transacción del caso de uso, que en la misma escribe las filas del libro del lote.
 *
 * Un lote de producción registrado no se edita ni se borra: la única escritura después de
 * insertarlo es la anulación, que toca solo el estado y sus tres columnas. Sus insumos no cambian
 * nunca. Sin `REPLACE`: un `INSERT OR REPLACE` borraría la fila que choca sin pasar por la guarda de
 * borrado.
 */
@Dao
internal interface ProductionBatchDao {
    /**
     * Los lotes con fecha de negocio entre [startDate] y [endDate], las dos incluidas, del más nuevo
     * al más antiguo. En la misma fecha, el registrado después va primero.
     */
    @Query(
        "SELECT * FROM production_batches WHERE business_date BETWEEN :startDate AND :endDate " +
            "ORDER BY business_date DESC, id DESC",
    )
    fun observeIn(startDate: String, endDate: String): Flow<List<ProductionBatchEntity>>

    @Query("SELECT * FROM production_batches WHERE id = :id")
    suspend fun findById(id: Long): ProductionBatchEntity?

    /** Las [limit] más nuevas, en el mismo orden que la lista por período. */
    @Query(
        "SELECT * FROM production_batches ORDER BY business_date DESC, id DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<ProductionBatchEntity>>

    @Query("SELECT * FROM production_batches WHERE id = :id")
    fun observeById(id: Long): Flow<ProductionBatchEntity?>

    @Query(
        "SELECT * FROM production_batch_lines WHERE production_batch_id = :batchId ORDER BY id",
    )
    fun observeLines(batchId: Long): Flow<List<ProductionBatchLineEntity>>

    /** Los insumos de un lote, en el orden en que se registraron. */
    @Query(
        "SELECT * FROM production_batch_lines WHERE production_batch_id = :batchId ORDER BY id",
    )
    suspend fun findLines(batchId: Long): List<ProductionBatchLineEntity>

    /** Devuelve el id nuevo. El repositorio lo guarda activo y sin datos de anulación. */
    @Insert
    suspend fun insert(batch: ProductionBatchEntity): Long

    /** Devuelve el id nuevo. El insumo no cambia después. */
    @Insert
    suspend fun insertLine(line: ProductionBatchLineEntity): Long

    /**
     * Anula un lote activo: el estado pasa a `VOIDED` con el instante, el motivo y la nota. Devuelve
     * 0 si no existe o ya estaba anulado, porque se anula una sola vez.
     */
    @Query(
        "UPDATE production_batches SET status = 'VOIDED', voided_at = :voidedAt, " +
            "void_reason = :voidReason, void_note = :voidNote " +
            "WHERE id = :id AND status = 'ACTIVE'",
    )
    suspend fun markVoided(id: Long, voidedAt: Long, voidReason: String, voidNote: String?): Int
}
