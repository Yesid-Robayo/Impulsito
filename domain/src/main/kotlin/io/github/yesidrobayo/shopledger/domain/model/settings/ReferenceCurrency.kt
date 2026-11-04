package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * Otra moneda en la que la persona sigue costos o precios de referencia, con sus decimales, de 0 a
 * 4. Nunca se mezcla con la del negocio: sus montos van en columnas propias.
 */
data class ReferenceCurrency(val code: CurrencyCode, val decimals: Int)
