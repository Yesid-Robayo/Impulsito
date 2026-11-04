package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * Un código de moneda de ISO 4217: tres letras mayúsculas, como el que guarda la base.
 *
 * No hay una lista de monedas válidas: una copia de otro negocio puede traer una moneda que este
 * teléfono no conoce, y solo se revisa la forma. [UNCONFIRMED] es `XXX`, el código de ISO 4217 que
 * significa sin moneda: el negocio nace con él, y mientras lo tenga no puede guardar nada con
 * dinero.
 */
@JvmInline
value class CurrencyCode private constructor(val code: String) {
    /** Si la persona ya eligió una moneda. */
    val isConfirmed: Boolean get() = code != UNCONFIRMED_CODE

    override fun toString(): String = code

    companion object {
        private const val UNCONFIRMED_CODE = "XXX"
        private const val LENGTH = 3

        /** Sin moneda todavía: el código con que nace el negocio. */
        val UNCONFIRMED: CurrencyCode = CurrencyCode(UNCONFIRMED_CODE)

        /**
         * El código [code], o `null` si no son tres letras mayúsculas de la A a la Z. Viene de la
         * base o de una copia, que no son confiables.
         */
        fun of(code: String): CurrencyCode? =
            if (code.length == LENGTH && code.all { it in 'A'..'Z' }) CurrencyCode(code) else null
    }
}
