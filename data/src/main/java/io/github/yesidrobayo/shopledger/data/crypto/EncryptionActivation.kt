package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Los pasos de datos de la activación del cifrado, en el orden de la compuerta: generar el código,
 * probarlo con una ida y vuelta, sellarlo y recién entonces encender el cifrado.
 *
 * - **El código vive solo en memoria** hasta [activate]: no se escribe en ningún archivo, estado
 *   guardado de la pantalla, `Intent` ni trabajo en segundo plano. Si el proceso muere antes de
 *   sellar, el código se pierde y el cifrado sigue apagado: no cambió nada.
 * - **Ninguna copia cifrada puede salir antes del último paso:** el estado se enciende después de
 *   sellar, bajo el cerrojo del respaldo, y ahí mismo se descarta la subida de una copia en claro
 *   que quedó pendiente, para que la siguiente salga cifrada.
 * - Un fallo en cualquier paso deja el cifrado como estaba.
 *
 * @param custody el sobre y el estado del cifrado.
 * @param backupMutex el cerrojo del respaldo: una copia en curso termina antes de encender.
 * @param discardPendingPlainUpload descarta la subida pendiente de una copia en claro: borra su
 *   archivo y limpia `pending_*`. Corre bajo [backupMutex].
 * @param forceUpload marca la base para que la siguiente copia salga ya, cifrada.
 * @param random el generador del sistema, para el código.
 */
internal class EncryptionActivation(
    private val custody: RecoveryCustody,
    private val backupMutex: Mutex,
    private val discardPendingPlainUpload: suspend () -> Unit,
    private val forceUpload: suspend () -> Unit,
    private val nowUtcMillis: () -> Long,
    private val ioContext: CoroutineContext,
    private val random: SecureRandom = SecureRandom(),
    private val faults: (ActivationStep) -> Unit = {},
) {
    /** Un código nuevo, que solo vive en la memoria de quien lo pide. */
    fun newCode(): RecoveryCode = RecoveryCodec.generate(random)

    /**
     * La prueba de ida y vuelta de la compuerta: cifra una muestra inventada con la clave del
     * código, la descifra con la misma clave derivada otra vez y compara largo y suma. No toca el
     * disco ni la base.
     */
    suspend fun roundTrip(code: RecoveryCode): Boolean = withContext(ioContext) {
        val sample = ByteArray(SAMPLE_BYTES).also { random.nextBytes(it) }
        val expected = newSha256().digest(sample).toLowerHex()
        val writeKey = BackupKey.from(code)
        val header =
            BackupHeader(writeKey.keyId, 0, 0, nowUtcMillis(), UUID(0, 0), sample.size.toLong())
        try {
            val encrypted = ByteArrayOutputStream()
            EncryptedCopy.write(ByteArrayInputStream(sample), encrypted, header, writeKey)
            val read =
                EncryptedCopy.read(
                    ByteArrayInputStream(encrypted.toByteArray()),
                    BackupKey.from(code),
                )
            read is EncryptedReadResult.Read &&
                read.content == RawContent(sample.size.toLong(), expected)
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Sella [code] y enciende el cifrado. Antes de sellar no cambia nada; después, el sobre manda
     * aunque el estado no alcance a escribirse, porque al arrancar el estado se alinea con él.
     */
    suspend fun activate(code: RecoveryCode): ActivationResult {
        faults(ActivationStep.SEAL)
        val keyIdHex =
            when (val saved = custody.keys.seal(code)) {
                is RecoveryKeySave.Sealed -> saved.keyIdHex
                is RecoveryKeySave.Failed -> return ActivationResult.SealFailed
            }
        backupMutex.withLock {
            faults(ActivationStep.DISCARD_PENDING)
            discardPendingPlainUpload()
            faults(ActivationStep.WRITE_STATE)
            val current = custody.state.read()
            val enabled =
                current.copy(
                    enabled = true,
                    keyIdHex = keyIdHex,
                    enabledAtUtcMillis = nowUtcMillis(),
                    offConfirmedAtUtcMillis = null,
                )
            try {
                custody.state.write(enabled)
            } catch (_: IOException) {
                // El sobre ya está sellado: al arrancar, el estado se alinea con él.
                return ActivationResult.Activated(keyIdHex, stateWritten = false)
            }
        }
        faults(ActivationStep.FORCE_UPLOAD)
        forceUpload()
        return ActivationResult.Activated(keyIdHex, stateWritten = true)
    }

    private companion object {
        /** La muestra de la ida y vuelta: algo más que un segmento, para cruzar uno. */
        const val SAMPLE_BYTES = (1 shl 20) + 4096
    }
}

internal sealed interface ActivationResult {
    /** Quedó sellado y activo. Si [stateWritten] es falso, el estado se alinea al arrancar. */
    data class Activated(val keyIdHex: String, val stateWritten: Boolean) : ActivationResult

    /** No se pudo sellar. El cifrado sigue como estaba. */
    data object SealFailed : ActivationResult
}

/** Los pasos de escritura de la activación, donde las pruebas inyectan la muerte. */
internal enum class ActivationStep { SEAL, DISCARD_PENDING, WRITE_STATE, FORCE_UPLOAD }
