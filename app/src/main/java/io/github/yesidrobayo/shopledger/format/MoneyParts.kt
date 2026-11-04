package io.github.yesidrobayo.shopledger.format

/**
 * Un monto separado en partes, para dibujarlo, alinearlo y leerlo en voz alta.
 *
 * Son partes y no un texto porque cada una se trata distinto: las cifras van con cifras tabulares,
 * el signo se dibuja con su propio glifo, y TalkBack lee el número con el nombre de la moneda en
 * vez del signo. El símbolo de la moneda y su posición se agregan aquí cuando se resuelvan desde
 * ICU, con su propia corrida de texto.
 */
data class MoneyParts(
    /** Si el monto es menor que cero. El número de [number] nunca lleva signo. */
    val negative: Boolean,
    /**
     * Las cifras sin signo ni símbolo, con los separadores del formato regional y exactamente los
     * decimales de la moneda, por ejemplo `1.234,56`.
     */
    val number: String,
)
