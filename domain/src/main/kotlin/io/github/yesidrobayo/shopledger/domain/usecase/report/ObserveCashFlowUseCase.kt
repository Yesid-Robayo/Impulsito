package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowProjection
import io.github.yesidrobayo.shopledger.domain.port.CashFlowSource
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ModuleUsageSource
import io.github.yesidrobayo.shopledger.domain.port.RecurringExpenseRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.report.CashFlowRules
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * El flujo de caja proyectado de hoy, a 7 y 30 días, o `null` si no hay cómo calcularlo. Así la
 * herramienta no aparece cuando no tiene datos:
 *
 * - **Sin cuentas en uso**, no hay saldo del que partir. La cuenta de efectivo que nace con la
 *   base no basta: hace falta que la persona lleve cuentas, como al pagar un gasto.
 * - **Sin cuentas vigentes en la moneda del negocio**, tampoco.
 * - **Sin una zona conocida** no se sabe qué día es.
 *
 * Hoy es la fecha de negocio del instante actual, y se vuelve a mirar con cada emisión de
 * `dayChecks`. No depende del período que muestra la pantalla: siempre parte de hoy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveCashFlowUseCase(
    private val source: CashFlowSource,
    private val recurring: RecurringExpenseRepository,
    private val usage: ModuleUsageSource,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
) {
    operator fun invoke(dayChecks: Flow<Unit> = flowOf(Unit)): Flow<CashFlowProjection?> = combine(
        calendar.observe(),
        usage.observe().map { BusinessTool.MONEY_ACCOUNTS in it }.distinctUntilChanged(),
        dayChecks,
    ) { result, inUse, _ ->
        when (result) {
            is BusinessCalendarResult.Ready ->
                result.calendar.businessDateOf(clock.now()).takeIf { inUse }

            is BusinessCalendarResult.UnknownZone -> null
        }
    }.distinctUntilChanged().flatMapLatest { today ->
        if (today == null) {
            flowOf(null)
        } else {
            val sums = source.observe(
                today,
                today.plusDays(CashFlowRules.SHORT_DAYS),
                today.plusDays(CashFlowRules.LONG_DAYS),
            )
            combine(sums, recurring.observe(ArchiveFilter.ACTIVE)) { read, active ->
                read.takeIf { it.accountCount > 0 }
                    ?.let { CashFlowRules.project(today, it, active) }
            }
        }
    }.distinctUntilChanged()
}
