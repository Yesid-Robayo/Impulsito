package io.github.yesidrobayo.shopledger.data.crypto

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La custodia del código de recuperación en este teléfono: `rk.sealed` dentro de [directory], que en
 * la app es `noBackupFilesDir/recovery`. Android no la respalda, y ninguna copia la incluye.
 *
 * Mientras el teléfono viva, el código no hace falta: la app copia y restaura con este sobre sin
 * pedirlo. Por eso el sobre se sella con [KeystoreSealer], con claves sin autenticación ni
 * StrongBox y sin exigir el teléfono desbloqueado: así el respaldo lo abre con la pantalla
 * bloqueada. Keystore impide sacar la clave, no usarla.
 *
 * - Lo sellado es el código y su `key_id`. Al abrir, el `key_id` se vuelve a derivar del código:
 *   si no coincide, el sobre cuenta como dañado.
 * - Hay dos alias, y cada sello usa el que el sobre actual no usa: el nuevo se escribe con `.tmp`,
 *   se sincroniza y se renombra con su clave nueva, y recién entonces se borra la clave anterior.
 *   Un corte en medio deja el sobre de antes con la clave que lo abre, o el nuevo.
 * - `rk.pending` guarda el código de una copia cifrada que se está restaurando. Se promueve a
 *   `rk.sealed` solo cuando el reemplazo quedó verificado; una reversión o una cancelación lo borran.
 * - Abrir nunca crea ni reemplaza una clave, ni borra o reescribe un sobre: un fallo de Keystore no
 *   toca nada, y la persona decide la salida.
 * - El código nunca sale de aquí en texto: no se registra, ni va a la base, a una copia, al destino,
 *   a `state.json` ni a un trabajo en segundo plano. Este archivo no escribe en ningún registro.
 *
 * Una sola instancia por proceso: el cerrojo vive en ella.
 *
 * @param directory da la carpeta `recovery`. Se pide en [ioContext], porque pedirla toca el disco.
 * @param ioContext contexto de lecturas y escrituras, fuera del hilo principal.
 * @param aliases los dos alias de Keystore que se alternan, distintos de los de la credencial WebDAV.
 *   Las pruebas usan unos propios.
 */
