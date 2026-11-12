package io.github.yesidrobayo.shopledger.data.crypto

import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Apagar el cifrado de las copias, por decisión expresa de la persona. No es una caída a texto plano:
 * esa es la que ocurre sola, y nunca pasa.
 *
 * - **Una sola escritura.** Bajo el cerrojo del respaldo, `state.json` pasa de una vez a apagado, con
 *   el instante y el `key_id` que se apaga. Antes del renombrado sigue activo; después, apagado.
 * - **El sobre no se borra:** las copias cifradas que queden se siguen abriendo sin pedir el código,
 *   y reiniciar no lo vuelve a encender, porque su `key_id` quedó como apagado.
 * - **También sin `recovery/`:** si el destino tiene copias cifradas y este teléfono no tiene el
 *   código, apagar deja la confirmación escrita, y la guarda deja subir en claro.
 * - Después anota `ENCRYPTION_CHANGED`, que si falla no detiene nada, y marca la base para que la
 *   siguiente copia salga ya, sin cifrar. La poda conserva las cifradas hasta comprobar una en claro.
 *
 * @param recordChanged anota el cambio en la auditoría. Nunca lanza.
 * @param forceUpload marca la base para que la siguiente copia salga ya.
 */
internal class EncryptionDeactivation(
    private val custody: RecoveryCustody,
    private val backupMutex: Mutex,
    private val recordChanged: suspend () -> Unit,
    private val forceUpload: suspend () -> Unit,
    private val nowUtcMillis: () -> Long,
    private val faults: (DeactivationStep) -> Unit = {},
) {
    suspend fun deactivate(): DeactivationResult {
        backupMutex.withLock {
            // El sobre manda: primero se alinea el estado con él.
            custody.alignAtStartup()
            val now = nowUtcMillis()
            faults(DeactivationStep.WRITE_STATE)
            try {
                // Leer y escribir bajo el cerrojo del estado: una prueba del código anotada a la vez
                // no se pierde ni vuelve a encender nada.
                custody.state.update { current ->
                    current.copy(
                        enabled = false,
                        offConfirmedAtUtcMillis = now,
                        // Sin un código vigente, se conserva el que ya se había apagado, si hay uno.
                        disabledKeyIdHex = current.keyIdHex ?: current.disabledKeyIdHex,
                    )
                }
            } catch (_: IOException) {
                return DeactivationResult.Failed
            }
        }
        faults(DeactivationStep.RECORD)
        recordChanged()
        faults(DeactivationStep.FORCE_UPLOAD)
        forceUpload()
        return DeactivationResult.Deactivated
    }
}

internal enum class DeactivationResult {
    /** Quedó apagado: la siguiente copia sale sin cifrar. */
    Deactivated,

    /** No se pudo escribir. El cifrado sigue como estaba. */
    Failed,
}

/** Los pasos de apagar el cifrado, donde las pruebas inyectan la muerte. */
internal enum class DeactivationStep { WRITE_STATE, RECORD, FORCE_UPLOAD }
