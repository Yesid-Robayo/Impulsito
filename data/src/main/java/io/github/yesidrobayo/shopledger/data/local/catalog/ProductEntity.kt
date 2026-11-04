package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.tax.TaxRateEntity

/**
 * Un producto del catálogo. Cada variante es un producto propio, con su libro de stock.
 *
 * Dinero en unidad menor de la moneda del negocio y cantidades en milésimas, siempre `Long`. El
 * repositorio convierte a los tipos del dominio, y la entidad nunca sale de esta capa.
 *
 * - `on_hand_milli`, `average_cost` y `stock_value` son copias derivadas del libro de stock: solo
 *   las escribe la fila del libro, en su misma transacción. Ninguna edición del producto las toca.
 * - `stock_value` es el valor total del stock. Sin él, un costo por unidad menor que uno se
 *   perdería al redondear el promedio.
 * - Con historia, el producto no se borra: se archiva con `archived_at`. Un archivado no reserva su
 *   nombre ni su código de barras, así que esos índices no son únicos: la unicidad entre activos la
 *   impone la guarda de la tabla.
 * - Al eliminar su categoría o su proveedor habitual, el producto queda sin ellos. Un producto
 *   padre no se borra mientras tenga variantes, y una unidad no se borra mientras sea la base de
 *   algún producto.
 * - Al borrar su foto, el producto queda sin ella: la imagen no es historia del producto.
 * - Al eliminar su tasa de impuesto, el producto queda sin tasa, y sus ventas guardan su copia.
 */
@Entity(
    tableName = "products",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["id"],
            childColumns = ["default_supplier_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_product_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TaxRateEntity::class,
            parentColumns = ["id"],
            childColumns = ["tax_rate_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_media_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = UnitEntity::class,
            parentColumns = ["id"],
            childColumns = ["base_unit_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["normalized_name"]),
        Index(value = ["category_id"]),
        Index(value = ["default_supplier_id"]),
        Index(value = ["base_unit_id"]),
        Index(value = ["barcode"]),
        Index(value = ["parent_product_id"]),
        Index(value = ["photo_media_id"]),
        Index(value = ["tax_rate_id"]),
    ],
)
internal data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "default_supplier_id") val defaultSupplierId: Long?,
    @ColumnInfo(name = "base_unit_id") val baseUnitId: Long,
    @ColumnInfo(name = "stock_mode") val stockMode: String,
    @ColumnInfo(name = "sale_price") val salePrice: Long,
    @ColumnInfo(name = "average_cost") val averageCost: Long,
    @ColumnInfo(name = "initial_cost") val initialCost: Long,
    @ColumnInfo(name = "fixed_unit_cost") val fixedUnitCost: Long,
    @ColumnInfo(name = "stock_value") val stockValue: Long,
    @ColumnInfo(name = "on_hand_milli") val onHandMilli: Long,
    @ColumnInfo(name = "min_stock_milli") val minStockMilli: Long,
    @ColumnInfo(name = "barcode") val barcode: String?,
    @ColumnInfo(name = "parent_product_id") val parentProductId: Long?,
    @ColumnInfo(name = "variant_axis_1") val variantAxis1: String?,
    @ColumnInfo(name = "variant_axis_2") val variantAxis2: String?,
    @ColumnInfo(name = "variant_value_1") val variantValue1: String?,
    @ColumnInfo(name = "variant_value_2") val variantValue2: String?,
    @ColumnInfo(name = "photo_media_id") val photoMediaId: Long?,
    @ColumnInfo(name = "tax_rate_id") val taxRateId: Long?,
    @ColumnInfo(name = "replacement_cost") val replacementCost: Long,
    @ColumnInfo(name = "replacement_cost_at") val replacementCostAt: Long?,
    @ColumnInfo(name = "target_margin_bp") val targetMarginBp: Long?,
    @ColumnInfo(name = "reference_cost") val referenceCost: Long?,
    @ColumnInfo(name = "reference_price") val referencePrice: Long?,
    @ColumnInfo(name = "price_review_since") val priceReviewSince: Long?,
    // 0 o 1: SQLite no tiene booleanos.
    @ColumnInfo(name = "tracks_lots") val tracksLots: Long,
    @ColumnInfo(name = "expiry_notice_days") val expiryNoticeDays: Long?,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
