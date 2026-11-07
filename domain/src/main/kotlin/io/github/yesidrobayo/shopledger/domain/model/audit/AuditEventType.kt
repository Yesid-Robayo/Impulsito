package io.github.yesidrobayo.shopledger.domain.model.audit

/**
 * Qué acción sensible quedó en el registro de auditoría. Se guarda con su nombre en
 * `audit_events.event_type`, y la guarda de la tabla solo acepta estos valores: no se renombran.
 *
 * Cada evento dice qué pasó y cuándo, nunca montos, nombres, direcciones ni usuarios.
 */
enum class AuditEventType {
    /** Se exportó un CSV. */
    EXPORT_CSV,

    /** Se exportó una copia completa a un archivo. */
    EXPORT_FULL_COPY,

    /** Se restauró una copia. */
    RESTORE,

    /** Se rechazó una copia al restaurar. */
    RESTORE_REJECTED,

    /** Se desconectó el destino de las copias. */
    DESTINATION_DISCONNECTED,

    /** Se cambió el destino de las copias. */
    DESTINATION_CHANGED,

    /** Se encendió, se apagó o se cambió el bloqueo de la app. */
    APP_LOCK_CHANGED,

    /** Cambió el cifrado de las copias, o el código con que se cifran. */
    ENCRYPTION_CHANGED,

    /** Se vio el código de recuperación. */
    RECOVERY_CODE_VIEWED,

    /** Se borraron los datos desde la app. */
    DELETE_MY_DATA,

    /** Falló una autenticación. */
    AUTH_FAILED,
}
