package io.github.yesidrobayo.shopledger.domain.usecase.tax

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateEntry
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxSetup
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Lo que muestra la pantalla de impuestos: el modo, las tasas vigentes y archivadas, cuál es la
 * tasa por defecto y cuántos productos usa cada una. Vuelve a emitir con cada cambio confirmado.
 */
class ObserveTaxSetupUseCase(
    private val rates: TaxRateRepository,
    private val taxSettings: TaxSettingsRepository,
) {
    operator fun invoke(): Flow<TaxSetup> = combine(
        taxSettings.observe(),
        rates.observe(ArchiveFilter.ACTIVE),
        rates.observe(ArchiveFilter.ARCHIVED),
        rates.observeProductCounts(),
    ) { settings, active, archived, counts ->
        TaxSetup(
            settings = settings,
            active = entries(active, settings, counts),
            archived = entries(archived, settings, counts),
        )
    }.distinctUntilChanged()

    private fun entries(
        rates: List<TaxRate>,
        settings: TaxSettings,
        counts: Map<Long, Int>,
    ): List<TaxRateEntry> = rates.map { rate ->
        TaxRateEntry(
            rate = rate,
            isDefault = rate.id == settings.defaultTaxRateId,
            productCount = counts[rate.id] ?: 0,
        )
    }
}
