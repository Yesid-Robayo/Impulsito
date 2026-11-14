package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.audit.AuditEventEntity
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.port.AuditRecorder
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.warn
import kotlinx.coroutines.CancellationException

/**
 * Anota en `audit_events` lo que un caso de uso pide, fuera de su transacción: el evento y su poda
 * van cada uno en su propia transacción corta, sin contar como cambio del negocio.
 *
 * Una falla al anotar nunca detiene nada: queda en el registro, solo con su clase, como [failed].
 * Sin la base abierta no anota nada.
 *
 * @param ports la base abierta, o `null` si no lo está.
 */
internal class RoomAuditRecorder(
    private val ports: suspend () -> DataPorts?,
    private val clock: Clock,
    private val log: EventLog,
    private val failed: DataEvent = DataEvent.CURRENCY_AUDIT_FAILED,
) : AuditRecorder {
    override suspend fun record(type: AuditEventType, result: AuditResult) {
        try {
            val open = ports() ?: return
            open.auditEvents.insert(
                AuditEventEntity(
                    occurredAt = clock.nowEpochMillis(),
                    eventType = type.name,
                    result = result.name,
                ),
            )
            open.auditEvents.pruneOldest()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.warn(failed, failure)
        }
    }
}
