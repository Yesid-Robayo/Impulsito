package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * La moneda a la que la persona corrige la del negocio, cuando la eligió mal y ya hay registros
 * con dinero.
 *
 * - [code] es la moneda correcta, de ISO 4217.
 * - [isoDecimals] son los decimales que la norma le da a [code], según lo que sabe el teléfono. La
 *   corrección solo vale si son los mismos decimales que ya usa el negocio: así cada monto
 *   guardado sigue valiendo lo mismo, ahora en [code].
 */
data class CurrencyCorrection(val code: CurrencyCode, val isoDecimals: Int)
