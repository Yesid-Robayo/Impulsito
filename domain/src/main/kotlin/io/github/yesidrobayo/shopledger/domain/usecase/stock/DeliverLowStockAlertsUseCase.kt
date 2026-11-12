package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockNotice
import io.github.yesidrobayo.shopledger.domain.port.LowStockNotifier
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository

/**
 * Lanza los avisos de las alertas que quedaron pendientes y guarda qué pasó con cada una.
 *
 * Corre después de confirmar la transacción que creó las alertas, y también al iniciar la app: si
 * el proceso murió entre confirmar y avisar, la alerta sigue pendiente y se avisa entonces. Nunca
 * corre dentro de una transacción de escritura, porque avisar es lento y no es un dato del negocio.
 *
 * - Con los avisos de stock apagados en los ajustes, no avisa: las marca calladas.
 * - Una alerta que ya se resolvió antes de avisar, porque el stock volvió a subir, tampoco avisa.
 * - Si el sistema no deja avisar, las marca bloqueadas. Al dar el permiso después no se avisa hacia
 *   atrás: avisa el siguiente cruce.
 *
 * La lista de productos por agotarse no depende de nada de esto.
 */
class DeliverLowStockAlertsUseCase(
    private val alerts: LowStockAlertRepository,
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val settings: AppSettingsRepository,
    private val notifier: LowStockNotifier,
) {
    /** Avisa lo pendiente y devuelve cuántos avisos mostró. */
    suspend operator fun invoke(): Int {
        val pending = alerts.findPendingNotifications()
        if (pending.isEmpty()) return 0
        val (active, resolved) = pending.partition { it.isActive }
        mark(resolved, LowStockNotificationStatus.SUPPRESSED)
        if (active.isEmpty()) return 0
        if (!settings.get().preferences.lowStockNotificationsEnabled) {
            mark(active, LowStockNotificationStatus.SUPPRESSED)
            return 0
        }
        val notices = noticesOf(active)
        val shown = notices.isNotEmpty() && notifier.show(notices)
        val status =
            if (shown) LowStockNotificationStatus.SHOWN else LowStockNotificationStatus.BLOCKED
        mark(active, status)
        return if (shown) notices.size else 0
    }

    private suspend fun noticesOf(active: List<LowStockAlert>): List<LowStockNotice> {
        val byId = products.findByIds(active.map { it.productId }.toSet()).associateBy { it.id }
        return active.mapNotNull { alert ->
            // El producto se borra con sus alertas, así que una alerta siempre lo tiene.
            val product = byId[alert.productId] ?: return@mapNotNull null
            LowStockNotice(
                alertId = alert.id,
                productId = product.id,
                productName = product.details.name,
                onHand = alert.onHandAtTrigger,
                minStock = alert.minStockAtTrigger,
                unit = units.findById(product.details.baseUnitId),
            )
        }
    }

    private suspend fun mark(list: List<LowStockAlert>, status: LowStockNotificationStatus) {
        for (alert in list) alerts.setNotificationStatus(alert.id, status)
    }
}
