package io.github.yesidrobayo.shopledger.domain.port

/**
 * Cómo termina el bloque de una transacción de escritura de [TransactionRunner.write].
 *
 * Que el bloque lo diga con un resultado, y no con una excepción, deja que una regla de negocio
 * rechace una operación y devuelva su motivo sin escribir nada ni contar un cambio.
 */
sealed interface TxResult<out T> {
    /** Confirma todo lo escrito, lo cuenta como un cambio sin respaldar y devuelve [value]. */
    data class Commit<out T>(val value: T) : TxResult<T>

    /** Deshace todo lo escrito y devuelve [value], que suele ser el motivo del rechazo. */
    data class Rollback<out T>(val value: T) : TxResult<T>

    /**
     * Confirma todo lo escrito igual que [Commit], pero no lo cuenta como un cambio sin respaldar,
     * y devuelve [value].
     *
     * Es para lo que no es un hecho del negocio, aunque viaje en la siguiente copia: hoy, guardar o
     * borrar una venta en espera. Cualquier otro uso es una decisión escrita, no una comodidad: un
     * hecho del negocio confirmado sin contar podría no llegar nunca a una copia.
     */
    data class CommitUncounted<out T>(val value: T) : TxResult<T>
}
