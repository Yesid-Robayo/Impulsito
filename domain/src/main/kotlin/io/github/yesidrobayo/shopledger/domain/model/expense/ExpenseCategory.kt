package io.github.yesidrobayo.shopledger.domain.model.expense

import kotlin.time.Instant

/**
 * Una categoría de gasto guardada.
 *
 * [systemKey] es la clave estable, en inglés, de una categoría inicial; las que crea la persona no
 * la tienen. [isSystem] marca la categoría de otros gastos, que nunca se archiva ni se borra, para
 * que siempre haya dónde anotar un gasto. Con gastos ya no se borra: se archiva.
 *
 * Lanza [IllegalArgumentException] si no tiene ni nombre ni clave: no habría con qué mostrarla.
 */
data class ExpenseCategory(
    val id: Long,
    val systemKey: String?,
    val details: ExpenseCategoryDetails,
    val isSystem: Boolean,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(details.name != null || systemKey != null) {
            "Una categoría de gasto sin nombre necesita su clave inicial"
        }
    }

    /** Si está archivada: no se ofrece en gastos nuevos. */
    val isArchived: Boolean get() = archivedAt != null
}
