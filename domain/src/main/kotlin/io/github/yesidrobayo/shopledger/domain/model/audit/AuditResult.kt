package io.github.yesidrobayo.shopledger.domain.model.audit

/** Cómo terminó la acción de un evento de auditoría. Se guarda con su nombre en `audit_events.result`. */
enum class AuditResult {
    SUCCESS,
    FAILURE,
}
