package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.data.crypto.KeystoreSealer
import io.github.yesidrobayo.shopledger.data.crypto.SealOpenResult
import io.github.yesidrobayo.shopledger.data.crypto.SealedEnvelope
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La credencial WebDAV, sellada en `webdav.sealed` dentro de [directory], que en la app es
 * `noBackupFilesDir`: Android no la respalda, y la copia de la base no la incluye. Es el único
 * lugar donde vive la contraseña fuera de la memoria.
 *
 * - Se sella con [KeystoreSealer] y claves propias de este archivo, distintas de las del código de
 *   recuperación. Hay dos alias, y cada guardado usa el que el archivo actual no usa: el sobre
 *   nuevo se escribe y se renombra con su clave nueva, y recién entonces se borra la clave del
 *   anterior. Un corte en medio deja el archivo de antes con la clave que lo abre.
 * - Si no abre, el resultado es [CredentialRead.PermissionLost]: la persona escribe de nuevo la
 *   contraseña. Nunca lanza por eso, ni devuelve el mensaje de una excepción, solo su clase.
 * - Una credencial de otro destino tampoco se usa: nunca se manda una contraseña a otro sitio.
 * - Leer nunca crea ni reemplaza una clave, ni borra o reescribe el archivo.
 * - Nunca escribe en un registro.
 *
 * Una sola instancia por proceso: el cerrojo vive en ella.
 *
 * @param directory da la carpeta. Se pide en [ioContext], porque pedirla puede tocar el disco.
 * @param ioContext contexto de las lecturas y escrituras, fuera del hilo principal.
 * @param aliases los dos alias de Keystore que se alternan. Las pruebas usan unos propios.
 */
