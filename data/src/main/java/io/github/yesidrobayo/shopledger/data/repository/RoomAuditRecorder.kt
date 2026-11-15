package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.audit.AuditEventDao
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
 * @param events el registro de la base abierta, o `null` si no lo está.
 */
internal class RoomAuditRecorder(
    private val events: suspend () -> AuditEventDao?,
    private val clock: Clock,
    private val log: EventLog,
    private val failed: DataEvent = DataEvent.AUDIT_FAILED,
) : AuditRecorder {
    override suspend fun record(type: AuditEventType, result: AuditResult) {
        val dao =
            try {
                events()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.warn(failed, failure)
                null
            } ?: return
        recordAuditEvent(dao, type, result, clock, log, failed)
    }
}

/**
 * Anota [type] con [result] en [events], y poda lo que pase del tope. Va siempre fuera de
 * `TransactionRunner.write`: cada sentencia corre en su propia transacción corta de Room, así que
 * el evento no suma a `change_seq` y su falla no deshace la operación que lo causó.
 *
 * Nunca lanza, salvo la cancelación: una falla queda en el registro, solo por su clase, como
 * [failed]. El evento no lleva montos, nombres, direcciones ni usuarios: la tabla no tiene dónde.
 */
internal suspend fun recordAuditEvent(
    events: AuditEventDao,
    type: AuditEventType,
    result: AuditResult,
    clock: Clock,
    log: EventLog,
    failed: DataEvent = DataEvent.AUDIT_FAILED,
) {
    try {
        events.insert(
            AuditEventEntity(
                occurredAt = clock.nowEpochMillis(),
                eventType = type.name,
                result = result.name,
            ),
        )
        events.pruneOldest()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        log.warn(failed, failure)
    }
}
