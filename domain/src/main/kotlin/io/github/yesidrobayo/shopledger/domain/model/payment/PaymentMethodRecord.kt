package io.github.yesidrobayo.shopledger.domain.model.payment

import kotlin.time.Instant

/**
 * Un método de pago guardado: uno de los iniciales, con su clave, o uno que creó la persona.
 *
 * [systemKey] es la clave estable, en inglés, de un método inicial, como `CASH`. Sigue
 * identificándolo aunque la persona lo renombre. Los que crea la persona no la tienen. [sortOrder]
 * es el orden en que se ofrecen al cobrar. Con ventas ya no se borra: se archiva, y [archivedAt]
 * dice desde cuándo. Sus ventas guardan su propia copia del nombre y del tipo, así que editarlo o
 * archivarlo no cambia ninguna venta pasada.
 *
 * Lanza [IllegalArgumentException] si no tiene ni nombre ni clave: no habría con qué mostrarlo.
 */
data class PaymentMethodRecord(
    val id: Long,
    val systemKey: String?,
    val details: PaymentMethodDetails,
    val sortOrder: Long,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(details.name != null || systemKey != null) {
            "Un método de pago sin nombre necesita su clave inicial"
        }
    }

    /** Si está archivado: no se ofrece al cobrar. */
    val isArchived: Boolean get() = archivedAt != null
}
