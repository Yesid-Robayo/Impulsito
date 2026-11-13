package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.BackupHeader
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedCopy
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedReadResult
import io.github.yesidrobayo.shopledger.data.crypto.HeaderReadResult
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Prepara el archivo de una copia para revisarlo como base: comprueba su suma, reconoce el formato
 * por sus primeros bytes y lo descomprime con tope.
 *
 * - **La suma, antes que nada.** Si se conoce la suma esperada, se compara con la del archivo antes
 *   de descomprimir un solo byte. Sin suma esperada, como con un archivo elegido por la persona,
 *   se sigue: el control de gzip detecta el daño accidental, y la suma calculada sirve después para
 *   saber si la copia ya está en el destino.
 * - **El formato sale de los bytes, nunca del nombre.** Un nombre que venga de afuera no decide nada:
 *   ni el formato ni dónde se escribe. El nombre que gzip guarda adentro se ignora.
 * - **Con tope.** La descompresión es en flujo y cuenta lo que escribe: se corta al pasar
 *   [maxDecompressedBytes], aunque el pie del gzip diga otra cosa. El disco nunca guarda más que el
 *   tope y un búfer. Un archivo con varios miembros gzip cuenta todos.
 * - **Una copia cifrada**, el `.slbk`, sigue el orden de la restauración: cabecera contra la lista
 *   cerrada antes de usar ninguna clave, `key_id` de la cabecera autenticada, descifrado autenticado
 *   en flujo y descompresión de un solo miembro gzip con el tope. Sin una clave de ese `key_id` no
 *   descifra nada, y dice cuál pide.
 * - **Nada a medias.** Si algo falla o se cancela, la salida se borra antes de devolver.
 *
 * No registra nada: quien la llama decide qué hacer con el resultado.
 *
 * @param ioContext contexto del trabajo, fuera del hilo principal.
 * @param maxDecompressedBytes la mayor base que se acepta. Solo las pruebas lo bajan.
 */
