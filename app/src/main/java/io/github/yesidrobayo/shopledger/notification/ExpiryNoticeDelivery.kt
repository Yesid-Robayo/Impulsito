package io.github.yesidrobayo.shopledger.notification

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.di.AppEvent
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.ExpiryNotifier
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.stock.DeliverExpiryNoticesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveExpiringLotsUseCase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Lanza los avisos de vencimiento pendientes mientras la base está abierta.
 *
 * Avisa al abrir la app y cada vez que cambia qué lotes esperan aviso: uno que entra en sus días,
 * una compra con un vencimiento cercano. Cada lote se avisa una sola vez. Como el día cambia solo,
 * abrir la app es lo que mira de nuevo la fecha.
 *
 * Una falla al entregar se registra por su clase y no detiene a las siguientes.
 *
 * @param scope el alcance de la aplicación: sigue mientras viva el proceso.
 */
class ExpiryNoticeDelivery(
    private val scope: CoroutineScope,
    private val notifier: ExpiryNotifier,
    private val clock: Clock,
    private val log: EventLog,
) {
    private val started = AtomicBoolean(false)

    /** Empieza a seguir los lotes de [ports]. Solo la primera llamada cuenta. */
    fun follow(ports: DataPorts) {
        if (!started.compareAndSet(false, true)) return
        val expiring =
            ObserveExpiringLotsUseCase(
                ports.stockLots,
                BusinessCalendarSource(ports.settings),
                ports.settings,
                clock,
            )
        val deliver =
            DeliverExpiryNoticesUseCase(expiring, ports.stockLots, ports.units, notifier, clock)
        scope.launch {
            expiring()
                .map { lots ->
                    lots.filter { it.lot.expiryNotifiedAt == null }.map { it.lot.lotId }
                }
                .distinctUntilChanged()
                .collect { pending ->
                    if (pending.isNotEmpty()) {
                        log.contain(AppEvent.EXPIRY_DELIVERY_FAILED) { deliver() }
                    }
                }
        }
    }
}
