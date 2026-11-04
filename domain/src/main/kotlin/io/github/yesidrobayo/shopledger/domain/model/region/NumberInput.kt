package io.github.yesidrobayo.shopledger.domain.model.region

/**
 * Lo que sale de leer un número que la persona escribió en un campo: el valor exacto, o el motivo
 * por el que no se puede guardar.
 *
 * Un número mal escrito no es un error de programación: es lo normal mientras alguien escribe. Por
 * eso es un resultado que el formulario tiene que mirar, y cada motivo lleva lo que la pantalla
 * necesita para explicarlo en el idioma de la persona. Nunca se redondea para que un valor entre:
 * `12,345` en un campo de dos decimales no es `12,35`, es un error de digitación.
 */
sealed interface NumberInput<out T> {
    /** El número escrito, exacto. */
    data class Value<out T>(val value: T) : NumberInput<T>

    /** El campo está vacío o solo tiene espacios. El formulario decide si es obligatorio. */
    data object Blank : NumberInput<Nothing>

    /**
     * El texto no es un número del formato regional: tiene letras, dos separadores decimales, un
     * signo fuera de lugar o grupos de cifras que el formato no escribiría, como `1.5` en una
     * región que agrupa con punto. Leerlo de otra forma sería adivinar.
     */
    data object Malformed : NumberInput<Nothing>

    /** Tiene más decimales que [allowed], que es lo máximo que guarda el campo. */
    data class TooManyDecimals(val allowed: Int) : NumberInput<Nothing>

    /**
     * Su valor absoluto supera [maximum], el tope del campo. Casi siempre es un error de
     * digitación, y la pantalla muestra el tope con el mismo formato que el campo.
     */
    data class TooLarge<out T>(val maximum: T) : NumberInput<T>
}