internal class CandidateUnpacker(
    private val ioContext: CoroutineContext,
    private val maxDecompressedBytes: Long = BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES,
) {
    /**
     * Revisa [source] y deja la base descomprimida en [target], un nombre fijo en una carpeta privada
     * de la app. Si [target] ya existe, es un resto de una ejecución cortada y se borra antes.
     *
     * @param expectedSha256 la suma esperada, en hexadecimal en minúscula, o `null` si no la hay.
     * @param keys las claves con que se puede abrir una copia cifrada: la de este teléfono y la del
     *   código escrito, si las hay. Solo se usa la del `key_id` de la cabecera.
     */
    suspend fun unpack(
        source: File,
        target: File,
        expectedSha256: String?,
        keys: List<BackupKey> = emptyList(),
    ): UnpackResult {
        require(target.isAbsolute) { "La ruta del candidato debe ser absoluta" }
        var unpacked = false
        try {
            val result = withContext(ioContext) {
                val checkActive = { coroutineContext.ensureActive() }
                try {
                    unpackChecked(source, target, expectedSha256, keys, checkActive)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: IOException) {
                    UnpackResult.Failed(failure)
                }
            }
            unpacked = result is UnpackResult.Unpacked
            return result
        } finally {
            if (!unpacked) withContext(NonCancellable + ioContext) { target.delete() }
        }
    }

    private fun unpackChecked(
        source: File,
        target: File,
        expectedSha256: String?,
        keys: List<BackupKey>,
        checkActive: () -> Unit,
    ): UnpackResult {
        target.delete()
        val sha256 = sha256Of(source, checkActive)
        if (expectedSha256 != null && expectedSha256 != sha256) {
            return UnpackResult.Rejected(CandidateRejection.CHECKSUM_MISMATCH)
        }
        val format = FileInputStream(source).use(::formatOf)
            ?: return UnpackResult.Rejected(CandidateRejection.UNKNOWN_FORMAT)
        return when (format) {
            CopyFormat.ENCRYPTED -> decrypt(source, target, sha256, keys, checkActive)
            CopyFormat.GZIP -> decompress(source, target, sha256, checkActive)
        }
    }

    /**
     * Descifra y descomprime una copia cifrada en [target]. La cabecera se revisa primero, sin
     * clave: un valor fuera de la lista, un formato más nuevo o un tamaño que no se acepta se
     * rechazan sin tocar ninguna.
     */
    private fun decrypt(
        source: File,
        target: File,
        sha256: String,
        keys: List<BackupKey>,
        checkActive: () -> Unit,
    ): UnpackResult {
        val header =
            when (val read = FileInputStream(source).use { BackupHeader.read(it) }) {
                is HeaderReadResult.Valid -> read.header

                is HeaderReadResult.NewerFormat ->
                    return UnpackResult.Rejected(CandidateRejection.FORMAT_TOO_NEW)

                is HeaderReadResult.Rejected, HeaderReadResult.Truncated ->
                    return UnpackResult.Rejected(CandidateRejection.DAMAGED_ARCHIVE)

                // Los primeros bytes ya dijeron que es cifrada: esto no debería pasar.
                HeaderReadResult.PlainFormat, HeaderReadResult.NotEncryptedCopy ->
                    return UnpackResult.Rejected(CandidateRejection.UNKNOWN_FORMAT)
            }
        if (header.rawSize > maxDecompressedBytes) {
            return UnpackResult.Rejected(CandidateRejection.DECOMPRESSED_TOO_LARGE)
        }
        val key = keys.firstOrNull { it.matches(header.keyId) }
            ?: return UnpackResult.NeedsKey(header.keyId.toLowerHex())
        val directory = requireNotNull(target.parentFile) { "El candidato necesita una carpeta" }
        directory.mkdirs()
        val read =
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    EncryptedCopy.read(input, key, output, maxDecompressedBytes, checkActive)
                        .also { if (it is EncryptedReadResult.Read) output.fd.sync() }
                }
            }
        return when (read) {
            is EncryptedReadResult.Read ->
                UnpackResult.Unpacked(
                    file = target,
                    sourceSha256 = sha256,
                    rawSizeBytes = read.content.size,
                    keyIdHex = key.keyIdHex,
                )

            is EncryptedReadResult.NewerFormat ->
                UnpackResult.Rejected(CandidateRejection.FORMAT_TOO_NEW)

            EncryptedReadResult.TooLarge ->
                UnpackResult.Rejected(CandidateRejection.DECOMPRESSED_TOO_LARGE)

            // La cabecera ya pasó con esta clave: lo que queda es daño en el archivo.
            is EncryptedReadResult.OtherKey,
            is EncryptedReadResult.HeaderRejected,
            EncryptedReadResult.Damaged,
            EncryptedReadResult.SizeMismatch,
            -> UnpackResult.Rejected(CandidateRejection.DAMAGED_ARCHIVE)

            EncryptedReadResult.PlainFormat, EncryptedReadResult.NotEncryptedCopy ->
                UnpackResult.Rejected(CandidateRejection.UNKNOWN_FORMAT)
        }
    }

    private fun decompress(
        source: File,
        target: File,
        sha256: String,
        checkActive: () -> Unit,
    ): UnpackResult {
        val directory = requireNotNull(target.parentFile) { "El candidato necesita una carpeta" }
        directory.mkdirs()
        var written = 0L
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    GZIPInputStream(input, BUFFER_BYTES).use { gzip ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            checkActive()
                            val read = gzip.read(buffer)
                            if (read < 0) break
                            if (written + read > maxDecompressedBytes) {
                                return UnpackResult.Rejected(
                                    CandidateRejection.DECOMPRESSED_TOO_LARGE,
                                )
                            }
                            output.write(buffer, 0, read)
                            written += read
                        }
                    }
                    output.fd.sync()
                }
            }
        } catch (_: ZipException) {
            // Control CRC, largo del pie o encabezado de gzip que no corresponden.
            return UnpackResult.Rejected(CandidateRejection.DAMAGED_ARCHIVE)
        } catch (_: EOFException) {
            // El archivo se acaba antes que el gzip: está cortado.
            return UnpackResult.Rejected(CandidateRejection.DAMAGED_ARCHIVE)
        }
        return UnpackResult.Unpacked(file = target, sourceSha256 = sha256, rawSizeBytes = written)
    }

    private fun sha256Of(source: File, checkActive: () -> Unit): String {
        val digest = newSha256()
        FileInputStream(source).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toLowerHex()
    }

    /** Los dos formatos de copia, por sus primeros bytes. */
    private enum class CopyFormat { GZIP, ENCRYPTED }

    private fun formatOf(input: InputStream): CopyFormat? {
        val start = ByteArray(ENCRYPTED_MAGIC.size)
        var read = 0
        while (read < start.size) {
            val count = input.read(start, read, start.size - read)
            if (count < 0) break
            read += count
        }
        return when {
            read >= GZIP_MAGIC.size && start.copyOf(GZIP_MAGIC.size).contentEquals(GZIP_MAGIC) ->
                CopyFormat.GZIP

            read == ENCRYPTED_MAGIC.size && start.contentEquals(ENCRYPTED_MAGIC) ->
                CopyFormat.ENCRYPTED

            else -> null
        }
    }

    private companion object {
        /** 64 KiB: el búfer de lectura y de escritura. */
        const val BUFFER_BYTES = 64 * 1024

        /** Los dos primeros bytes de todo miembro gzip. */
        val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())

        /** `SLBK`, el comienzo de una copia cifrada. */
        val ENCRYPTED_MAGIC = byteArrayOf(0x53, 0x4C, 0x42, 0x4B)
    }
}

/** Lo que dio preparar el archivo de una copia. */
internal sealed interface UnpackResult {
    /**
     * La base quedó descomprimida en [file], con [rawSizeBytes] bytes. [sourceSha256] es la suma del
     * archivo de origen, que decide después si la copia ya está en el destino. [keyIdHex] es la clave
     * con que se descifró, o `null` si era una copia en claro.
     */
    data class Unpacked(
        val file: File,
        val sourceSha256: String,
        val rawSizeBytes: Long,
        val keyIdHex: String? = null,
    ) : UnpackResult

    /**
     * Es una copia cifrada y ninguna de las claves dadas es la de su `key_id`, [keyIdHex]. No se
     * descifró ni se escribió nada.
     */
    data class NeedsKey(val keyIdHex: String) : UnpackResult

    /** El archivo no sirve como copia. No quedó nada escrito. */
    data class Rejected(val reason: CandidateRejection) : UnpackResult

    /**
     * Una falla del disco que no dice nada del archivo, y se puede reintentar. Solo la clase de
     * [cause] puede ir al registro: su mensaje puede llevar rutas.
     */
    class Failed(val cause: IOException) : UnpackResult
}
