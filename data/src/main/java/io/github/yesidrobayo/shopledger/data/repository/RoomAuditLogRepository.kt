package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.audit.AuditEventDao
import io.github.yesidrobayo.shopledger.data.local.audit.AuditEventEntity
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEvent
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.port.AuditLogRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * El registro de auditoría para su pantalla, sobre `audit_events`. Solo lee.
 *
 * La guarda de la tabla solo acepta los nombres de `AuditEventType` y `AuditResult`; una fila con
 * otro nombre, de una versión más nueva de la app que la copia trajo, se deja fuera en vez de
 * romper la lista.
 */
internal class RoomAuditLogRepository(private val events: AuditEventDao) : AuditLogRepository {
    override fun observeLatest(limit: Int, failuresOnly: Boolean): Flow<List<AuditEvent>> {
        val rows =
            if (failuresOnly) events.observeLatestFailures(limit) else events.observeLatest(limit)
        return rows.map { list -> list.mapNotNull { it.toEvent() } }
    }
}

private fun AuditEventEntity.toEvent(): AuditEvent? {
    val type = AuditEventType.entries.firstOrNull { it.name == eventType } ?: return null
    val outcome = AuditResult.entries.firstOrNull { it.name == result } ?: return null
    return AuditEvent(id, Instant.fromEpochMilliseconds(occurredAt), type, outcome)
}
