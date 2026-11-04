package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `product_units`. Las escrituras solo se llaman dentro de la transacción
 * del caso de uso.
 *
 * Las conversiones de un producto salen en el orden en que se crearon, por `id`. Sin `REPLACE`: un
 * `INSERT OR REPLACE` sobre la misma unidad borraría la conversión que había sin que nadie lo
 * pidiera. Un choque de producto y unidad falla y no cambia nada.
 */
@Dao
internal interface ProductUnitDao {
    @Query("SELECT * FROM product_units WHERE product_id = :productId ORDER BY id")
    fun observeByProduct(productId: Long): Flow<List<ProductUnitEntity>>

    @Query("SELECT * FROM product_units WHERE product_id = :productId ORDER BY id")
    suspend fun findByProduct(productId: Long): List<ProductUnitEntity>

    @Query("SELECT * FROM product_units WHERE id = :id")
    suspend fun findById(id: Long): ProductUnitEntity?

    /** La conversión del producto a esa unidad: hay a lo sumo una. */
    @Query("SELECT * FROM product_units WHERE product_id = :productId AND unit_id = :unitId")
    suspend fun findByProductAndUnit(productId: Long, unitId: Long): ProductUnitEntity?

    /** La conversión con ese código de barras: el código no se repite entre conversiones. */
    @Query("SELECT * FROM product_units WHERE barcode = :barcode")
    suspend fun findByBarcode(barcode: String): ProductUnitEntity?

    /** Devuelve el id nuevo. Falla si el producto ya tiene esa unidad, o si el código se repite. */
    @Insert
    suspend fun insert(productUnit: ProductUnitEntity): Long

    /**
     * Cambia el factor, el precio, el código y dónde se ofrece, sin tocar el producto, la unidad ni
     * `created_at`. Las líneas ya registradas guardan su propio factor. Devuelve las filas cambiadas.
     */
    @Query(
        "UPDATE product_units SET factor_milli = :factorMilli, sale_price = :salePrice, " +
            "barcode = :barcode, for_sale = :forSale, for_purchase = :forPurchase, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        factorMilli: Long,
        salePrice: Long?,
        barcode: String?,
        forSale: Long,
        forPurchase: Long,
        updatedAt: Long,
    ): Int

    /** Quita la conversión. Las líneas que la usaron no cambian: guardan su factor. */
    @Query("DELETE FROM product_units WHERE id = :id")
    suspend fun delete(id: Long): Int
}
