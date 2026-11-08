package io.github.yesidrobayo.shopledger.domain.model.tax

import kotlin.time.Instant

/**
 * Una tasa de impuesto que escribió la persona, con el nombre que ella quiera.
 *
 * La app no trae ninguna: no conoce impuestos de ningún país. Cada línea de venta o de compra copia
 * la tasa y su nombre al registrarse, así que editar, archivar o eliminar una tasa nunca cambia un
 * documento pasado. Archivada, [archivedAt] dice desde cuándo, y deja de ofrecerse.
 */
data class TaxRate(
    val id: Long,
    val details: TaxRateDetails,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Si está archivada: no se ofrece en productos nuevos ni como tasa por defecto. */
    val isArchived: Boolean get() = archivedAt != null
}
