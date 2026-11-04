package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `price_index_entries`, siempre por mes `YYYY-MM`. Las escrituras solo
 * se llaman dentro de la transacción del caso de uso.
 *
 * La lista sale por mes, que es único: el orden es estable y el de la comparación. Sin `REPLACE`:
 * un `INSERT OR REPLACE` sobre un mes que ya tiene variación borraría la fila y escribiría otra.
 */
@Dao
internal interface PriceIndexEntryDao {
    /** Todas las variaciones, de la más vieja a la más nueva, de nuevo cada vez que cambian. */
    @Query("SELECT * FROM price_index_entries ORDER BY month")
    fun observeAll(): Flow<List<PriceIndexEntryEntity>>

    @Query("SELECT * FROM price_index_entries WHERE month = :month")
    suspend fun findByMonth(month: String): PriceIndexEntryEntity?

    /** Devuelve el id nuevo. Falla si el mes ya tiene variación. */
    @Insert
    suspend fun insert(entry: PriceIndexEntryEntity): Long

    /** Cambia la variación de [month]. Devuelve las filas cambiadas: 0 si el mes no la tiene. */
    @Query(
        "UPDATE price_index_entries SET change_bp = :changeBp, source_note = :sourceNote, " +
            "updated_at = :updatedAt WHERE month = :month",
    )
    suspend fun update(month: String, changeBp: Long, sourceNote: String?, updatedAt: Long): Int

    /** Quita la variación de [month]: nada depende de ella. Devuelve las filas borradas. */
    @Query("DELETE FROM price_index_entries WHERE month = :month")
    suspend fun delete(month: String): Int
}
