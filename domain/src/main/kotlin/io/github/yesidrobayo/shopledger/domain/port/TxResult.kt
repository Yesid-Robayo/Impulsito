package io.github.yesidrobayo.shopledger.domain.port

/**
 * Cómo termina el bloque de una transacción de escritura de [TransactionRunner.write].
 *
 * Que el bloque lo diga con un resultado, y no con una excepción, deja que una regla de negocio
 * rechace una operación y devuelva su motivo sin escribir nada ni contar un cambio.
 */
sealed interface TxResult<out T> {
    /** Confirma todo lo escrito y devuelve [value]. */
    data class Commit<out T>(val value: T) : TxResult<T>

    /** Deshace todo lo escrito y devuelve [value], que suele ser el motivo del rechazo. */
    data class Rollback<out T>(val value: T) : TxResult<T>
}
