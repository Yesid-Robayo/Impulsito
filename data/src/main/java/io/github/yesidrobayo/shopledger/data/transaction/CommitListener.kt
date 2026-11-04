package io.github.yesidrobayo.shopledger.data.transaction

/**
 * Lo que corre después de confirmar una escritura, fuera de la transacción: programar la copia y
 * lanzar los avisos pendientes.
 *
 * Nada lento va dentro de la transacción, porque retiene la escritura de toda la app. Esto corre
 * solo si la confirmación terminó bien, nunca tras un rechazo o una falla. Si falla, o si el proceso
 * muere antes, el dato ya está guardado y contado: el arranque siguiente ve el cambio sin copia y lo
 * programa, y lanza los avisos que quedaron pendientes.
 */
internal fun interface CommitListener {
    /** Corto y sin esperar: lo que deba sobrevivir a la app va a un trabajo en segundo plano. */
    fun onCommitted()
}
