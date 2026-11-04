package io.github.yesidrobayo.shopledger.data.local.migration

import androidx.room3.ColumnInfo
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomDatabase

/*
 * Base ficticia, solo de las pruebas, para ensayar migraciones mientras la base de la app sigue en
 * el esquema 1, que no migra.
 *
 * Imita lo que más importa cuidar al migrar: dinero en la unidad menor y cantidades en milésimas,
 * siempre en Long, un libro de stock que solo crece y cuyo orden es el de `id`, y llaves foráneas
 * con RESTRICT. Su código está en la última versión, la 3. Las anteriores existen solo como JSON
 * exportado, igual que pasará con la base real: la 1 se exportó con este código sin las columnas y
 * la tabla que agregan los saltos, y la 2 sin la tabla de categorías.
 *
 * - Versión 2: `products.reorder_point_milli`, con valor por defecto, y `stock_movements.note`.
 *   Solo agrega columnas, lo único que admite el libro.
 * - Versión 3: tabla `categories`, y `products.category_id` pasa a ser llave foránea. SQLite no
 *   agrega una llave a una columna existente: `products`, padre del libro, se reconstruye.
 */

@Entity(tableName = "categories")
internal data class TemplateCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
)

@Entity(
    tableName = "products",
    foreignKeys = [
        ForeignKey(
            entity = TemplateCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["category_id"])],
)
internal data class TemplateProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "sale_price") val salePrice: Long,
    @ColumnInfo(name = "average_cost") val averageCost: Long,
    @ColumnInfo(name = "on_hand_milli") val onHandMilli: Long,
    @ColumnInfo(name = "reorder_point_milli", defaultValue = "0") val reorderPointMilli: Long,
)

@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(
            entity = TemplateProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TemplateMovementEntity::class,
            parentColumns = ["id"],
            childColumns = ["reverses_movement_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["product_id"]),
        Index(value = ["reverses_movement_id"], unique = true),
    ],
)
internal data class TemplateMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "unit_cost") val unitCost: Long,
    @ColumnInfo(name = "total_cost") val totalCost: Long,
    @ColumnInfo(name = "reverses_movement_id") val reversesMovementId: Long?,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "note") val note: String?,
)

@Database(
    entities = [
        TemplateCategoryEntity::class,
        TemplateProductEntity::class,
        TemplateMovementEntity::class,
    ],
    version = TemplateDatabase.LATEST_VERSION,
    exportSchema = true,
)
internal abstract class TemplateDatabase : RoomDatabase() {
    companion object {
        const val FIRST_VERSION = 1
        const val LATEST_VERSION = 3
    }
}
