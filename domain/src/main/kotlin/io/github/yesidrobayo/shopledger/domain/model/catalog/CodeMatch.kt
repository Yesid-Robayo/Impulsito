package io.github.yesidrobayo.shopledger.domain.model.catalog

/** Lo que encontró un código leído o escrito, buscado en los productos y en sus conversiones. */
sealed interface CodeMatch {
    /**
     * Un producto sin archivar. Con [conversion], el código es el de esa presentación, y la línea
     * usa su unidad.
     */
    data class Found(val product: Product, val conversion: ProductConversion?) : CodeMatch

    /** Un producto archivado: no se ofrece hasta reactivarlo. */
    data class Archived(val product: Product) : CodeMatch

    /** Ningún producto lo tiene: se ofrece crear uno con este código, o asignarlo a uno. */
    data class Unknown(val code: String) : CodeMatch
}
