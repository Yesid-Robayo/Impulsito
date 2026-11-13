package io.github.yesidrobayo.shopledger.data.local.pricing

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `product_prices`. Las escrituras solo se llaman dentro de la
 * transacción del caso de uso, que también deja el cambio en el historial de precios.
 *
 * Las listas salen por `id`. Sin `REPLACE`: un `INSERT OR REPLACE` borraría el precio que choca
 * sin que el historial lo cuente; cambiar un precio es [updatePrice].
 */
@Dao
internal interface ProductPriceDao {
    /** Los precios guardados de una lista, en el orden en que se crearon. */
    @Query("SELECT * FROM product_prices WHERE price_list_id = :priceListId ORDER BY id")
    fun observeByPriceList(priceListId: Long): Flow<List<ProductPriceEntity>>

    /** Todos los precios propios, de todas las listas: una relativa parte de los de su base. */
    @Query("SELECT * FROM product_prices ORDER BY id")
    fun observeAll(): Flow<List<ProductPriceEntity>>

    /** Los precios de un producto en las listas fijas que le dieron uno. */
    @Query("SELECT * FROM product_prices WHERE product_id = :productId ORDER BY id")
    suspend fun findByProduct(productId: Long): List<ProductPriceEntity>

    /** El precio del producto en esa lista, o `null` si la lista no le dio uno. */
    @Query(
        "SELECT * FROM product_prices " +
            "WHERE price_list_id = :priceListId AND product_id = :productId",
    )
    suspend fun find(priceListId: Long, productId: Long): ProductPriceEntity?

    /** Si la lista tiene algún precio propio: una fija con precios no pasa a relativa. */
    @Query("SELECT EXISTS (SELECT 1 FROM product_prices WHERE price_list_id = :priceListId)")
    suspend fun hasAny(priceListId: Long): Boolean

    /** Devuelve el id nuevo. Falla si el producto ya tiene precio en esa lista. */
    @Insert
    suspend fun insert(price: ProductPriceEntity): Long

    /** Cambia solo el precio. Devuelve 0 si no existe. */
    @Query(
        "UPDATE product_prices SET price = :price, updated_at = :updatedAt " +
            "WHERE price_list_id = :priceListId AND product_id = :productId",
    )
    suspend fun updatePrice(priceListId: Long, productId: Long, price: Long, updatedAt: Long): Int

    /**
     * Quita el precio del producto en esa lista, que vuelve a tomar el de la inicial. Devuelve las
     * filas borradas.
     */
    @Query(
        "DELETE FROM product_prices WHERE price_list_id = :priceListId AND product_id = :productId",
    )
    suspend fun delete(priceListId: Long, productId: Long): Int
}
