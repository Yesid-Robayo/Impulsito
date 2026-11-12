package io.github.yesidrobayo.shopledger.data.local

/**
 * Una fila de un conteo agrupado: cuántas filas, [count], apuntan a la fila [ownerId] de otra
 * tabla, como los productos de una categoría.
 */
internal data class OwnerCount(val ownerId: Long, val count: Int)
