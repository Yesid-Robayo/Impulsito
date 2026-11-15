package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.report.LimitNoticeMark
import io.github.yesidrobayo.shopledger.domain.model.report.RevenueLimitView
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.GoalRepository
import io.github.yesidrobayo.shopledger.domain.port.LimitNoticeStore
import io.github.yesidrobayo.shopledger.domain.port.PlanningReportRepository
import io.github.yesidrobayo.shopledger.domain.port.RevenueLimitNotifier
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.report.RevenueLimitRules
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
 * El límite de ingresos que escribió la persona y cuánto se lleva, de nuevo con cada venta,
 * devolución o anulación confirmada y con cada cambio del límite. `null` si no hay límite.
 *
 * Lo acumulado es `net_sales` de los meses que suma la ventana, incluido el mes en curso, con la
 * misma cuenta que las estadísticas. La app no conoce ningún tope: el monto y los meses son de la
 * persona. No escribe nada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveRevenueLimitUseCase(
    private val settings: AppSettingsRepository,
    private val planning: PlanningReportRepository,
    private val reports: ObservePeriodReportUseCase,
) {
    operator fun invoke(current: YearMonth): Flow<RevenueLimitView?> = settings
        .observe()
        .map { it.notices.revenueLimit }
        .distinctUntilChanged()
        .flatMapLatest { limit ->
            if (limit == null) flowOf<RevenueLimitView?>(null) else viewOf(current, limit)
        }
        .distinctUntilChanged()

    private fun viewOf(current: YearMonth, limit: RevenueLimit): Flow<RevenueLimitView> {
        val window = RevenueLimitRules.window(current, limit.months)
        return combine(reports(window), planning.observeFirstSaleDate()) { report, first ->
            val accumulated = report.metrics?.profit?.netSales
            val used = RevenueLimitRules.used(accumulated, limit.amount)
            RevenueLimitView(
                limit = limit,
                window = window,
                accumulated = accumulated,
                used = used,
                level = RevenueLimitRules.level(used),
                // Sin ventas antes del primer día de la ventana, la suma dice desde cuándo.
                since = first?.takeIf { it > window.start && it <= window.end },
            )
        }
    }
}

/** Cómo terminó guardar el límite de ingresos. */
sealed interface SaveLimitResult {
    /** Quedó guardado, o ya estaba así y no se escribió nada. */
    data object Saved : SaveLimitResult

    /** El negocio ya no tiene límite. */
    data object Removed : SaveLimitResult

    /** El monto o los meses no se pueden guardar. [amount] y [months] dicen cuál. */
    data class Invalid(val amount: Boolean, val months: Boolean) : SaveLimitResult

    /** La base no pudo escribir. Lo escrito sigue en la hoja. */
    data class Failed(val failure: StorageFailure) : SaveLimitResult
}

/**
 * Guarda el límite de ingresos que escribe la persona: un monto mayor que cero y de 1 a 60 meses,
 * que incluyen el mes en curso. Con `null` lo quita. Si nada cambió, no escribe ni cuenta un
 * cambio; si cambia, una sola transacción que cuenta para la copia.
 */
class SaveRevenueLimitUseCase(
    private val transactions: TransactionRunner,
    private val goals: GoalRepository,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(limit: RevenueLimit?): SaveLimitResult {
        if (limit != null) {
            val badAmount = !RevenueLimitRules.isValidAmount(limit.amount)
            val badMonths = !RevenueLimitRules.isValidMonths(limit.months)
            if (badAmount || badMonths) return SaveLimitResult.Invalid(badAmount, badMonths)
        }
        val done = if (limit == null) SaveLimitResult.Removed else SaveLimitResult.Saved
        return try {
            transactions.write {
                if (settings.get().notices.revenueLimit == limit) {
                    TxResult.Rollback(done)
                } else {
                    goals.saveRevenueLimit(limit, clock.now())
                    TxResult.Commit(done)
                }
            }
        } catch (failure: StorageFailureException) {
            SaveLimitResult.Failed(failure.failure)
        }
    }
}

/**
 * El límite del mes en curso, para el aviso que sale como notificación: como Inicio, mira qué mes
 * es con cada emisión de `dayChecks`. Sin zona conocida no hay mes, ni aviso.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveCurrentRevenueLimitUseCase(
    private val calendar: BusinessCalendarSource,
    private val limit: ObserveRevenueLimitUseCase,
    private val clock: Clock,
) {
    operator fun invoke(dayChecks: Flow<Unit>): Flow<RevenueLimitView?> = combine(
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
        if (month == null) flowOf(null) else limit(month)
    }.distinctUntilChanged()
}

/**
 * Avisa con una notificación cuando lo acumulado llega a un nivel del límite: al 80 %, al 95 % y
 * al pasarlo. Cada nivel avisa una sola vez por ventana, y bajar de nivel no avisa. La
 * notificación no lleva montos.
 *
 * Solo anota el aviso si salió: con las notificaciones apagadas, vuelve a intentar con el
 * siguiente cambio. No toca la base del negocio.
 */
class DeliverRevenueLimitNoticeUseCase(
    private val notifier: RevenueLimitNotifier,
    private val store: LimitNoticeStore,
) {
    /** Devuelve si salió un aviso. */
    suspend operator fun invoke(view: RevenueLimitView?): Boolean {
        if (view == null) return false
        val mark = LimitNoticeMark(
            windowStart = view.window.start,
            amount = view.limit.amount,
            months = view.limit.months,
            level = view.level,
        )
        if (!RevenueLimitRules.shouldNotify(mark, store.read())) return false
        if (!notifier.show(view.level, view.used)) return false
        store.write(mark)
        return true
    }
}
