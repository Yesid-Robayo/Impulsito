package io.github.yesidrobayo.shopledger.data.local.cash

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `cash_sessions`. Las escrituras solo se llaman dentro de la transacción
 * del caso de uso.
 *
 * Una sesión nace abierta y solo cambia mientras lo está: [close] es su única edición, y deja la
 * sesión cerrada para siempre. Ninguna función la borra.
 */
@Dao
internal interface CashSessionDao {
    /** Las sesiones de una cuenta, de la más reciente a la más vieja, por `id`. */
    @Query("SELECT * FROM cash_sessions WHERE money_account_id = :accountId ORDER BY id DESC")
    fun observeByAccount(accountId: Long): Flow<List<CashSessionEntity>>

    @Query("SELECT * FROM cash_sessions WHERE id = :id")
    suspend fun findById(id: Long): CashSessionEntity?

    /** La sesión abierta de la cuenta, si hay una: nunca hay dos. */
    @Query("SELECT * FROM cash_sessions WHERE money_account_id = :accountId AND status = 'OPEN'")
    suspend fun findOpen(accountId: Long): CashSessionEntity?

    /**
     * La suma de los movimientos de dinero de la sesión, sin las diferencias de caja: lo esperado
     * es su apertura más esta suma. `SUM` aborta si no cabe en un entero, en vez de dar un real, y
     * la suma con la apertura la hace el dominio con suma exacta por la misma razón.
     */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM money_movements " +
            "WHERE cash_session_id = :id AND type <> 'CASH_DIFFERENCE'",
    )
    suspend fun movementsTotal(id: Long): Long

    /** La abre y devuelve su id. Falla si la cuenta ya tiene una abierta. */
    @Insert
    suspend fun insert(session: CashSessionEntity): Long

    /**
     * La cierra con lo esperado, lo contado y su diferencia, que es lo contado menos lo esperado.
     * Una ya cerrada no cambia: la condición la excluye y devuelve 0.
     */
    @Query(
        "UPDATE cash_sessions SET status = 'CLOSED', expected_amount = :expectedAmount, " +
            "counted_amount = :countedAmount, difference_amount = :differenceAmount, " +
            "closed_at = :closedAt, note = :note WHERE id = :id AND status = 'OPEN'",
    )
    suspend fun close(
        id: Long,
        expectedAmount: Long,
        countedAmount: Long,
        differenceAmount: Long,
        closedAt: Long,
        note: String?,
    ): Int
}
