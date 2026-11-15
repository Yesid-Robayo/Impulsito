package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.report.DailySummarySchedule
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Cuándo sale el resumen del día. Apagado por defecto: sin hora no se programa nada, y sin una
 * zona conocida tampoco, porque no se sabe qué día es.
 */
object DailySummaryRules {
    /** El horario de [settings], o `null` si el resumen está apagado o la zona no se conoce. */
    fun scheduleOf(settings: AppSettings): DailySummarySchedule? {
        val time = settings.notices.dailySummaryTime ?: return null
        val calendar = BusinessCalendar.of(settings.regional)
        if (calendar !is BusinessCalendarResult.Ready) return null
        return DailySummarySchedule(time, calendar.calendar.zone)
    }

    /**
     * La próxima vez que sale el resumen después de [now]: hoy a su hora si todavía no pasó, o
     * mañana. Una hora que no existe por un cambio de horario se corre a la siguiente que sí.
     */
    fun nextRun(now: Instant, schedule: DailySummarySchedule): Instant {
        val zone = schedule.zone.timeZone
        val today = now.toLocalDateTime(zone).date
        val atToday = LocalDateTime(today, schedule.time).toInstant(zone)
        if (atToday > now) return atToday
        return LocalDateTime(today.plus(1, DateTimeUnit.DAY), schedule.time).toInstant(zone)
    }
}
