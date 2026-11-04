package io.github.yesidrobayo.shopledger.data.local.document

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `quotes` y sus líneas. Las escrituras solo se llaman dentro de la
 * transacción del caso de uso.
 *
 * Una cotización y sus líneas solo cambian mientras está `OPEN`: convertirla y cancelarla lo
 * comprueban en su condición, y devuelven 0 si ya no lo está, y la guarda rechaza cualquier otro
 * camino. Sin `REPLACE`: un `INSERT OR REPLACE` borraría la cotización que choca con sus líneas.
 */
@Dao
internal interface QuoteDao {
    /** Las abiertas, de la fecha de negocio más nueva a la más vieja, y por `id` en cada fecha. */
    @Query(
        "SELECT * FROM quotes WHERE status = 'OPEN' ORDER BY business_date DESC, id DESC",
    )
    fun observeOpen(): Flow<List<QuoteEntity>>

    @Query("SELECT * FROM quotes WHERE id = :id")
    suspend fun findById(id: Long): QuoteEntity?

    /** Las líneas de la cotización, en el orden en que se agregaron. */
    @Query("SELECT * FROM quote_lines WHERE quote_id = :quoteId ORDER BY id")
    suspend fun findLines(quoteId: Long): List<QuoteLineEntity>

    /** Devuelve el id nuevo. La cotización nace `OPEN`. */
    @Insert
    suspend fun insert(quote: QuoteEntity): Long

    /** Devuelve el id nuevo. Falla si la cotización ya no está abierta. */
    @Insert
    suspend fun insertLine(line: QuoteLineEntity): Long

    /**
     * Cambia el cliente, la vigencia, los totales y la nota, después de rehacer sus líneas. Solo se
     * llama mientras la cotización está abierta. Falla si el total no es neto más impuesto.
     */
    @Query(
        "UPDATE quotes SET customer_id = :customerId, valid_until = :validUntil, " +
            "net_total = :netTotal, tax_total = :taxTotal, total = :total, note = :note, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        customerId: Long?,
        validUntil: String?,
        netTotal: Long,
        taxTotal: Long,
        total: Long,
        note: String?,
        updatedAt: Long,
    ): Int

    /** Quita una línea. Solo se llama mientras la cotización está abierta. */
    @Query("DELETE FROM quote_lines WHERE id = :lineId")
    suspend fun deleteLine(lineId: Long): Int

    /**
     * La marca convertida en la venta [saleId], en la misma transacción que crea esa venta.
     * Devuelve 0 si ya no estaba abierta: una cotización se convierte una sola vez.
     */
    @Query(
        "UPDATE quotes SET status = 'CONVERTED', converted_sale_id = :saleId, " +
            "updated_at = :updatedAt WHERE id = :id AND status = 'OPEN'",
    )
    suspend fun markConverted(id: Long, saleId: Long, updatedAt: Long): Int

    /** La cancela. Devuelve 0 si ya no estaba abierta. */
    @Query(
        "UPDATE quotes SET status = 'CANCELLED', updated_at = :updatedAt " +
            "WHERE id = :id AND status = 'OPEN'",
    )
    suspend fun markCancelled(id: Long, updatedAt: Long): Int

    /**
     * Borra una cotización sin convertir, con sus líneas. Una convertida la apunta su venta, y su
     * llave `RESTRICT` hace fallar la sentencia sin borrar nada.
     */
    @Query("DELETE FROM quotes WHERE id = :id")
    suspend fun delete(id: Long): Int
}
