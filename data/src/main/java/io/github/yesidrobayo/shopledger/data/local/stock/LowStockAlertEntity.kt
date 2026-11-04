package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity

/**
 * Una alerta de stock bajo, con su historia: sin `resolved_at` está activa. También es la bandeja
 * de salida de su aviso: la alerta se confirma con la transacción que movió el stock, y el aviso
 * sale después, así que una alerta en `PENDING` sobrevive a un cierre del proceso.
 *
 * - Guarda el saldo y el mínimo del momento del cruce, en milésimas, porque el aviso los muestra
 *   aunque después cambien.
 * - `notification_status` es el nombre guardado de `LowStockNotificationStatus`.
 * - El producto usa `CASCADE`: una alerta no tiene historia propia que proteger, y sigue a su
 *   producto. Un producto con historia no se borra, así que esto solo alcanza a uno sin ella.
 * - El índice `(product_id, resolved_at)` encuentra la alerta activa de un producto dentro de cada
 *   venta, y el de `notification_status` las pendientes sin recorrer la historia. Que haya a lo
 *   sumo una activa por producto lo impone la guarda de la tabla, no un índice.
 */
@Entity(
    tableName = "low_stock_alerts",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["product_id", "resolved_at"]),
        Index(value = ["notification_status"]),
    ],
)
internal data class LowStockAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "on_hand_milli_at_trigger") val onHandMilliAtTrigger: Long,
    @ColumnInfo(name = "min_stock_milli_at_trigger") val minStockMilliAtTrigger: Long,
    @ColumnInfo(name = "notification_status") val notificationStatus: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long?,
)
