package io.github.yesidrobayo.shopledger.domain.model.catalog

import kotlin.time.Instant

/**
 * Una unidad guardada, en la que se cuenta el stock de un producto o se registra una línea.
 *
 * Las iniciales llevan [systemKey] y ningún [name] ni [symbol]: su texto sale del recurso
 * traducible de la clave, en el idioma de la interfaz. Las que crea la persona llevan su nombre y
 * no tienen clave. [allowsFraction] dice si una cantidad en esta unidad puede tener decimales: una
 * unidad que se cuenta no los admite, porque media caja casi siempre es un error al escribir.
 *
 * Se llama así, y no `Unit`, para no confundirse con el tipo vacío de Kotlin.
 */
data class MeasureUnit(
    val id: Long,
    val systemKey: String?,
    val name: String?,
    val symbol: String?,
    val kind: UnitKind,
    val allowsFraction: Boolean,
    val archivedAt: Instant?,
) {
    /** Si está archivada: no se ofrece para un producto nuevo, pero explica lo ya registrado. */
    val isArchived: Boolean get() = archivedAt != null
}
