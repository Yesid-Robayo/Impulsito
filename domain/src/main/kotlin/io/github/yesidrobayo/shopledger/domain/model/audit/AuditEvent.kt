package io.github.yesidrobayo.shopledger.domain.model.audit

import kotlin.time.Instant

/**
 * Un evento del registro de auditoría, tal como lo lee la pantalla: qué pasó, cuándo y cómo
 * terminó. No lleva montos, nombres, direcciones ni usuarios, porque la tabla no tiene dónde.
 *
 * @property id el orden del registro: el más alto es el más nuevo, diga lo que diga el reloj.
 * @property occurredAt la hora del teléfono al anotarlo. Informa, no ordena.
 */
data class AuditEvent(
    val id: Long,
    val occurredAt: Instant,
    val type: AuditEventType,
    val result: AuditResult,
)
