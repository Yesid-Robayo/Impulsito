package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant

/**
 * Una alerta de stock bajo que el caso de uso va a crear al cruzar el mínimo, todavía sin id.
 *
 * Guarda el saldo y el mínimo del momento del cruce, porque el aviso los muestra aunque después
 * cambien. Un producto tiene a lo sumo una alerta activa: crear otra con una activa es un error.
 */
data class NewLowStockAlert(
    val productId: Long,
    val onHandAtTrigger: QuantityMilli,
    val minStockAtTrigger: QuantityMilli,
    val notificationStatus: LowStockNotificationStatus,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
)