internal class RecoveryKeyStore(
    private val directory: () -> File,
    private val ioContext: CoroutineContext,
    private val aliases: Pair<String, String> = KEY_ALIAS_A to KEY_ALIAS_B,
    private val faults: RecoveryKeyFaults = RecoveryKeyFaults.None,
) {
    init {
        require(aliases.first != aliases.second) { "Los dos alias deben ser distintos" }
    }

    private val mutex = Mutex()

    private fun sealedFile() = File(directory(), SEALED_FILE)

    private fun pendingFile() = File(directory(), PENDING_FILE)

    /**
     * Sella [code] en `rk.sealed`, en lugar del sobre anterior si había uno. Un fallo deja el
     * sobre anterior intacto y abriendo con su clave.
     *
     * Con un `rk.pending` de una restauración en curso no sella nada: solo hay dos alias, y el sobre
     * nuevo le cambiaría la clave al pendiente. El arranque lo promueve o lo borra, y después se
     * puede volver a intentar.
     */
    suspend fun seal(code: RecoveryCode): RecoveryKeySave = mutex.withLock {
        withContext(ioContext) {
            val pending = pendingFile()
            if (pending.exists() || DurableFile.temporaryOf(pending).exists()) {
                RecoveryKeySave.Failed(RESTORE_PENDING)
            } else {
                sealInto(sealedFile(), code, deletePrevious = true)
            }
        }
    }

    /** Abre `rk.sealed`. Nunca crea, borra ni reescribe nada. */
    suspend fun open(): RecoveryKeyRead = mutex.withLock {
        withContext(ioContext) { openFile(sealedFile()) }
    }

    /**
     * Sella el código de una copia que se está restaurando en `rk.pending`, al lado de `rk.sealed`,
     * que sigue igual hasta [promotePending]. Usa la clave que `rk.sealed` no usa.
     */
    suspend fun sealPending(code: RecoveryCode): RecoveryKeySave = mutex.withLock {
        withContext(ioContext) { sealInto(pendingFile(), code, deletePrevious = false) }
    }

    /** Abre `rk.pending`, si hay uno. Nunca crea, borra ni reescribe nada. */
    suspend fun openPending(): RecoveryKeyRead = mutex.withLock {
        withContext(ioContext) { openFile(pendingFile()) }
    }

    /**
     * Promueve `rk.pending` a `rk.sealed` por renombrado, tras un reemplazo verificado, y borra la
     * clave del sobre anterior. Devuelve `false` si no había un `rk.pending` que abra: entonces no
     * cambia nada.
     */
    suspend fun promotePending(): Boolean = mutex.withLock {
        withContext(ioContext) {
            val pending = pendingFile()
            if (openFile(pending) !is RecoveryKeyRead.Available) return@withContext false
            val pendingAlias = aliasOf(pending) ?: return@withContext false
            val previous = aliasOf(sealedFile())
            try {
                faults.before(RecoveryKeyStep.PROMOTE_RENAME)
                DurableFile.rename(pending, sealedFile())
            } catch (_: IOException) {
                return@withContext false
            }
            if (previous != null && previous != pendingAlias) {
                attempt {
                    faults.before(RecoveryKeyStep.DELETE_PREVIOUS_KEY)
                    KeystoreSealer(previous).deleteKey()
                }
            }
            true
        }
    }

    /**
     * Borra `rk.pending` y su clave si no es la de `rk.sealed`, al revertir o cancelar una
     * restauración. `rk.sealed` queda como estaba. Devuelve `false` si quedó algo.
     */
    suspend fun deletePending(): Boolean = mutex.withLock {
        withContext(ioContext) {
            val pending = pendingFile()
            val pendingAlias = aliasOf(pending)
            val removed =
                try {
                    DurableFile.delete(pending)
                } catch (_: IOException) {
                    false
                }
            if (removed && pendingAlias != null && pendingAlias != aliasOf(sealedFile())) {
                attempt { KeystoreSealer(pendingAlias).deleteKey() }
            }
            removed
        }
    }

    /** Si hay `rk.sealed` o su temporal. No lo abre ni toca Keystore. */
    suspend fun exists(): Boolean = withContext(ioContext) {
        sealedFile().exists() || DurableFile.temporaryOf(sealedFile()).exists()
    }

    /** Si hay `rk.pending` o su temporal. No lo abre ni toca Keystore. */
    suspend fun pendingExists(): Boolean = withContext(ioContext) {
        pendingFile().exists() || DurableFile.temporaryOf(pendingFile()).exists()
    }

    private fun sealInto(
        target: File,
        code: RecoveryCode,
        deletePrevious: Boolean,
    ): RecoveryKeySave {
        val plaintext = RecoveryEnvelopeCodec.encode(code)
        val current = aliasOf(sealedFile())
        val next = if (current == aliases.first) aliases.second else aliases.first
        val sealer = KeystoreSealer(next)
        // Cada sobre nace con una clave nueva: lo sellado antes con este alias ya no abre.
        val failure =
            attempt {
                faults.before(RecoveryKeyStep.REPLACE_KEY)
                sealer.replaceKey()
            } ?: attempt {
                faults.before(RecoveryKeyStep.WRITE_ENVELOPE)
                DurableFile.write(target, sealer.seal(plaintext))
            }
        if (failure != null) return RecoveryKeySave.Failed(failure)
        if (deletePrevious && current != null && current != next) {
            // Si esto falla, la clave vieja queda sin uso: no abre nada que importe.
            attempt {
                faults.before(RecoveryKeyStep.DELETE_PREVIOUS_KEY)
                KeystoreSealer(current).deleteKey()
            }
        }
        return RecoveryKeySave.Sealed(BackupKey.from(code).keyIdHex)
    }

    private fun aliasOf(file: File): String? {
        if (!file.isFile) return null
        val alias =
            try {
                SealedEnvelope.aliasOf(file.readBytes())
            } catch (_: IOException) {
                null
            }
        return alias?.takeIf { it == aliases.first || it == aliases.second }
    }

    private fun openFile(file: File): RecoveryKeyRead {
        if (!file.isFile) return RecoveryKeyRead.Absent
        val bytes =
            try {
                file.readBytes()
            } catch (e: IOException) {
                return RecoveryKeyRead.RetryLater(KeystoreSealer.errorChain(e))
            }
        val alias =
            SealedEnvelope.aliasOf(bytes)?.takeIf { it == aliases.first || it == aliases.second }
                ?: return RecoveryKeyRead.Unavailable(RecoveryKeyProblem.ENVELOPE_DAMAGED)
        return when (val opened = KeystoreSealer(alias).open(bytes)) {
            is SealOpenResult.Opened ->
                RecoveryEnvelopeCodec.decode(opened.plaintext)?.let {
                    RecoveryKeyRead.Available(it)
                }
                    ?: RecoveryKeyRead.Unavailable(RecoveryKeyProblem.MALFORMED_CONTENT)

            SealOpenResult.KeyMissing -> RecoveryKeyRead.Unavailable(RecoveryKeyProblem.KEY_MISSING)

            is SealOpenResult.KeyInvalidated ->
                RecoveryKeyRead.Unavailable(RecoveryKeyProblem.KEY_INVALIDATED, opened.errorChain)

            is SealOpenResult.EnvelopeDamaged ->
                RecoveryKeyRead.Unavailable(RecoveryKeyProblem.ENVELOPE_DAMAGED, opened.errorChain)

            is SealOpenResult.Transient -> RecoveryKeyRead.RetryLater(opened.errorChain)
        }
    }

    /** Corre [block]. Devuelve `null` si terminó, o las clases del fallo, sin mensajes. */
    private inline fun attempt(block: () -> Unit): String? = try {
        block()
        null
    } catch (e: GeneralSecurityException) {
        KeystoreSealer.errorChain(e)
    } catch (e: ProviderException) {
        KeystoreSealer.errorChain(e)
    } catch (e: IOException) {
        KeystoreSealer.errorChain(e)
    }

    companion object {
        const val DIRECTORY = "recovery"
        const val SEALED_FILE = "rk.sealed"
        const val PENDING_FILE = "rk.pending"
        const val KEY_ALIAS_A = "recovery-code-a"
        const val KEY_ALIAS_B = "recovery-code-b"

        /** La causa de un sello rechazado porque una restauración espera con su código. */
        const val RESTORE_PENDING = "RestorePending"
    }
}

