package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.domain.model.report.MonthGoals
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.GoalRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.report.GoalRules
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.YearMonth

/**
 * Las metas de un mes con lo que se lleva de cada una.
 *
 * @property goals lo que escribió la persona, o `null` si el mes no tiene metas.
 * @property sales el avance de las ventas sin impuesto, menos lo devuelto, frente a su meta, o
 *   `null` sin meta de ventas.
 * @property profit el avance de la ganancia final frente a su meta, o `null` sin meta de ganancia.
 */
data class MonthGoalView(
    val month: YearMonth,
    val goals: MonthGoals?,
    val sales: GoalAdvance?,
    val profit: GoalAdvance?,
)

/**
 * Las metas de un mes y su avance, de nuevo con cada venta, devolución, gasto o anulación
 * confirmados y con cada meta guardada.
 *
 * Lo logrado sale de la misma cuenta que las estadísticas y que Inicio, con el mismo rango de
 * fechas: las ventas del mes son `net_sales`, y la ganancia es la final, después de gastos. No
 * escribe nada.
 */
class ObserveMonthGoalsUseCase(
    private val goals: GoalRepository,
    private val reports: ObservePeriodReportUseCase,
) {
    operator fun invoke(month: YearMonth): Flow<MonthGoalView> = combine(
        goals.observe(month),
        reports(ReportPeriod.Month(month)),
    ) { saved, report ->
        val profit = report.metrics?.profit
        MonthGoalView(
            month = month,
            goals = saved,
            sales = saved?.sales?.let { GoalRules.progress(profit?.netSales, it) },
            profit = saved?.profit?.let { GoalRules.progress(profit?.netProfit, it) },
        )
    }.distinctUntilChanged()
}

/**
 * La meta de ventas del mes en curso, para la tarjeta de Inicio: `null` si el mes no tiene meta de
 * ventas, y entonces la tarjeta no aparece.
 *
 * Como las otras cifras de Inicio, vuelve a mirar qué mes es con cada emisión de `dayChecks`, que
 * la pantalla emite al volver a primer plano. Sin zona conocida no se sabe qué mes es, y no hay
 * tarjeta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveCurrentMonthGoalUseCase(
    private val calendar: BusinessCalendarSource,
    private val goals: ObserveMonthGoalsUseCase,
    private val clock: Clock,
) {
    operator fun invoke(dayChecks: Flow<Unit>): Flow<GoalAdvance?> = combine(
        calendar.observe(),
        dayChecks,
    ) { result, _ ->
        when (result) {
            is BusinessCalendarResult.Ready -> {
                val today = result.calendar.businessDateOf(clock.now())
                result.calendar.month(today).yearMonth
            }

            is BusinessCalendarResult.UnknownZone -> null
        }
    }.distinctUntilChanged().flatMapLatest { month ->
        if (month == null) flowOf(null) else goals(month).map { it.sales }
    }.distinctUntilChanged()
}

/** Cómo terminó guardar las metas de un mes. */
sealed interface SaveGoalsResult {
    /** Quedaron guardadas, o ya estaban así y no se escribió nada. */
    data object Saved : SaveGoalsResult

    /** Las dos quedaron vacías: el mes ya no tiene metas. */
    data object Removed : SaveGoalsResult

    /**
     * Alguna meta escrita es cero o pasa del tope. [sales] y [profit] dicen cuál. Nada se guardó.
     */
    data class Invalid(val sales: Boolean, val profit: Boolean) : SaveGoalsResult

    /** La base no pudo escribir. Lo escrito sigue en la hoja. */
    data class Failed(val failure: StorageFailure) : SaveGoalsResult
}

/**
 * Guarda las metas que la persona escribió para un mes, pasado, en curso o futuro. Con las dos
 * vacías, el mes deja de tener metas. Si nada cambió, no escribe ni cuenta un cambio.
 *
 * Todo va en una sola transacción, que cuenta como cambio del negocio para la copia.
 */
class SaveMonthGoalsUseCase(
    private val transactions: TransactionRunner,
    private val goals: GoalRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(month: YearMonth, sales: Money?, profit: Money?): SaveGoalsResult {
        val badSales = sales != null && !GoalRules.isValid(sales)
        val badProfit = profit != null && !GoalRules.isValid(profit)
        if (badSales || badProfit) return SaveGoalsResult.Invalid(badSales, badProfit)
        val wanted = MonthGoals(month, sales, profit)
        return try {
            transactions.write {
                val current = goals.find(month)
                when {
                    sales == null && profit == null && current == null ->
                        TxResult.Rollback(SaveGoalsResult.Removed)

                    sales == null && profit == null -> {
                        goals.remove(month)
                        TxResult.Commit(SaveGoalsResult.Removed)
                    }

                    current == wanted -> TxResult.Rollback(SaveGoalsResult.Saved)

                    else -> {
                        goals.save(wanted, clock.now())
                        TxResult.Commit(SaveGoalsResult.Saved)
                    }
                }
            }
        } catch (failure: StorageFailureException) {
            SaveGoalsResult.Failed(failure.failure)
        }
    }
}
