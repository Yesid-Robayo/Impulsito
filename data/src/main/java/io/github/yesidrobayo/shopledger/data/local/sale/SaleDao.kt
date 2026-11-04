package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `sales`. Las escrituras solo se llaman dentro de la transacción del caso
 * de uso.
 *
 * Una venta confirmada no se edita ni se borra: la única escritura después de insertarla es la
 * anulación, que toca solo el estado y sus tres columnas. Sin `REPLACE`: un `INSERT OR REPLACE`
 * borraría la venta que choca sin pasar por la guarda de borrado.
 *
 * `ACTIVE` y `VOIDED` son los nombres guardados de `DocumentStatus`, que no cambian nunca.
 */
@Dao
internal interface SaleDao {
    /**
     * Las ventas con fecha de negocio entre [startDate] y [endDate], las dos incluidas, de la más
     * nueva a la más antigua. En la misma fecha, la registrada después va primero.
     *
     * Las fechas son texto `YYYY-MM-DD`, cuyo orden es el de las fechas. El filtro por los dos
     * estados no descarta nada: deja que SQLite busque por el índice `(status, business_date)` en
     * vez de recorrer años de ventas cada vez que la lista vuelve a emitir.
     */
    @Query(SALES_IN_PERIOD_SQL)
    fun observeIn(startDate: String, endDate: String): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE id = :id")
    fun observeById(id: Long): Flow<SaleEntity?>

    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun findById(id: Long): SaleEntity?

    /**
     * Devuelve el id nuevo, que también es el número de la venta. El repositorio la guarda activa y
     * sin datos de anulación.
     */
    @Insert
    suspend fun insert(sale: SaleEntity): Long

    /**
     * Anula una venta activa: el estado pasa a `VOIDED` con el instante, el motivo y la nota.
     * Devuelve 0 si no existe o ya estaba anulada, porque un documento se anula una sola vez, y no
     * toca ninguna otra columna.
     */
    @Query(
        "UPDATE sales SET status = 'VOIDED', voided_at = :voidedAt, " +
            "void_reason = :voidReason, void_note = :voidNote " +
            "WHERE id = :id AND status = 'ACTIVE'",
    )
    suspend fun markVoided(id: Long, voidedAt: Long, voidReason: String, voidNote: String?): Int
}

/**
 * La consulta de [SaleDao.observeIn]. Vive aparte para que la prueba del plan de consulta mire la
 * misma sentencia que corre la app, y no una copia que podría apartarse de ella.
 */
internal const val SALES_IN_PERIOD_SQL =
    "SELECT * FROM sales WHERE status IN ('ACTIVE', 'VOIDED') " +
        "AND business_date BETWEEN :startDate AND :endDate " +
        "ORDER BY business_date DESC, id DESC"
