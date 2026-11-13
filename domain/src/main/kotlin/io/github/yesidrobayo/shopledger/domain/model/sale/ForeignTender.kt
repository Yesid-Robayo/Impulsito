package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode

/**
 * Lo que se recibió en otra moneda por un pago: [amountMinor] en la unidad menor de [currency], que
 * tiene [decimals], con la cotización [rateMicro] que escribió la persona. La cotización son
 * unidades enteras de la moneda del negocio por una unidad entera de la otra, por 1.000.000.
 *
 * Nunca se mezcla con la moneda del negocio: el pago guarda su monto en la moneda del negocio, que
 * sale de aquí, y estos cuatro datos van aparte, para decir después qué se recibió. Nada se
 * descarga.
 */
data class ForeignTender(
    val currency: CurrencyCode,
    val decimals: Int,
    val amountMinor: Long,
    val rateMicro: Long,
)
