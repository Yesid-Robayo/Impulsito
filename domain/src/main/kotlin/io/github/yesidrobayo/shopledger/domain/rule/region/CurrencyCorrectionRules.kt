package io.github.yesidrobayo.shopledger.domain.rule.region

import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCorrection
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings

/** Por qué no se puede corregir la moneda del negocio a la elegida. Nada se escribió. */
enum class CurrencyCorrectionProblem {
    /**
     * La moneda todavía no tiene registros con dinero: se cambia en el formulario, con sus
     * decimales, sin corrección.
     */
    NOT_LOCKED,

    /** La moneda elegida es `XXX`, la de sin moneda. */
    UNCONFIRMED,

    /** La moneda elegida ya es la del negocio: no hay nada que corregir. */
    SAME_AS_CURRENT,

    /**
     * La norma le da a la moneda elegida otros decimales que los del negocio: cada monto guardado
     * valdría otra cosa. Cambiar los decimales no es una corrección.
     */
    DECIMALS_DIFFER,

    /**
     * La moneda elegida es la de referencia: sus precios y costos de referencia quedarían en la
     * misma moneda que el libro. Primero hay que quitarla como referencia.
     */
    SAME_AS_REFERENCE,

    /**
     * Una cuenta propia ya está en la moneda elegida: su saldo, que se llevaba aparte, pasaría a
     * sumarse con el del negocio.
     */
    USED_BY_ACCOUNT,

    /**
     * Un cobro, una compra o una cotización guardó la moneda elegida como otra moneda: quedaría
     * convertida a sí misma.
     */
    USED_AS_OTHER_CURRENCY,
}

/**
 * Lo que revisa la corrección de la moneda del negocio con lo guardado, antes de mirar las cuentas
 * y los registros en otra moneda.
 *
 * Corregir es solo cambiar la etiqueta: los montos quedan iguales, en la unidad menor de la moneda
 * nueva. Por eso exige los mismos decimales. Nunca compara con una lista de monedas ni de países:
 * los decimales de la norma los dice el teléfono.
 */
object CurrencyCorrectionRules {
    /** El primer problema de corregir la moneda de [current] a [request], o `null` si no hay. */
    fun check(
        current: RegionalSettings,
        request: CurrencyCorrection,
    ): CurrencyCorrectionProblem? {
        val currency = current.currency
        return when {
            !currency.isLocked -> CurrencyCorrectionProblem.NOT_LOCKED

            !request.code.isConfirmed -> CurrencyCorrectionProblem.UNCONFIRMED

            request.code == currency.code -> CurrencyCorrectionProblem.SAME_AS_CURRENT

            request.isoDecimals != currency.decimals -> CurrencyCorrectionProblem.DECIMALS_DIFFER

            request.code == current.referenceCurrency?.code ->
                CurrencyCorrectionProblem.SAME_AS_REFERENCE

            else -> null
        }
    }
}
