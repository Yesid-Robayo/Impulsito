package io.github.yesidrobayo.shopledger.format

/**
 * Un monto separado en partes, para dibujarlo, alinearlo y leerlo en voz alta.
 *
 * Son partes y no un texto porque cada una se trata distinto: las cifras van con cifras tabulares,
 * el símbolo va en su propia corrida de texto con la fuente que lo tenga, el signo se dibuja con su
 * propio glifo, y TalkBack lee el número con el nombre de la moneda en vez del símbolo.
 *
 * El orden en pantalla sale de [symbolFirst] y [signPosition], que son los de la región: la app
 * nunca decide dónde va el símbolo ni el signo.
 */
data class MoneyParts(
    /** Si el monto es menor que cero. El número de [number] nunca lleva signo. */
    val negative: Boolean,
    /**
     * Las cifras sin signo ni símbolo, con los separadores del formato regional y exactamente los
     * decimales de la moneda, por ejemplo `1.234,56`.
     */
    val number: String,
    /**
     * El símbolo de la moneda tal como se pide dibujar: el que escribió la persona o, si no
     * escribió ninguno, el que da ICU para la región. Sin marcas de dirección. Si ICU no da nada
     * visible, es el código ISO. Puede necesitar otra fuente: eso lo decide la pantalla.
     */
    val symbol: String,
    /** Si la región pone el símbolo antes de las cifras. */
    val symbolFirst: Boolean,
    /** Si entre el símbolo y las cifras va un espacio, que siempre es duro. */
    val symbolSpace: Boolean,
    /** Dónde va el signo menos de un negativo, respecto del símbolo y las cifras. */
    val signPosition: SignPosition,
    /** El código ISO 4217 de la moneda, que reemplaza al símbolo si ninguna fuente lo dibuja. */
    val currencyCode: String,
    /**
     * El nombre de la moneda que da ICU en el idioma de la interfaz, en singular o plural según el
     * monto, por ejemplo `euros`. Es lo que lee TalkBack. Si ICU no lo conoce, es el código ISO.
     */
    val currencyName: String,
)

/**
 * El lugar del signo menos de un negativo, tal como lo pone la región. Los ejemplos usan `¤` por el
 * símbolo y van con el símbolo antes y después de las cifras.
 */
enum class SignPosition {
    /** Antes de todo: `−¤ 1.200` o `−1.200 ¤`. */
    LEADING,

    /** Entre el símbolo y las cifras, pegado a las cifras: `¤ −1.200` o `1.200− ¤`. */
    NEXT_TO_NUMBER,

    /** Entre el símbolo y las cifras, pegado al símbolo: `¤− 1.200` o `1.200 −¤`. */
    NEXT_TO_SYMBOL,

    /** Después de todo: `¤ 1.200−` o `1.200 ¤−`. */
    TRAILING,
}
