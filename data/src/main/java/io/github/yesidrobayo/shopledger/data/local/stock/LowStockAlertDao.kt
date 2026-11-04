package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `low_stock_alerts`.
 *
 * Crear y resolver una alerta va dentro de la transacción que movió el stock. Guardar qué pasó con
 * el aviso va fuera de ella, después de lanzarlo, y no cuenta como un cambio del negocio.
 *
 * Todo sale en el orden en que se crearon, por `id`, de la más antigua a la más nueva: el reloj del
 * teléfono puede estar mal puesto, y el `id` no. Ninguna escritura toca el producto, el saldo o el
 * mínimo del cruce. Sin `REPLACE`: un `INSERT OR REPLACE` borraría la alerta que choca y su
 * historia.
 */
@Dao
internal interface LowStockAlertDao {
    /** Las alertas activas. */
    @Query("SELECT * FROM low_stock_alerts WHERE resolved_at IS NULL ORDER BY id")
    fun observeActive(): Flow<List<LowStockAlertEntity>>

    @Query("SELECT * FROM low_stock_alerts WHERE id = :id")
    suspend fun findById(id: Long): LowStockAlertEntity?

    /**
     * La alerta activa de cada producto de [productIds] que tenga una. Busca por el índice
     * `(product_id, resolved_at)`: corre dentro de cada venta.
     */
    @Query(
        "SELECT * FROM low_stock_alerts " +
            "WHERE product_id IN (:productIds) AND resolved_at IS NULL ORDER BY id",
    )
    suspend fun findActiveByProducts(productIds: List<Long>): List<LowStockAlertEntity>

    /** Las alertas cuyo aviso falta lanzar, por el índice de `notification_status`. */
    @Query("SELECT * FROM low_stock_alerts WHERE notification_status = 'PENDING' ORDER BY id")
    suspend fun findPendingNotifications(): List<LowStockAlertEntity>

    /** Devuelve el id nuevo. El producto no debe tener otra alerta activa. */
    @Insert
    suspend fun insert(alert: LowStockAlertEntity): Long

    /**
     * Resuelve una alerta activa. Devuelve 0 si no existe o ya estaba resuelta: el instante de una
     * resolución no se reescribe.
     */
    @Query(
        "UPDATE low_stock_alerts SET resolved_at = :resolvedAt " +
            "WHERE id = :id AND resolved_at IS NULL",
    )
    suspend fun resolve(id: Long, resolvedAt: Long): Int

    /**
     * Guarda qué pasó con el aviso, con el nombre guardado de `LowStockNotificationStatus`, sin
     * tocar ninguna otra columna. Devuelve las filas cambiadas.
     */
    @Query("UPDATE low_stock_alerts SET notification_status = :status WHERE id = :id")
    suspend fun setNotificationStatus(id: Long, status: String): Int
}
