package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLowStockAlert
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las alertas de stock bajo, que también son la bandeja de salida de sus avisos.
 *
 * Crear y resolver una alerta va dentro de la misma [TransactionRunner.write] que movió el stock.
 * Marcar que el aviso salió no es un cambio del negocio, y va fuera de ella.
 */
interface LowStockAlertRepository {
    /** Las alertas activas, de la más antigua a la más nueva. */
    fun observeActive(): Flow<List<LowStockAlert>>

    /** La alerta activa de cada producto de [productIds] que tenga una. Hay a lo sumo una. */
    suspend fun findActiveByProducts(productIds: Collection<Long>): List<LowStockAlert>

    /** Las alertas cuyo aviso falta lanzar, de la más antigua a la más nueva. */
    suspend fun findPendingNotifications(): List<LowStockAlert>

    /** Crea la alerta y devuelve su id. El producto no debe tener otra activa. */
    suspend fun insert(alert: NewLowStockAlert): Long

    /** Resuelve la alerta: el stock volvió a quedar por encima del mínimo, o se archivó. */
    suspend fun resolve(id: Long, resolvedAt: Instant)

    /**
     * Guarda qué pasó con el aviso. Corre fuera de [TransactionRunner.write], después de lanzarlo,
     * y no cuenta como un cambio sin respaldar.
     */
    suspend fun setNotificationStatus(id: Long, status: LowStockNotificationStatus)
}
