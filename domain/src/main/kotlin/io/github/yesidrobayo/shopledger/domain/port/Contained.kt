package io.github.yesidrobayo.shopledger.domain.port

/**
 * Cómo terminó un trabajo que corrió con [contain]: con su valor, o con una falla ya registrada.
 *
 * Una falla no sale como excepción. Quien corre el trabajo, por ejemplo un trabajo en segundo plano,
 * solo decide si lo reintenta: la excepción, con su mensaje, ya no existe.
 */
sealed interface Contained<out T> {
    /** El trabajo terminó y devolvió [value]. */
    data class Done<out T>(val value: T) : Contained<T>

    /**
     * El trabajo falló. Solo queda el nombre de la clase de la excepción, el mismo que se registró:
     * nunca su mensaje.
     */
    data class Failed(val failureClass: String) : Contained<Nothing>
}