internal class WebDavCredentialStore(
    private val directory: () -> File,
    private val ioContext: CoroutineContext,
    private val aliases: Pair<String, String> = KEY_ALIAS_A to KEY_ALIAS_B,
) {
    init {
        require(aliases.first != aliases.second) { "Los dos alias deben ser distintos" }
    }

    private val mutex = Mutex()

    private fun file() = File(directory(), FILE_NAME)

    private fun sealerOf(alias: String) = KeystoreSealer(alias)

    /**
     * Guarda la credencial, en lugar de la anterior si había una. Si la clave nueva no se puede
     * usar, por ejemplo porque el sistema la invalidó, la reemplaza y sella otra vez: la credencial
     * que se guarda la acaba de escribir la persona.
     */
    suspend fun save(credential: StoredWebDavCredential): CredentialSave {
        WebDavCredentialCodec.problem(credential)?.let { return CredentialSave.Rejected(it) }
        val plaintext = WebDavCredentialCodec.encode(credential)
        return mutex.withLock {
            withContext(ioContext) {
                val file = file()
                val previous = currentAlias(file)
                val next = if (previous == aliases.first) aliases.second else aliases.first
                val sealer = sealerOf(next)
                // Una clave que quedó de un guardado viejo no se reutiliza: cada sobre nace con una
                // clave nueva, así que la anterior ya no abre nada que se guarde después.
                val written =
                    attempt { sealer.replaceKey() }
                        ?: attempt { DurableFile.write(file, sealer.seal(plaintext)) }
                if (written != null) return@withContext CredentialSave.Failed(written)
                if (previous != null) attempt { sealerOf(previous).deleteKey() }
                CredentialSave.Saved
            }
        }
    }

    /**
     * Lee la credencial del destino [expectedDestinationId], el que guarda el estado del respaldo.
     * Una credencial de otro destino cuenta como pérdida de permiso.
     */
    suspend fun read(expectedDestinationId: String): CredentialRead = mutex.withLock {
        withContext(ioContext) { readNow(expectedDestinationId) }
    }

    /**
     * Borra el archivo y sus dos claves, al desconectar el destino o al borrar los datos. Sin clave,
     * una copia del archivo que hubiera quedado en otro lado ya no abre.
     */
    suspend fun delete(): CredentialDelete = mutex.withLock {
        withContext(ioContext) {
            val removed =
                try {
                    DurableFile.delete(file())
                } catch (e: IOException) {
                    return@withContext CredentialDelete.Failed(KeystoreSealer.errorChain(e))
                }
            listOf(aliases.first, aliases.second).forEach { alias ->
                attempt {
                    sealerOf(alias).deleteKey()
                }?.let { return@withContext CredentialDelete.Failed(it) }
            }
            if (removed) CredentialDelete.Deleted else CredentialDelete.Failed(FILE_LEFT)
        }
    }

    private fun currentAlias(file: File): String? {
        if (!file.isFile) return null
        val alias =
            try {
                SealedEnvelope.aliasOf(file.readBytes())
            } catch (_: IOException) {
                null
            }
        return alias?.takeIf { it == aliases.first || it == aliases.second }
    }

    private fun readNow(expectedDestinationId: String): CredentialRead {
        val file = file()
        if (!file.isFile) return CredentialRead.PermissionLost(PermissionLostReason.NO_FILE)
        val bytes =
            try {
                file.readBytes()
            } catch (e: IOException) {
                return CredentialRead.RetryLater(KeystoreSealer.errorChain(e))
            }
        val alias =
            SealedEnvelope.aliasOf(bytes)?.takeIf { it == aliases.first || it == aliases.second }
                ?: return CredentialRead.PermissionLost(PermissionLostReason.ENVELOPE_DAMAGED)
        return when (val opened = sealerOf(alias).open(bytes)) {
            is SealOpenResult.Opened -> {
                val credential = WebDavCredentialCodec.decode(opened.plaintext)
                when {
                    credential == null ->
                        CredentialRead.PermissionLost(PermissionLostReason.MALFORMED_CONTENT)

                    credential.destinationId != expectedDestinationId ->
                        CredentialRead.PermissionLost(PermissionLostReason.OTHER_DESTINATION)

                    else -> CredentialRead.Usable(credential)
                }
            }

            SealOpenResult.KeyMissing -> CredentialRead.PermissionLost(
                PermissionLostReason.KEY_MISSING,
            )

            is SealOpenResult.KeyInvalidated ->
                CredentialRead.PermissionLost(
                    PermissionLostReason.KEY_INVALIDATED,
                    opened.errorChain,
                )

            is SealOpenResult.EnvelopeDamaged ->
                CredentialRead.PermissionLost(
                    PermissionLostReason.ENVELOPE_DAMAGED,
                    opened.errorChain,
                )

            is SealOpenResult.Transient -> CredentialRead.RetryLater(opened.errorChain)
        }
    }

    /** Corre [block]. Devuelve `null` si terminó, o las clases del fallo. */
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
        const val FILE_NAME = "webdav.sealed"
        const val KEY_ALIAS_A = "webdav-credential-a"
        const val KEY_ALIAS_B = "webdav-credential-b"
        private const val FILE_LEFT = "FileLeft"
    }
}

/**
 * La credencial de un destino WebDAV tal como se guarda. Solo vive en memoria durante la
 * operación, y nunca se imprime: su texto es fijo. No se promete borrarla de memoria.
 *
 * @property url la dirección base ya revisada, con `https://` y `/` final.
 * @property destinationId la huella del destino, la misma que guarda el estado del respaldo.
 */
internal class StoredWebDavCredential(
    val url: String,
    val username: String,
    val password: String,
    val destinationId: String,
) {
    override fun toString(): String = "StoredWebDavCredential(oculta)"
}

/** Por qué la credencial no sirve. En todos los casos la persona la escribe de nuevo. */
internal enum class PermissionLostReason {
    NO_FILE,
    KEY_MISSING,
    KEY_INVALIDATED,
    ENVELOPE_DAMAGED,
    MALFORMED_CONTENT,
    OTHER_DESTINATION,
}

internal sealed interface CredentialRead {
    class Usable(val credential: StoredWebDavCredential) : CredentialRead {
        override fun toString(): String = "Usable"
    }

    /**
     * La persona escribe de nuevo la contraseña. Ni la base ni las copias cambian.
     *
     * @property errorChain clases de la cadena de causas, sin mensajes, si hubo una excepción.
     */
    data class PermissionLost(val reason: PermissionLostReason, val errorChain: String? = null) :
        CredentialRead

