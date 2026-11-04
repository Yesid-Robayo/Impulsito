package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant

/**
 * Una alerta de stock bajo guardada. Sin [resolvedAt] está activa; con valor, el stock volvió a
 * quedar por encima del mínimo, o el producto se archivó, y la próxima caída vuelve a avisar.
 */
data class LowStockAlert(
    val id: Long,
    val productId: Long,
    val onHandAtTrigger: QuantityMilli,
    val minStockAtTrigger: QuantityMilli,
    val notificationStatus: LowStockNotificationStatus,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val resolvedAt: Instant?,
) {
    /** Si la alerta sigue activa. */
    val isActive: Boolean get() = resolvedAt == null
}
