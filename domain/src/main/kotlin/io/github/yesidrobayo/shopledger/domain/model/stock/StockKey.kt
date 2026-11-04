package io.github.yesidrobayo.shopledger.domain.model.stock

/**
 * Dónde está una parte del stock de un producto: en qué ubicación y, si el producto lleva lotes, en
 * qué lote. [lotId] es `null` en un producto sin lotes.
 *
 * El saldo de un producto es la suma de todas sus partes. Un compensatorio cae siempre en la misma
 * parte que la fila que anula: si cayera en otra, el total del producto cuadraría, pero una
 * ubicación quedaría con stock de más y otra con stock de menos, sin que nada lo delatara.
 */
data class StockKey(val productId: Long, val locationId: Long, val lotId: Long?)
