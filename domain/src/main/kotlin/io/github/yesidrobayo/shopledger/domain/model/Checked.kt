package io.github.yesidrobayo.shopledger.domain.model

/**
 * Resultado de una cuenta que puede salirse de rango.
 *
 * Con datos absurdos, pero escribibles, una multiplicación puede no caber en `Long` o pasar de un
 * tope. No es un error de programación: la operación se rechaza con un motivo y no se guarda nada.
 * Por eso es un resultado que el compilador obliga a mirar, y no una excepción.
 */
sealed interface Checked<out T> {
    /** La cuenta cabe en `Long` y respeta el tope pedido, si se pidió uno. */
    data class Value<out T>(val value: T) : Checked<T>

    /** El resultado no cabe en `Long`, ni siquiera para guardarlo. */
    data object Overflow : Checked<Nothing>

    /** El resultado cabe en `Long`, pero supera [limit]. */
    data class AboveLimit(val limit: Limit) : Checked<Nothing>
}

/** Transforma el valor, si lo hay, y deja el rechazo tal como estaba. */
fun <T, R> Checked<T>.map(transform: (T) -> R): Checked<R> = when (this) {
    is Checked.Value -> Checked.Value(transform(value))
    Checked.Overflow -> Checked.Overflow
    is Checked.AboveLimit -> this
}

/**
 * El valor, o `null` si la cuenta se rechazó.
 *
 * Sirve donde un rechazo se muestra como "sin valor", como la suma de un reporte: la pantalla no
 * debe caerse por una cifra absurda.
 */
fun <T> Checked<T>.valueOrNull(): T? = when (this) {
    is Checked.Value -> value
    Checked.Overflow -> null
    is Checked.AboveLimit -> null
}
