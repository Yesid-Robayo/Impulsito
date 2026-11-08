package io.github.yesidrobayo.shopledger.domain.model.catalog

/** Lo que impide guardar una categoría de producto tal como se escribió. */
enum class CategoryFormIssue {
    /** El nombre queda vacío tras recortar espacios, o hecho solo de marcas sin letra. */
    NAME_EMPTY,

    /** El nombre pasa de 40 caracteres. */
    NAME_TOO_LONG,

    /** El margen objetivo es negativo o pasa del 99 %. */
    TARGET_MARGIN_OUT_OF_RANGE,
}
