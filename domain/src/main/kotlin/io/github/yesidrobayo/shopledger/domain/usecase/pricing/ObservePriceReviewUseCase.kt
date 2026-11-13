package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReview
import io.github.yesidrobayo.shopledger.domain.model.pricing.StaleCostCheck
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.rule.pricing.PriceReviewRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObservePricingContextUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * La lista `Revisar` del inventario: costo nuevo sin revisar, margen bajo el objetivo y costo
 * viejo, con las reglas de [PriceReviewRules]. Vuelve a emitir con cada cambio de productos,
 * categorías, margen objetivo, impuestos o días para costo viejo.
 *
 * Hoy es la fecha de negocio del instante actual en la zona del negocio, y se vuelve a mirar con
 * cada emisión de `dayChecks`. Si el teléfono no conoce la zona guardada, el costo viejo no se
 * evalúa y los otros dos motivos siguen.
 */
class ObservePriceReviewUseCase(
    private val products: ProductRepository,
    private val pricing: ObservePricingContextUseCase,
    private val calendar: BusinessCalendarSource,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    operator fun invoke(dayChecks: Flow<Unit> = flowOf(Unit)): Flow<List<PriceReview>> = combine(
        products.observe(ArchiveFilter.ACTIVE),
        pricing(),
        calendar.observe(),
        settings.observe().map { it.notices.costStaleDays }.distinctUntilChanged(),
        dayChecks,
    ) { list, context, calendarResult, staleDays, _ ->
        val check = when (calendarResult) {
            is BusinessCalendarResult.Ready -> {
                val today = calendarResult.calendar.businessDateOf(clock.now())
                StaleCostCheck(today, calendarResult.calendar.zone, staleDays)
            }

            is BusinessCalendarResult.UnknownZone -> null
        }
        PriceReviewRules.reviewsOf(list, context, check)
    }.distinctUntilChanged()
}
