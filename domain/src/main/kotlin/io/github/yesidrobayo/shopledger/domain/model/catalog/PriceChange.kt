package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Una fila guardada del historial de precios. Nunca se edita, y solo se borra junto con un
 * producto que no tiene historia.
 */
data class PriceChange(val id: Long, val content: NewPriceChange)
