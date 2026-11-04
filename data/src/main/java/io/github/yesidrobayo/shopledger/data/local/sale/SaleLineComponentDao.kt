package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

/**
 * Los componentes que consumió cada línea de combo: agregar y leer, nada más.
 *
 * No hay función que edite ni borre un componente, y no debe haberla: guarda lo que salió del stock
 * y a qué costo, y explica las filas que la línea dejó en el libro, también después de anular la
 * venta. La inserción no usa `REPLACE`, que borraría el componente que choca. Salen por `id`, en el
 * orden en que se guardaron.
 */
@Dao
internal interface SaleLineComponentDao {
    @Query("SELECT * FROM sale_line_components WHERE sale_line_id = :saleLineId ORDER BY id")
    suspend fun findBySaleLine(saleLineId: Long): List<SaleLineComponentEntity>

    /** Los de todas las líneas de la venta, por `id`: lo que se repone al anularla. */
    @Query(
        "SELECT c.* FROM sale_line_components c JOIN sale_lines l ON l.id = c.sale_line_id " +
            "WHERE l.sale_id = :saleId ORDER BY c.id",
    )
    suspend fun findBySale(saleId: Long): List<SaleLineComponentEntity>

    /** Agrega el componente y devuelve su id. Solo se llama dentro de la transacción de la venta. */
    @Insert
    suspend fun insert(component: SaleLineComponentEntity): Long
}
