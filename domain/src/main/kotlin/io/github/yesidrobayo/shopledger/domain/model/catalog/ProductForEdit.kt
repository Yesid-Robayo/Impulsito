package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Un producto tal como lo abre su formulario de edición: sus datos, lo que su historia ya no deja
 * cambiar, y si su costo inicial quedó fijo porque su libro tiene filas.
 *
 * Con [costLocked], el costo se muestra solo para leer: lo lleva el libro, y se corrige con una
 * corrección de costo, que deja su propia fila.
 */
data class ProductForEdit(
    val product: Product,
    val lockedFields: Set<HistoryLockedField>,
    val costLocked: Boolean,
)
