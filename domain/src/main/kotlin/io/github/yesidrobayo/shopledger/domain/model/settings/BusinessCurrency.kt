package io.github.yesidrobayo.shopledger.domain.model.settings

import kotlin.time.Instant

/**
 * La moneda del negocio: la de todos sus montos, que se guardan en su unidad menor.
 *
 * - [decimals] va de 0 a 4. Sale de ISO 4217, pero la persona puede cambiarlo antes del bloqueo,
 *   porque el valor de la norma no siempre es el de uso. Con [CurrencyCode.UNCONFIRMED] es 0.
 * - [symbol] es el símbolo que escribió la persona, de 1 a 8 caracteres. Nulo: el de ICU para el
 *   formato regional.
 * - [lockedAt] es cuándo se guardó el primer registro con dinero. Desde ahí, ni el código ni los
 *   decimales cambian: cambiarlos cambiaría el valor de todo lo guardado.
 */
data class BusinessCurrency(
    val code: CurrencyCode,
    val decimals: Int,
    val symbol: String?,
    val lockedAt: Instant?,
) {
    /** Si ya hay montos guardados en esta moneda. */
    val isLocked: Boolean get() = lockedAt != null
}
