package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `stock_transfers` y de sus líneas. Las escrituras solo se llaman dentro
 * de la transacción del caso de uso, que en la misma escribe las filas del libro de cada línea.
 *
 * Una transferencia registrada no se edita ni se borra: la única escritura después de insertarla es
 * la anulación, que toca solo el estado y sus tres columnas. Sus líneas no cambian nunca. Sin
 * `REPLACE`: un `INSERT OR REPLACE` borraría la fila que choca sin pasar por la guarda de borrado.
 */
@Dao
internal interface StockTransferDao {
    /**
     * Las transferencias con fecha de negocio entre [startDate] y [endDate], las dos incluidas, de
     * la más nueva a la más antigua. En la misma fecha, la registrada después va primero.
     */
    @Query(
        "SELECT * FROM stock_transfers WHERE business_date BETWEEN :startDate AND :endDate " +
            "ORDER BY business_date DESC, id DESC",
    )
    fun observeIn(startDate: String, endDate: String): Flow<List<StockTransferEntity>>

    @Query("SELECT * FROM stock_transfers WHERE id = :id")
    suspend fun findById(id: Long): StockTransferEntity?

    /** Las líneas de una transferencia, en el orden en que se registraron. */
    @Query("SELECT * FROM stock_transfer_lines WHERE transfer_id = :transferId ORDER BY id")
    suspend fun findLines(transferId: Long): List<StockTransferLineEntity>

    /** Las filas del libro que salieron de las líneas de la transferencia, por orden del libro. */
    @Query(
        "SELECT * FROM stock_movements WHERE transfer_line_id IN " +
            "(SELECT id FROM stock_transfer_lines WHERE transfer_id = :transferId) ORDER BY id",
    )
    suspend fun findMovements(transferId: Long): List<StockMovementEntity>

    /** Devuelve el id nuevo. El repositorio la guarda activa y sin datos de anulación. */
    @Insert
    suspend fun insert(transfer: StockTransferEntity): Long

    /** Devuelve el id nuevo. La línea no cambia después. */
    @Insert
    suspend fun insertLine(line: StockTransferLineEntity): Long

    /**
     * Anula una transferencia activa: el estado pasa a `VOIDED` con el instante, el motivo y la
     * nota. Devuelve 0 si no existe o ya estaba anulada, porque se anula una sola vez.
     */
    @Query(
        "UPDATE stock_transfers SET status = 'VOIDED', voided_at = :voidedAt, " +
            "void_reason = :voidReason, void_note = :voidNote " +
            "WHERE id = :id AND status = 'ACTIVE'",
    )
    suspend fun markVoided(id: Long, voidedAt: Long, voidReason: String, voidNote: String?): Int
}
