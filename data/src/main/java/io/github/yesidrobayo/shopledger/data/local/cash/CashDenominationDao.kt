package io.github.yesidrobayo.shopledger.data.local.cash

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `cash_denominations`. Las listas van por el orden que eligió la persona
 * y después por `id`, para que dos con el mismo orden no cambien de lugar. Sin `REPLACE`: borraría
 * la que choca, y con conteos fallaría su llave a mitad del caso de uso.
 */
@Dao
internal interface CashDenominationDao {
    /** Las que se ofrecen al contar. */
    @Query("SELECT * FROM cash_denominations WHERE is_active = 1 ORDER BY sort_order, id")
    fun observeActive(): Flow<List<CashDenominationEntity>>

    /** Todas, también las desactivadas, para editarlas. */
    @Query("SELECT * FROM cash_denominations ORDER BY sort_order, id")
    fun observeAll(): Flow<List<CashDenominationEntity>>

    @Query("SELECT * FROM cash_denominations WHERE id = :id")
    suspend fun findById(id: Long): CashDenominationEntity?

    /** Devuelve el id nuevo. */
    @Insert
    suspend fun insert(denomination: CashDenominationEntity): Long

    /** Cambia lo que la persona escribe, sin tocar `created_at`. Devuelve las filas cambiadas. */
    @Query(
        "UPDATE cash_denominations SET value = :value, is_active = :isActive, " +
            "sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(id: Long, value: Long, isActive: Long, sortOrder: Long, updatedAt: Long): Int

    /**
     * Elimina una denominación sin conteos. Con conteos, su llave lo impide y falla: se desactiva.
     * Devuelve las filas borradas.
     */
    @Query("DELETE FROM cash_denominations WHERE id = :id")
    suspend fun delete(id: Long): Int
}
