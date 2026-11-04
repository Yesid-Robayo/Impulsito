package io.github.yesidrobayo.shopledger.domain.model

/**
 * Qué parte de un catálogo se lee: lo vigente o lo archivado.
 *
 * Un producto, un proveedor o una categoría de gasto con historia no se borra: se archiva. Lo
 * archivado no se ofrece al registrar nada nuevo, pero se sigue viendo con su filtro y se puede
 * reactivar.
 */
enum class ArchiveFilter {
    /** Solo lo que no está archivado: lo que se puede elegir hoy. */
    ACTIVE,

    /** Solo lo archivado. */
    ARCHIVED,
}
