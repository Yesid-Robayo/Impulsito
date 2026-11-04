package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Las líneas de venta: agregar y leer, nada más.
 *
 * No hay función que edite ni borre una línea, y no debe haberla: la línea guarda el precio y el
 * costo con que se vendió, y explica las filas que su venta dejó en el libro de stock, también
 * después de anularla. La inserción no usa `REPLACE`, que borraría la línea que choca. Las líneas
 * salen en el orden en que se guardaron, por `id`: un extra siempre después de su línea principal.
 */
@Dao
internal interface SaleLineDao {
    @Query("SELECT * FROM sale_lines WHERE sale_id = :saleId ORDER BY id")
    fun observeBySale(saleId: Long): Flow<List<SaleLineEntity>>

    @Query("SELECT * FROM sale_lines WHERE sale_id = :saleId ORDER BY id")
    suspend fun findBySale(saleId: Long): List<SaleLineEntity>

    /** Agrega la línea y devuelve su id. Solo se llama dentro de la transacción del caso de uso. */
    @Insert
    suspend fun insert(line: SaleLineEntity): Long
}
