package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEvent
import kotlinx.coroutines.flow.Flow

/** El registro de auditoría para leerlo. Solo lee: nada aquí edita, borra ni exporta un evento. */
interface AuditLogRepository {
    /**
     * Los [limit] eventos más nuevos, primero el último, o solo los que terminaron en fallo si
     * [failuresOnly]. Emite de nuevo cada vez que entra un evento.
     */
    fun observeLatest(limit: Int, failuresOnly: Boolean): Flow<List<AuditEvent>>
}