/**
 * Lo sellado en el sobre, en Kotlin puro: un byte de versión, el `key_id` de 8 bytes y los 26
 * caracteres del código en ASCII. Un contenido de otra versión, incompleto, con sobras o con un
 * `key_id` que no sale del código, no es un código.
 */
internal object RecoveryEnvelopeCodec {
    private const val VERSION: Byte = 1
    private val LENGTH = 1 + RecoveryCodec.KEY_ID_BYTES + RecoveryCodec.CODE_LENGTH

    fun encode(code: RecoveryCode): ByteArray =
        byteArrayOf(VERSION) + RecoveryCodec.deriveKeyId(code) + code.ascii()

    fun decode(plaintext: ByteArray): RecoveryCode? {
        if (plaintext.size != LENGTH || plaintext[0] != VERSION) return null
        val keyId = plaintext.copyOfRange(1, 1 + RecoveryCodec.KEY_ID_BYTES)
        val text =
            String(
                plaintext,
                1 + RecoveryCodec.KEY_ID_BYTES,
                RecoveryCodec.CODE_LENGTH,
                Charsets.US_ASCII,
            )
        if (text.length != RecoveryCodec.CODE_LENGTH ||
            text.any { it !in RecoveryCodec.ALPHABET }
        ) {
            return null
        }
        val code = RecoveryCode(text)
        return code.takeIf { MessageDigest.isEqual(RecoveryCodec.deriveKeyId(it), keyId) }
    }
}

/** Lo que dio abrir un sobre. Ningún caso lleva el código en su texto ni el mensaje de un fallo. */
internal sealed interface RecoveryKeyRead {
    class Available(val code: RecoveryCode) : RecoveryKeyRead {
        val key: BackupKey by lazy { BackupKey.from(code) }

        override fun toString(): String = "Available"
    }

    /** No hay sobre: el cifrado nunca se activó en esta instalación, o se borraron sus datos. */
    data object Absent : RecoveryKeyRead

    /**
     * El sobre existe y no abre. Con el cifrado activo es `ENCRYPTION_KEY_UNAVAILABLE`: no sube nada,
     * nunca en claro, y la persona escribe el código o crea otro. Nada se borra.
     *
     * @property errorChain clases de la cadena de causas, sin mensajes, si hubo una excepción.
     */
    data class Unavailable(val problem: RecoveryKeyProblem, val errorChain: String? = null) :
        RecoveryKeyRead

    /** Fallo pasajero de Keystore o del disco: se reintenta sin cambiar el estado. */
    data class RetryLater(val errorChain: String) : RecoveryKeyRead
}

/** Por qué el sobre no abre. */
internal enum class RecoveryKeyProblem {
    KEY_MISSING,
    KEY_INVALIDATED,
    ENVELOPE_DAMAGED,
    MALFORMED_CONTENT,
}

internal sealed interface RecoveryKeySave {
    /** Quedó sellado, durable, con el `key_id` en hexadecimal. */
    data class Sealed(val keyIdHex: String) : RecoveryKeySave

    /** No se selló. El sobre anterior sigue igual. Solo clases, sin mensajes. */
    data class Failed(val errorChain: String) : RecoveryKeySave
}

/** Los pasos de escritura de la custodia, donde las pruebas inyectan un fallo o la muerte. */
internal enum class RecoveryKeyStep {
    REPLACE_KEY,
    WRITE_ENVELOPE,
    DELETE_PREVIOUS_KEY,
    PROMOTE_RENAME,
}

/** Inyecta fallos en las pruebas. En la app no hace nada. */
internal fun interface RecoveryKeyFaults {
    fun before(step: RecoveryKeyStep)

    companion object {
        val None = RecoveryKeyFaults {}
    }
}
