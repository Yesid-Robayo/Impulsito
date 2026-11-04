package io.github.yesidrobayo.shopledger.domain.usecase.region

import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El calendario del negocio, leído de sus ajustes: la zona y el primer día de la semana salen
 * siempre de ahí, nunca del teléfono ni de un valor fijo.
 *
 * Un caso de uso que escribe un registro con fecha llama a [current] dentro de su propia
 * transacción, justo antes de escribir: así la fecha de negocio sale de la zona vigente en ese
 * momento, aunque la persona la haya cambiado un instante antes. No escribe nada.
 */
class BusinessCalendarSource(private val settings: AppSettingsRepository) {
    /** El calendario de los ajustes de ahora. */
    suspend fun current(): BusinessCalendarResult = BusinessCalendar.of(settings.get().regional)

    /**
     * El calendario, de nuevo cada vez que cambian la zona o la semana. Un cambio de otro ajuste no
     * vuelve a emitir: los reportes no se rearman sin motivo.
     */
    fun observe(): Flow<BusinessCalendarResult> =
        settings.observe().map { BusinessCalendar.of(it.regional) }.distinctUntilChanged()
}
