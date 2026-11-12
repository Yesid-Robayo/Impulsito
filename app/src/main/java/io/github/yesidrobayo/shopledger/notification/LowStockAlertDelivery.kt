package io.github.yesidrobayo.shopledger.notification

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.di.AppEvent
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LowStockNotifier
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.usecase.stock.DeliverLowStockAlertsUseCase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Lanza los avisos de stock bajo pendientes mientras la base está abierta.
 *
 * Avisa al abrir la app, por lo que quedó pendiente si el proceso murió entre confirmar una venta y
 * avisar, y después de cada alerta nueva: sigue las alertas activas, y cada vez que cambia cuáles
 * esperan aviso, entrega lo pendiente. Como el stock solo cambia con la app abierta, no hace falta
 * ningún trabajo en segundo plano.
 *
 * Una falla al entregar se registra por su clase y no detiene a las siguientes.
 *
 * @param scope el alcance de la aplicación: sigue mientras viva el proceso.
 */
class LowStockAlertDelivery(
    private val scope: CoroutineScope,
    private val notifier: LowStockNotifier,
    private val log: EventLog,
) {
    private val started = AtomicBoolean(false)

    /** Empieza a seguir las alertas de [ports]. Solo la primera llamada cuenta. */
    fun follow(ports: DataPorts) {
        if (!started.compareAndSet(false, true)) return
        val deliver =
            DeliverLowStockAlertsUseCase(
                alerts = ports.lowStockAlerts,
                products = ports.products,
                units = ports.units,
                settings = ports.settings,
                notifier = notifier,
            )
        scope.launch {
            ports.lowStockAlerts
                .observeActive()
                .map { active ->
                    active
                        .filter { it.notificationStatus == LowStockNotificationStatus.PENDING }
                        .map { it.id }
                }.distinctUntilChanged()
                .collect { log.contain(AppEvent.LOW_STOCK_DELIVERY_FAILED) { deliver() } }
        }
    }
}
