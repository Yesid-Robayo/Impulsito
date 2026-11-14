package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.stock.ExpiringLot
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ExpiryNotice
import io.github.yesidrobayo.shopledger.domain.port.ExpiryNotifier
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.ExpiryRules
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * La lista `Por vencer`: los lotes con stock que ya están en sus días de aviso, o vencidos, del
 * que vence antes al que vence después. Funciona sin el permiso de avisos: no depende de ninguno.
 *
 * Hoy es la fecha de negocio del instante actual, y se vuelve a mirar con cada emisión de
 * `dayChecks`. Si el teléfono no conoce la zona guardada, no se puede saber qué día es y la lista
 * sale vacía.
 */
class ObserveExpiringLotsUseCase(
    private val lots: StockLotRepository,
    private val calendar: BusinessCalendarSource,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    operator fun invoke(dayChecks: Flow<Unit> = flowOf(Unit)): Flow<List<ExpiringLot>> = combine(
        lots.observeDated(),
        calendar.observe(),
        settings.observe().map { it.notices.expiryNoticeDays }.distinctUntilChanged(),
        dayChecks,
    ) { dated, calendarResult, noticeDays, _ ->
        when (calendarResult) {
            is BusinessCalendarResult.Ready -> {
                val today = calendarResult.calendar.businessDateOf(clock.now())
                ExpiryRules.expiringOf(dated, today, noticeDays)
            }

            is BusinessCalendarResult.UnknownZone -> emptyList()
        }
    }.distinctUntilChanged()
}

/**
 * Avisa una sola vez cada lote que entró en su aviso, y lo anota en `expiry_notified_at`.
 *
 * Corre al abrir la app y con cada cambio de lotes. Nunca dentro de una transacción de escritura,
 * porque avisar es lento y no es un dato del negocio. Primero avisa y después anota: si el proceso
 * muere entre las dos cosas, el aviso se repite una vez, que es mejor que no avisar nunca.
 *
 * Si el sistema no deja avisar, no anota nada: cuando la persona dé el permiso, avisa lo que siga
 * pendiente. La lista `Por vencer` no depende de esto.
 */
class DeliverExpiryNoticesUseCase(
    private val expiring: ObserveExpiringLotsUseCase,
    private val lots: StockLotRepository,
    private val units: UnitRepository,
    private val notifier: ExpiryNotifier,
    private val clock: Clock,
) {
    /** Avisa lo pendiente y devuelve cuántos avisos mostró. */
    suspend operator fun invoke(): Int {
        val pending = expiring().first().filter { it.lot.expiryNotifiedAt == null }
        if (pending.isEmpty()) return 0
        val unitsById = pending.map { it.lot.baseUnitId }.distinct()
            .associateWith { units.findById(it) }
        val notices = pending.map { ExpiryNotice(it, unitsById[it.lot.baseUnitId]) }
        if (!notifier.show(notices)) return 0
        lots.markExpiryNotified(pending.map { it.lot.lotId }, clock.now())
        return notices.size
    }
}
