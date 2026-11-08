package io.github.yesidrobayo.shopledger.domain.rule.region

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.rule.ReportPeriods
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant

/**
 * El calendario del negocio: la zona con que se fecha cada registro y el día en que empiezan sus
 * semanas, tal como están en sus ajustes en el momento de usarlo.
 *
 * Se arma desde los ajustes con [of] cada vez que hace falta, nunca se guarda aparte: así un
 * registro nuevo siempre usa la zona vigente al escribir. Los registros ya escritos no pasan por
 * aquí: su fecha de negocio quedó guardada y no se recalcula al cambiar la zona.
 */
data class BusinessCalendar(val zone: BusinessTimeZone, val weekStartDay: DayOfWeek) {
    /** La fecha de negocio de un registro ocurrido en [occurredAt]. */
    fun businessDateOf(occurredAt: Instant): BusinessDate = BusinessDate.of(occurredAt, zone)

    /** El instante [occurredAt] y su fecha de negocio, las dos formas que guarda un registro. */
    fun stamp(occurredAt: Instant): RecordTime = RecordTime(occurredAt, businessDateOf(occurredAt))

    /**
     * El instante y la fecha de un registro atrasado, con fecha de negocio [date] elegida por la
     * persona: las 12:00 de ese día en la zona del negocio, y la fecha tal cual.
     *
     * El mediodía queda lejos de los dos bordes del día, así que un cambio de horario nunca lo
     * pasa a otra fecha. El registro igual toma su lugar en el libro por orden de llegada, no por
     * esta hora. Quien llama decide antes que [date] no sea hoy ni una fecha futura.
     */
    fun stampBackdated(date: BusinessDate): RecordTime {
        val noon = LocalDateTime(date.date, LocalTime(BACKDATED_HOUR, 0))
        return RecordTime(noon.toInstant(zone.timeZone), date)
    }

    /** El día de [date]. */
    fun day(date: BusinessDate): ReportPeriod.Day = ReportPeriods.day(date)

    /** La semana del negocio que contiene [date]. */
    fun week(date: BusinessDate): ReportPeriod.Week = ReportPeriods.week(date, weekStartDay)

    /** El mes del calendario que contiene [date]. */
    fun month(date: BusinessDate): ReportPeriod.Month = ReportPeriods.month(date)

    /** El primer instante de [period] en la zona del negocio. */
    fun startInstant(period: ReportPeriod): Instant = ReportPeriods.startInstant(period, zone)

    /**
     * El instante en que termina [period], que ya no le pertenece: el comienzo del día siguiente en
     * la zona del negocio.
     */
    fun endInstantExclusive(period: ReportPeriod): Instant =
        ReportPeriods.endInstantExclusive(period, zone)

    companion object {
        /** La hora local que recibe un registro con fecha pasada. */
        private const val BACKDATED_HOUR = 12

        /**
         * El calendario de los ajustes regionales [regional], o [BusinessCalendarResult.UnknownZone]
         * si este teléfono no conoce la zona guardada.
         *
         * Una zona desconocida nunca se cambia en silencio por otra, ni por la del teléfono ni por
         * UTC: los registros nuevos caerían en otro día sin que la persona lo sepa, y esa fecha ya
         * no se corrige. Quien llama no registra nada con fecha y pide elegir la zona.
         */
        fun of(regional: RegionalSettings): BusinessCalendarResult =
            of(regional.businessTimeZoneId, regional.weekStartDay)

        /**
         * El calendario de la zona guardada [zoneId] y de las semanas que empiezan en
         * [weekStartDay], o [BusinessCalendarResult.UnknownZone] si este teléfono no conoce esa
         * zona. Igual que el otro [of], nunca la reemplaza.
         */
        fun of(zoneId: String, weekStartDay: DayOfWeek): BusinessCalendarResult {
            val zone =
                BusinessTimeZone.of(zoneId) ?: return BusinessCalendarResult.UnknownZone(zoneId)
            return BusinessCalendarResult.Ready(BusinessCalendar(zone, weekStartDay))
        }
    }
}
