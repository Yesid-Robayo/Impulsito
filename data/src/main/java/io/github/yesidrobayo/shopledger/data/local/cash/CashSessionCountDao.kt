package io.github.yesidrobayo.shopledger.data.local.cash

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

/**
 * Los conteos por denominación: agregar y leer, nada más. Un conteo no se edita ni se borra, y la
 * inserción no usa `REPLACE`, que borraría el que choca.
 */
@Dao
internal interface CashSessionCountDao {
    /** Los conteos de una sesión en una fase, en el orden en que se escribieron. */
    @Query(
        "SELECT * FROM cash_session_counts WHERE cash_session_id = :sessionId AND phase = :phase " +
            "ORDER BY id",
    )
    suspend fun findBySession(sessionId: Long, phase: String): List<CashSessionCountEntity>

    /** Devuelve los ids nuevos. Falla si una denominación ya se contó en esa sesión y fase. */
    @Insert
    suspend fun insertAll(counts: List<CashSessionCountEntity>): List<Long>
}
