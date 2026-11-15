package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DailySummary
import io.github.yesidrobayo.shopledger.domain.model.report.DailySummarySchedule
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.report.DailySummaryRules
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodMetricsCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalTime

// El resumen del día: una notificación local, apagada por defecto, que lanza el propio teléfono.
// Sin servidor ni red: puede retrasarse, y nunca muestra montos con el teléfono bloqueado.

/** Resultado de [SetDailySummaryTimeUseCase]. */
sealed interface SetDailySummaryTimeResult {
    /** Quedó así, o ya estaba así y no hacía falta escribir. */
    data object Saved : SetDailySummaryTimeResult

    /** La base no pudo escribir, por [failure]. No cambió nada. */
    data class Failed(val failure: StorageFailure) : SetDailySummaryTimeResult
}

/**
 * Elige la hora del resumen del día, en la zona del negocio, o lo apaga con `null`. Es un dato del
 * negocio y viaja en la copia: va en su propia transacción y cuenta como cambio. Los segundos no
 * se guardan. Si ya estaba así, no escribe.
 */
class SetDailySummaryTimeUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(time: LocalTime?): SetDailySummaryTimeResult {
        val minute = time?.let { LocalTime(it.hour, it.minute) }
        return try {
            transactions.write {
                if (settings.get().notices.dailySummaryTime == minute) {
                    TxResult.Rollback(SetDailySummaryTimeResult.Saved)
                } else {
                    settings.updateDailySummaryTime(minute, clock.now())
                    TxResult.Commit(SetDailySummaryTimeResult.Saved)
                }
            }
        } catch (failure: StorageFailureException) {
            SetDailySummaryTimeResult.Failed(failure.failure)
        }
    }
}

/**
 * El horario del resumen, de nuevo cada vez que cambia la hora o la zona, o `null` si está
 * apagado o la zona no se conoce. Con él se programa, o se cancela, el trabajo del teléfono.
 */
class ObserveDailySummaryScheduleUseCase(private val settings: AppSettingsRepository) {
    operator fun invoke(): Flow<DailySummarySchedule?> =
        settings.observe().map { DailySummaryRules.scheduleOf(it) }.distinctUntilChanged()
}

/**
 * El resumen de hoy, con las mismas sumas de `Números`, o `null` si el resumen está apagado, la
 * zona no se conoce o una cifra no cabe. Solo lee.
 */
class BuildDailySummaryUseCase(
    private val settings: AppSettingsRepository,
    private val reports: PeriodReportRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): DailySummary? {
        val current = settings.get()
        if (current.notices.dailySummaryTime == null) return null
        val calendar = BusinessCalendar.of(current.regional)
        if (calendar !is BusinessCalendarResult.Ready) return null
        val today = calendar.calendar.businessDateOf(clock.now())
        val totals = reports.observeTotals(ReportPeriod.Day(today)).first()
        val metrics = PeriodMetricsCalculator.of(totals).valueOrNull() ?: return null
        return DailySummary(
            date = today,
            salesCount = metrics.salesCount,
            netSales = metrics.profit.netSales,
            grossProfit = metrics.profit.grossProfit,
            discreet = current.preferences.discreetModeEnabled,
        )
    }
}
