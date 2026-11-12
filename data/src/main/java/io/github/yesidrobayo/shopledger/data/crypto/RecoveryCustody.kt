package io.github.yesidrobayo.shopledger.data.crypto

/**
 * El sobre del código y el estado del cifrado, juntos: lo que la app consulta para saber si las
 * copias salen cifradas y con qué clave.
 *
 * Al activar o cambiar el código, el sobre se escribe antes que el estado. Por eso, al arrancar,
 * el sobre manda: si abre y el estado no lo conoce, el estado se alinea con él. La única excepción
 * es el `key_id` que la persona apagó a propósito, que sigue apagado.
 */
internal class RecoveryCustody(
    val keys: RecoveryKeyStore,
    val state: RecoveryStateStore,
    private val nowUtcMillis: () -> Long,
) {
    /**
     * Alinea el estado con el sobre. Si el sobre no está o no abre, no cambia nada: sin el código a
     * la vista no hay nada que alinear, y el respaldo lo informa.
     */
    suspend fun alignAtStartup(): RecoveryState = when (val read = keys.open()) {
        is RecoveryKeyRead.Available -> state.alignWith(read.key.keyIdHex, nowUtcMillis())

        RecoveryKeyRead.Absent,
        is RecoveryKeyRead.Unavailable,
        is RecoveryKeyRead.RetryLater,
        -> state.read()
    }
}
