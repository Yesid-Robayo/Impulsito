package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull
import io.github.yesidrobayo.shopledger.domain.port.SaleFiscalMarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.YearMonth

/**
 * Las ventas activas de un mes con una marca, con su resumen.
 *
 * - [total] es la suma de sus totales, o `null` si la suma no cabe: la pantalla muestra "sin
 *   valor".
 * - [hasMarks] dice si alguna venta, de cualquier mes, ya tiene marca: sin ninguna, es el primer
 *   uso.
 */
data class FiscalMonth(
    val month: YearMonth,
    val status: FiscalMarkStatus,
    val sales: List<FiscalSale>,
    val total: Money?,
    val hasMarks: Boolean,
)

/**
 * Lee las ventas de un mes con la marca elegida, de la más nueva a la más antigua. Con `PENDING`
 * también trae las ventas sin marca. Solo lee, y todo es local: funciona sin red.
 */
class ObserveFiscalMonthUseCase(private val marks: SaleFiscalMarkRepository) {
    operator fun invoke(month: YearMonth, status: FiscalMarkStatus): Flow<FiscalMonth> = combine(
        marks.observeSales(ReportPeriod.Month(month), status),
        marks.observeHasMarks(),
    ) { sales, hasMarks ->
        FiscalMonth(
            month = month,
            status = status,
            sales = sales,
            total = sales.map { it.total }.sumOrNull(),
            hasMarks = hasMarks,
        )
    }.distinctUntilChanged()
}