    /** Fallo pasajero: se reintenta sin cambiar el estado. */
    data class RetryLater(val errorChain: String) : CredentialRead
}

internal sealed interface CredentialSave {
    data object Saved : CredentialSave

    /** No se guardó porque la credencial no es válida. El archivo anterior sigue igual. */
    data class Rejected(val problem: CredentialProblem) : CredentialSave

    /** No se guardó por un fallo del disco o de Keystore. Solo clases, sin mensajes. */
    data class Failed(val errorChain: String) : CredentialSave
}

internal sealed interface CredentialDelete {
    data object Deleted : CredentialDelete

    data class Failed(val errorChain: String) : CredentialDelete
}

internal enum class CredentialProblem {
    /** Solo `https://`: una contraseña nunca viaja sin cifrar. */
    NOT_HTTPS,
    BLANK_FIELD,
    TOO_LONG,
}

/**
 * El contenido sellado, en Kotlin puro: un byte de versión y cuatro textos en UTF-8, cada uno con su
 * longitud en cuatro bytes. UTF-8 normal y no el modificado de Java: la contraseña viaja en UTF-8 en
 * la cabecera de autenticación, y admite letras de cualquier alfabeto.
 */
internal object WebDavCredentialCodec {
    private const val VERSION: Byte = 1
    private const val FIELD_COUNT = 4
    private const val BYTE_MASK = 0xFF

    /** Tope de cada texto, en bytes. Una dirección o una contraseña real queda muy por debajo. */
    const val MAX_FIELD_BYTES = 4096

    fun problem(credential: StoredWebDavCredential): CredentialProblem? {
        val fields = fields(credential)
        return when {
            fields.any { it.isEmpty() } -> CredentialProblem.BLANK_FIELD

            fields.any { it.toByteArray(Charsets.UTF_8).size > MAX_FIELD_BYTES } ->
                CredentialProblem.TOO_LONG

            !credential.url.startsWith("https://", ignoreCase = true) -> CredentialProblem.NOT_HTTPS

            else -> null
        }
    }

    fun encode(credential: StoredWebDavCredential): ByteArray {
        require(problem(credential) == null) { "Credencial no válida" }
        val parts = fields(credential).map { it.toByteArray(Charsets.UTF_8) }
        val out = ByteArray(1 + parts.sumOf { Int.SIZE_BYTES + it.size })
        out[0] = VERSION
        var offset = 1
        for (part in parts) {
            writeInt(out, offset, part.size)
            offset += Int.SIZE_BYTES
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    /** Devuelve `null` si el contenido no es de esta versión, está incompleto o le sobra algo. */
    fun decode(plaintext: ByteArray): StoredWebDavCredential? {
        if (plaintext.isEmpty() || plaintext[0] != VERSION) return null
        val fields = ArrayList<String>(FIELD_COUNT)
        var offset = 1
        repeat(FIELD_COUNT) {
            if (plaintext.size - offset < Int.SIZE_BYTES) return null
            val length = readInt(plaintext, offset)
            offset += Int.SIZE_BYTES
            if (length !in 1..MAX_FIELD_BYTES || plaintext.size - offset < length) return null
            fields += String(plaintext, offset, length, Charsets.UTF_8)
            offset += length
        }
        if (offset != plaintext.size) return null
        val credential = StoredWebDavCredential(fields[0], fields[1], fields[2], fields[3])
        return if (problem(credential) == null) credential else null
    }

    private fun fields(credential: StoredWebDavCredential) =
        listOf(credential.url, credential.username, credential.password, credential.destinationId)

    private fun writeInt(out: ByteArray, offset: Int, value: Int) {
        for (i in 0 until Int.SIZE_BYTES) {
            out[offset + i] = (value ushr (Byte.SIZE_BITS * (Int.SIZE_BYTES - 1 - i))).toByte()
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until Int.SIZE_BYTES) {
            value = (value shl Byte.SIZE_BITS) or (bytes[offset + i].toInt() and BYTE_MASK)
        }
        return value
    }
}
