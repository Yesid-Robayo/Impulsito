package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * A qué fila del catálogo apunta un producto. Si la que eligió la persona ya no existe, o ya no se
 * puede elegir, el caso de uso lo dice con esta referencia, para que la pantalla pida otra.
 */
enum class CatalogReference {
    CATEGORY,
    SUPPLIER,
    UNIT,
}
