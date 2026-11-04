package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `products`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * Cada escritura toca solo sus columnas, nunca la fila entera: una edición de datos no debe pisar
 * el saldo, el costo ni el valor del stock que dejó el libro, ni el precio, que cambia con su fila
 * de historial. Sin `REPLACE`: un `INSERT OR REPLACE` borraría el producto que choca, y con él su
 * historial de precios en cascada.
 *
 * Las listas van por nombre normalizado y después por `id`, porque un archivado puede repetir el
 * nombre de otro producto.
 */
@Dao
internal interface ProductDao {
    @Query("SELECT * FROM products WHERE archived_at IS NULL ORDER BY normalized_name, id")
    fun observeActive(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE archived_at IS NOT NULL ORDER BY normalized_name, id")
    fun observeArchived(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    fun observeById(id: Long): Flow<ProductEntity?>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun findById(id: Long): ProductEntity?

    /** Los que existen de [ids], en cualquier orden: los que no existen no salen. */
    @Query("SELECT * FROM products WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<ProductEntity>

    /** El producto sin archivar con ese nombre: un archivado no reserva el suyo. */
    @Query(
        "SELECT * FROM products WHERE normalized_name = :normalizedName AND archived_at IS NULL " +
            "ORDER BY id LIMIT 1",
    )
    suspend fun findActiveByNormalizedName(normalizedName: String): ProductEntity?

    /** El producto sin archivar con ese código de barras: un archivado no reserva el suyo. */
    @Query(
        "SELECT * FROM products WHERE barcode = :barcode AND archived_at IS NULL " +
            "ORDER BY id LIMIT 1",
    )
    suspend fun findActiveByBarcode(barcode: String): ProductEntity?

    /** Devuelve el id nuevo. El repositorio lo crea sin stock: saldo y valor en cero. */
    @Insert
    suspend fun insert(product: ProductEntity): Long

    /**
     * Cambia lo que la persona edita. No toca el precio, los costos, el saldo, el valor del stock,
     * la revisión de precio, el archivado ni `created_at`.
     */
    @Query(
        "UPDATE products SET name = :name, normalized_name = :normalizedName, " +
            "category_id = :categoryId, default_supplier_id = :defaultSupplierId, " +
            "base_unit_id = :baseUnitId, stock_mode = :stockMode, tracks_lots = :tracksLots, " +
            "fixed_unit_cost = :fixedUnitCost, min_stock_milli = :minStockMilli, " +
            "barcode = :barcode, parent_product_id = :parentProductId, " +
            "variant_axis_1 = :variantAxis1, variant_axis_2 = :variantAxis2, " +
            "variant_value_1 = :variantValue1, variant_value_2 = :variantValue2, " +
            "photo_media_id = :photoMediaId, tax_rate_id = :taxRateId, " +
            "target_margin_bp = :targetMarginBp, reference_cost = :referenceCost, " +
            "reference_price = :referencePrice, expiry_notice_days = :expiryNoticeDays, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateDetails(
        id: Long,
        name: String,
        normalizedName: String,
        categoryId: Long?,
        defaultSupplierId: Long?,
        baseUnitId: Long,
        stockMode: String,
        tracksLots: Long,
        fixedUnitCost: Long,
        minStockMilli: Long,
        barcode: String?,
        parentProductId: Long?,
        variantAxis1: String?,
        variantAxis2: String?,
        variantValue1: String?,
        variantValue2: String?,
        photoMediaId: Long?,
        taxRateId: Long?,
        targetMarginBp: Long?,
        referenceCost: Long?,
        referencePrice: Long?,
        expiryNoticeDays: Long?,
        updatedAt: Long,
    ): Int

    /** Solo el precio de venta. Su fila de historial se agrega aparte, en la misma transacción. */
    @Query("UPDATE products SET sale_price = :salePrice, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSalePrice(id: Long, salePrice: Long, updatedAt: Long): Int

    /**
     * El costo inicial y, con él, el costo promedio: sin filas en el libro, el promedio es el costo
     * con que empieza la reproducción. Con filas, el costo solo lo mueve el libro, y el repositorio
     * no la llama.
     */
    @Query(
        "UPDATE products SET initial_cost = :initialCost, average_cost = :initialCost, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateInitialCost(id: Long, initialCost: Long, updatedAt: Long): Int

    /** El costo de reposición y su fecha. Cero es sin dato. */
    @Query(
        "UPDATE products SET replacement_cost = :replacementCost, " +
            "replacement_cost_at = :replacementCostAt, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateReplacementCost(
        id: Long,
        replacementCost: Long,
        replacementCostAt: Long?,
        updatedAt: Long,
    ): Int

    /** Lo pone a esperar una revisión de precio desde un instante, o lo saca con `null`. */
    @Query(
        "UPDATE products SET price_review_since = :since, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun setPriceReviewSince(id: Long, since: Long?, updatedAt: Long): Int

    /** Lo archiva con un instante, o lo reactiva con `null`. */
    @Query("UPDATE products SET archived_at = :archivedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * El saldo, el costo promedio y el valor del stock que deja una fila nueva del libro. Solo la
     * llama la escritura del libro, en la misma transacción que agrega la fila: así estas copias
     * nunca se apartan de él.
     */
    @Query(
        "UPDATE products SET on_hand_milli = :onHandMilli, average_cost = :averageCost, " +
            "stock_value = :stockValue, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateStockState(
        id: Long,
        onHandMilli: Long,
        averageCost: Long,
        stockValue: Long,
        updatedAt: Long,
    ): Int

    /**
     * Elimina un producto sin historia. Su historial de precios se borra con él, en cascada y en la
     * misma sentencia. Falla si tiene variantes. Devuelve las filas borradas.
     */
    @Query("DELETE FROM products WHERE id = :id")
    suspend fun delete(id: Long): Int
}
