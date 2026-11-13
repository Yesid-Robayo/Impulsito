package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayCollection
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.report.SoldAndCollected
import io.github.yesidrobayo.shopledger.domain.port.CollectionReportRepository
import io.github.yesidrobayo.shopledger.domain.rule.report.SoldAndCollectedRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Lo vendido y lo cobrado de un período, aparte, con lo que queda por cobrar hoy, o `null` si el
 * negocio no vende a crédito: sin ventas a crédito ni abonos, lo cobrado es lo vendido y no hace
 * falta otra cifra.
 *
 * @param receivable lo que deben hoy todos los clientes, o `null` si la suma desborda.
 */
class ObserveSoldAndCollectedUseCase(
    private val collections: CollectionReportRepository,
    private val receivable: Flow<Money?>,
) {
    operator fun invoke(period: ReportPeriod): Flow<SoldAndCollectedView?> = combine(
        collections.observeOnCredit(),
        collections.observeDaily(period),
        receivable,
    ) { onCredit, days, owed ->
        if (onCredit) SoldAndCollectedView(SoldAndCollectedRules.total(days), days, owed) else null
    }.distinctUntilChanged()

    /** La serie de la gráfica con lo cobrado de cada día, si el negocio vende a crédito. */
    fun series(period: ReportPeriod, points: Flow<List<DayPoint>?>): Flow<List<DayPoint>?> =
        combine(points, invoke(period)) { shown, view ->
            if (shown == null || view == null) {
                shown
            } else {
                SoldAndCollectedRules.withCollected(shown, view.days)
            }
        }.distinctUntilChanged()
}

/**
 * El resumen del período, `null` si desborda, los días con los que se armó y lo que queda por
 * cobrar hoy, que no depende del período.
 */
data class SoldAndCollectedView(
    val totals: SoldAndCollected?,
    val days: List<DayCollection>,
    val receivable: Money? = null,
)
