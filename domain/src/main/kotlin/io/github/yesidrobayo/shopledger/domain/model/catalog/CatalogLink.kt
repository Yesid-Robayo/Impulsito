package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Cómo usa el catálogo a un producto desde otro producto.
 *
 * No es historia: no dice nada de lo que pasó con el producto, y se deshace editando el otro
 * producto. Pero mientras exista, el producto no se puede eliminar, porque el otro quedaría
 * apuntando a nada. Por eso eliminar lo dice antes de intentarlo, con cada vínculo que lo impide.
 */
enum class CatalogLink {
    /** Es componente de un producto hecho de componentes. */
    COMPONENT,

    /** Es un extra que se ofrece al vender otro producto. */
    EXTRA,

    /** Tiene variantes: otros productos lo nombran como su producto principal. */
    VARIANT_PARENT,
}
