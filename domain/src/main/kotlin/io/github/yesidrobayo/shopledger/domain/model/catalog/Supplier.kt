package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * Un proveedor guardado.
 *
 * [balance] es lo que se le debe: positivo es deuda del negocio. Es un saldo derivado de su libro
 * de cuenta, que solo cambia en la misma transacción que agrega una fila a ese libro: editar el
 * proveedor nunca lo toca. Con compras o con filas en su libro ya no se borra: se archiva, y
 * [archivedAt] dice desde cuándo.
 */
data class Supplier(
    val id: Long,
    val details: SupplierDetails,
    val balance: Money,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Si está archivado: no se ofrece en compras nuevas ni como proveedor habitual. */
    val isArchived: Boolean get() = archivedAt != null
}
