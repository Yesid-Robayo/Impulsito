package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Lo que el negocio fija para medir márgenes y sugerir precios: su margen objetivo, el de cada
 * categoría, cómo lleva el impuesto y con qué tasas, y el paso de redondeo. Vuelve a emitir cuando
 * algo de eso cambia, así una sugerencia abierta sigue el margen que se acaba de guardar en Ajustes.
 *
 * Lleva también las tasas archivadas: un producto puede seguir con una tasa que ya no se elige, y su
 * precio sigue llevándola.
 */
class ObservePricingContextUseCase(
    private val settings: AppSettingsRepository,
    private val categories: CategoryRepository,
    private val taxRates: TaxRateRepository,
) {
    operator fun invoke(): Flow<PricingContext> = combine(
        settings.observe(),
        categories.observeAll(),
        taxRates.observe(ArchiveFilter.ACTIVE),
        taxRates.observe(ArchiveFilter.ARCHIVED),
    ) { current, allCategories, active, archived ->
        val categoryTargets = buildMap<Long, BasisPoints> {
            allCategories.forEach { category ->
                category.details.targetMargin?.let { put(category.id, it) }
            }
        }
        PricingContext(
            defaultTargetMargin = current.profile.defaultTargetMargin,
            categoryTargets = categoryTargets,
            taxMode = current.taxes.mode,
            defaultTaxRateId = current.taxes.defaultTaxRateId,
            taxRates = (active + archived).associate { it.id to it.details.rate },
            roundingStep = current.sales.defaultRoundingStep,
        )
    }.distinctUntilChanged()
}
