package io.github.yesidrobayo.shopledger.domain.model.catalog

import kotlin.time.Instant

/**
 * Una categoría de producto guardada.
 *
 * Se puede renombrar y eliminar aunque esté en uso: no interviene en ninguna cuenta de dinero, así
 * que no tiene historia que proteger. Al eliminarla, sus productos quedan sin categoría.
 */
data class Category(
    val id: Long,
    val details: CategoryDetails,
    val createdAt: Instant,
    val updatedAt: Instant,
)
