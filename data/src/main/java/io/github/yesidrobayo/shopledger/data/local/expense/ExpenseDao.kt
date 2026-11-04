package io.github.yesidrobayo.shopledger.data.local.expense

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `expenses`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * Un gasto registrado no se edita ni se borra: la única escritura después de insertarlo es la
 * anulación, que toca solo el estado y sus tres columnas. Sin `REPLACE`: un `INSERT OR REPLACE`
 * borraría el gasto que choca sin pasar por la guarda de borrado.
 *
 * `ACTIVE` y `VOIDED` son los nombres guardados de `DocumentStatus`, que no cambian nunca.
 */
@Dao
internal interface ExpenseDao {
    /**
     * Los gastos con fecha de negocio entre [startDate] y [endDate], las dos incluidas, del más
     * nuevo al más antiguo. En la misma fecha, el registrado después va primero.
     *
     * Las fechas son texto `YYYY-MM-DD`, cuyo orden es el de las fechas. El filtro por los dos
     * estados no descarta nada: deja que SQLite busque por el índice `(status, business_date)` en
     * vez de recorrer años de gastos cada vez que la lista vuelve a emitir.
     */
    @Query(EXPENSES_IN_PERIOD_SQL)
    fun observeIn(startDate: String, endDate: String): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun findById(id: Long): ExpenseEntity?

    /** Devuelve el id nuevo. El repositorio lo guarda activo y sin datos de anulación. */
    @Insert
    suspend fun insert(expense: ExpenseEntity): Long

    /**
     * Anula un gasto activo: el estado pasa a `VOIDED` con el instante, el motivo y la nota.
     * Devuelve 0 si no existe o ya estaba anulado, porque un gasto se anula una sola vez, y no toca
     * ninguna otra columna.
     */
    @Query(
        "UPDATE expenses SET status = 'VOIDED', voided_at = :voidedAt, " +
            "void_reason = :voidReason, void_note = :voidNote " +
            "WHERE id = :id AND status = 'ACTIVE'",
    )
    suspend fun markVoided(id: Long, voidedAt: Long, voidReason: String, voidNote: String?): Int
}

/**
 * La consulta de [ExpenseDao.observeIn]. Vive aparte para que la prueba del plan de consulta mire
 * la misma sentencia que corre la app, y no una copia que podría apartarse de ella.
 */
internal const val EXPENSES_IN_PERIOD_SQL =
    "SELECT * FROM expenses WHERE status IN ('ACTIVE', 'VOIDED') " +
        "AND business_date BETWEEN :startDate AND :endDate " +
        "ORDER BY business_date DESC, id DESC"
