package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints

/**
 * Lo que la persona escribe de una categoría de producto.
 *
 * [normalizedName] es [name] pasado por `TextNormalizer`: con él se compara si dos nombres son el
 * mismo y se busca. Lo calcula el caso de uso, nunca la base, para que comparar no dependa del
 * idioma del teléfono. [targetMargin] es el margen objetivo de sus productos, o `null` si la
 * categoría no fija ninguno y manda el del negocio.
 */
data class CategoryDetails(
    val name: String,
    val normalizedName: String,
    val targetMargin: BasisPoints?,
)
