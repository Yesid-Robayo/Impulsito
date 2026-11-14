package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult

/**
 * Anota una acción sensible en el registro de auditoría: qué pasó y cómo terminó, nunca montos ni
 * nombres. Va fuera de [TransactionRunner.write], después de la operación.
 */
fun interface AuditRecorder {
    /** Anota [type] con [result]. Nunca lanza: una falla al anotar no detiene nada. */
    suspend fun record(type: AuditEventType, result: AuditResult)
}
