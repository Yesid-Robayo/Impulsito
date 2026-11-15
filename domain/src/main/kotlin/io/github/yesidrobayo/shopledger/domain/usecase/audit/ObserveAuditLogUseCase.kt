package io.github.yesidrobayo.shopledger.domain.usecase.audit

import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEvent
import io.github.yesidrobayo.shopledger.domain.port.AuditLogRepository
import kotlinx.coroutines.flow.Flow

/**
 * El registro de auditoría para su pantalla, por tramos: se pide un tope y se agranda al llegar al
 * final de la lista. El tope nunca baja de un evento ni pasa de lo que la tabla conserva.
 */
class ObserveAuditLogUseCase(private val log: AuditLogRepository) {
    operator fun invoke(limit: Int, failuresOnly: Boolean): Flow<List<AuditEvent>> =
        log.observeLatest(limit.coerceIn(1, MAX_EVENTS), failuresOnly)

    companion object {
        /** Cuántos eventos conserva el registro: por encima, la poda borra los más viejos. */
        const val MAX_EVENTS = 5_000

        /** Cuántos eventos trae cada tramo de la lista. */
        const val PAGE_SIZE = 100
    }
}
