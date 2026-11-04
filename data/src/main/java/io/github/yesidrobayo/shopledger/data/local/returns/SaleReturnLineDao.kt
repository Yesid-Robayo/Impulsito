package io.github.yesidrobayo.shopledger.data.local.returns

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

/**
 * Las líneas de las devoluciones de venta: agregar y leer, nada más.
 *
 * No hay función que edite ni borre una línea, y no debe haberla: guarda lo que volvió y a qué
 * costo, y explica las filas que su devolución dejó en el libro de stock, también después de
 * anularla. La inserción no usa `REPLACE`, que borraría la línea que choca. Salen por `id`, en el
 * orden en que se guardaron.
 */
@Dao
internal interface SaleReturnLineDao {
    @Query("SELECT * FROM sale_return_lines WHERE sale_return_id = :saleReturnId ORDER BY id")
    suspend fun findByReturn(saleReturnId: Long): List<SaleReturnLineEntity>

    /**
     * Lo ya devuelto de la línea vendida entre las devoluciones activas, en milésimas: lo que falta
     * por devolver es lo vendido menos esto. Una devolución anulada no cuenta.
     */
    @Query(
        "SELECT COALESCE(SUM(rl.quantity_milli), 0) FROM sale_return_lines rl " +
            "JOIN sale_returns r ON r.id = rl.sale_return_id " +
            "WHERE rl.sale_line_id = :saleLineId AND r.status = 'ACTIVE'",
    )
    suspend fun returnedQuantityMilli(saleLineId: Long): Long

    /** Agrega la línea y devuelve su id. Solo se llama dentro de la transacción de la devolución. */
    @Insert
    suspend fun insert(line: SaleReturnLineEntity): Long
}
