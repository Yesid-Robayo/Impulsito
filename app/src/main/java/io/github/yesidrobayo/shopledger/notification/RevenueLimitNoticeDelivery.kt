package io.github.yesidrobayo.shopledger.notification

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.di.AppEvent
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LimitNoticeStore
import io.github.yesidrobayo.shopledger.domain.port.RevenueLimitNotifier
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.report.DeliverRevenueLimitNoticeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveCurrentRevenueLimitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodReportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveRevenueLimitUseCase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Lanza el aviso del límite de ingresos mientras la base está abierta.
 *
 * Mira el nivel al abrir la app y cada vez que cambia: una venta, una devolución, una anulación o
 * un límite nuevo. Cada nivel avisa una sola vez por ventana, y sin límite no hay nada que avisar.
 * Como las ventas solo ocurren con la app abierta, no hace falta un trabajo en segundo plano.
 *
 * Una falla al entregar se registra por su clase y no detiene a las siguientes.
 *
 * @param scope el alcance de la aplicación: sigue mientras viva el proceso.
 */
class RevenueLimitNoticeDelivery(
    private val scope: CoroutineScope,
    private val notifier: RevenueLimitNotifier,
    private val store: LimitNoticeStore,
    private val clock: Clock,
    private val log: EventLog,
) {
    private val started = AtomicBoolean(false)

    /** Empieza a seguir el límite de [ports]. Solo la primera llamada cuenta. */
    fun follow(ports: DataPorts) {
        if (!started.compareAndSet(false, true)) return
        val limit = ObserveRevenueLimitUseCase(
            ports.settings,
            ports.planningReports,
            ObservePeriodReportUseCase(ports.periodReports),
        )
        val current =
            ObserveCurrentRevenueLimitUseCase(BusinessCalendarSource(ports.settings), limit, clock)
        val deliver = DeliverRevenueLimitNoticeUseCase(notifier, store)
        scope.launch {
            current(flowOf(Unit))
                // Solo importa cuando cambia el nivel o la ventana, no cada venta.
                .distinctUntilChangedBy { view ->
                    view?.let { Triple(it.window, it.limit, it.level) }
                }
                .collect { view ->
                    log.contain(AppEvent.LIMIT_DELIVERY_FAILED) { deliver(view) }
                }
        }
    }
}
