package io.github.yesidrobayo.shopledger.domain.usecase.tax

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.port.TaxReportRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.rule.tax.TaxEstimateRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * El impuesto estimado de un período, para las estadísticas. Vuelve a emitir con cada cambio
 * confirmado en ventas, devoluciones, compras o en el modo de impuesto.
 *
 * Sin impuesto en el negocio no hay nada que mostrar, y el resultado lo dice: la pantalla no
 * muestra la tarjeta. Con impuesto, la cifra es una estimación, nunca una declaración.
 */
class ObserveTaxEstimateUseCase(
    private val taxSettings: TaxSettingsRepository,
    private val reports: TaxReportRepository,
) {
    operator fun invoke(period: ReportPeriod): Flow<TaxEstimateState> = combine(
        taxSettings.observe(),
        reports.observeTotals(period),
    ) { settings, totals ->
        when (settings.mode) {
            TaxMode.NONE -> TaxEstimateState.Hidden

            TaxMode.INCLUDED, TaxMode.ADDED ->
                TaxEstimateState.Estimated(TaxEstimateRules.estimate(totals))
        }
    }.distinctUntilChanged()
}

/** Si el impuesto estimado se muestra, y con qué cifras. */
sealed interface TaxEstimateState {
    /** El negocio no cobra impuesto: el reporte no aparece. */
    data object Hidden : TaxEstimateState

    /** La estimación del período, que se rotula como tal y no como una declaración. */
    data class Estimated(val estimate: TaxEstimate) : TaxEstimateState
}
