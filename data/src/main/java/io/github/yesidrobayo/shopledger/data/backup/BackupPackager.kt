package io.github.yesidrobayo.shopledger.data.backup

import android.system.ErrnoException
import android.system.OsConstants
import io.github.yesidrobayo.shopledger.data.crypto.BackupHeader
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedCopy
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedReadResult
import io.github.yesidrobayo.shopledger.data.crypto.RawContent
import io.github.yesidrobayo.shopledger.data.crypto.RawSizeMismatch
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCode
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Convierte la instantánea sellada en el archivo que se sube: la base comprimida con gzip, con la
 * suma SHA-256 de sus bytes tal como quedan en el destino. Con el cifrado activo, el archivo es la
 * copia cifrada: la cabecera y la base con gzip cifrada en flujo con la clave del código.
 *
 * - **Nunca en claro con el cifrado activo.** Si la clave no abre, no arma nada: no hay caída a
 *   texto plano. Un fallo pasajero de Keystore pide reintentar sin cambiar nada.
 *
 * - **En flujo.** Lee la instantánea, la comprime, cuenta y suma en una sola pasada, con un búfer
 *   fijo: nunca carga la base en memoria, que puede pesar cientos de megabytes.
 * - **Con tope.** Si el archivo pasa de [maxFileBytes] se detiene: una copia más grande que lo que
 *   la restauración acepta bajar no serviría para restaurar.
 * - **Comprobado antes de subir.** Las sumas solas no prueban que la copia se pueda abrir: un gzip
 *   sin su cierre da sumas estables y falla al descomprimir. Por eso, ya escrito, vuelve a leer el
 *   archivo en flujo, lo descomprime y compara largo y suma con la instantánea, y además relee la
 *   suma del archivo. Una copia cifrada se descifra para eso con la clave derivada otra vez del
 *   código, como la abriría una restauración. Solo entonces borra la instantánea.
 * - **Nada a medias.** Si algo falla o se cancela, el archivo comprimido se borra antes de devolver.
 *   La instantánea queda: la limpieza de la carpeta de preparación la borra.
 *
 * No registra nada: quien la llama decide qué hacer con el resultado.
 *
 * @param ioContext contexto de la compresión, fuera del hilo principal.
 * @param maxFileBytes el mayor archivo comprimido que se acepta.
 * @param compressor abre el compresor sobre el archivo. Siempre el gzip con su nivel por defecto;
 *   las pruebas lo cambian para fabricar un archivo dañado.
 * @param encryption si las copias salen cifradas y con qué código. Se lee en cada copia.
 */
internal class BackupPackager(
    private val ioContext: CoroutineContext,
    private val maxFileBytes: Long = BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES,
    private val encryption: suspend () -> PackEncryption = { PackEncryption.Plain },
    private val compressor: (OutputStream) -> GZIPOutputStream = {
        GZIPOutputStream(it, BUFFER_BYTES)
    },
) {
    /**
     * Comprime [snapshot] en [target] y lo comprueba. Si queda bien, borra [snapshot]. Solo arma
     * copias en claro: con el cifrado activo no arma nada y lo dice, porque sin los datos de la
     * cabecera no puede cifrar.
     *
     * Si [target] ya existe, es un resto de una ejecución cortada y se borra antes.
     */
    suspend fun compress(snapshot: File, target: File): PackageResult =
        compress(snapshot, { target }, facts = null)

    /**
     * Arma la copia de [snapshot] en el archivo que da [targetFor], cifrado o en claro según el
     * cifrado de las copias, y la comprueba. Si queda bien, borra [snapshot]. [facts] son los datos
     * de la cabecera de una copia cifrada.
     *
     * Si el archivo ya existe, es un resto de una ejecución cortada y se borra antes.
     */
    suspend fun compress(
        snapshot: File,
        targetFor: (encrypted: Boolean) -> File,
        facts: CopyFacts?,
    ): PackageResult {
        val code =
            when (val mode = encryption()) {
                PackEncryption.Plain -> null
                is PackEncryption.Encrypt -> mode.code
                PackEncryption.KeyUnavailable -> return PackageResult.EncryptionUnavailable
                PackEncryption.RetryLater -> return PackageResult.KeyRetry
            }
        if (code != null && facts == null) return PackageResult.EncryptionUnavailable
        val target = targetFor(code != null)
        require(target.isAbsolute) { "La ruta del archivo comprimido debe ser absoluta" }
        var packed = false
        try {
            val result = withContext(ioContext) {
                val checkActive = { coroutineContext.ensureActive() }
                if (code == null) {
                    pack(snapshot, target, checkActive)
                } else {
                    packEncrypted(snapshot, target, requireNotNull(facts), code, checkActive)
                }
            }
            packed = result is PackageResult.Packed
            return result
        } finally {
            if (!packed) withContext(NonCancellable + ioContext) { target.delete() }
        }
    }

    private fun packEncrypted(
        snapshot: File,
        target: File,
        facts: CopyFacts,
        code: RecoveryCode,
        checkActive: () -> Unit,
    ): PackageResult {
        target.delete()
        val key = BackupKey.from(code)
        val header =
            BackupHeader(
                keyId = key.keyId,
                generation = facts.generation,
                schemaVersion = facts.schemaVersion.toLong(),
                createdAtUtcMillis = facts.createdAtUtcMillis,
                deviceId = UUID.fromString(facts.deviceId),
                rawSize = snapshot.length(),
            )
        val file = Measure()
        val raw =
            try {
                FileInputStream(snapshot).use { input ->
                    FileOutputStream(target).use { output ->
                        val content =
                            EncryptedCopy.write(
                                input,
                                MeasuredOutput(output, file, maxFileBytes),
                                header,
                                key,
                                checkActive,
                            )
                        // La copia puede esperar un reintento de subida: debe sobrevivir a un corte.
                        output.fd.sync()
                        content
                    }
                }
            } catch (_: TooLarge) {
                return PackageResult.TooLarge
            } catch (_: RawSizeMismatch) {
                return PackageResult.Mismatch
            } catch (failure: IOException) {
                return if (isStorageFull(failure)) {
                    PackageResult.StorageFull
                } else {
                    PackageResult.Failed(failure)
                }
            }
        val written = Written(Sum(raw.size, raw.sha256), file.done())
        if (!opens(target, written, code, checkActive)) return PackageResult.Mismatch
        snapshot.delete()
        return PackageResult.Packed(
            file = target,
            fileSizeBytes = written.file.size,
            fileSha256 = written.file.hex,
            rawSizeBytes = written.raw.size,
            keyIdHex = key.keyIdHex,
        )
    }

    /**
     * La comprobación de ida y vuelta de una copia cifrada: relee [target] en flujo, lo descifra
     * con la clave derivada otra vez de [code], lo descomprime y compara largo y suma con la
     * instantánea, y la suma del archivo con la escrita.
     */
    private fun opens(
        target: File,
        written: Written,
        code: RecoveryCode,
        checkActive: () -> Unit,
    ): Boolean {
        val file = Measure()
        return try {
            FileInputStream(target).use { input ->
                val result =
                    EncryptedCopy.read(
                        MeasuredInput(input, file),
                        BackupKey.from(code),
                        sink = null,
                        maxRawBytes = written.raw.size,
                        checkActive = checkActive,
                    )
                result is EncryptedReadResult.Read &&
                    result.content == RawContent(written.raw.size, written.raw.hex) &&
                    file.done() == written.file
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun pack(snapshot: File, target: File, checkActive: () -> Unit): PackageResult {
        target.delete()
        val written =
            try {
                write(snapshot, target, checkActive)
            } catch (_: TooLarge) {
                return PackageResult.TooLarge
            } catch (failure: IOException) {
                return if (isStorageFull(failure)) {
                    PackageResult.StorageFull
                } else {
                    PackageResult.Failed(failure)
                }
            }
        if (!matches(target, written, checkActive)) return PackageResult.Mismatch
        snapshot.delete()
        return PackageResult.Packed(
            file = target,
            fileSizeBytes = written.file.size,
            fileSha256 = written.file.hex,
            rawSizeBytes = written.raw.size,
        )
    }

    /** Escribe el archivo comprimido. Devuelve largo y suma de lo que leyó y de lo que escribió. */
    private fun write(snapshot: File, target: File, checkActive: () -> Unit): Written {
        val raw = Measure()
        val file = Measure()
        FileInputStream(snapshot).use { input ->
            FileOutputStream(target).use { output ->
                // Cerrar el compresor también libera su memoria nativa, aunque haya fallado.
                compressor(MeasuredOutput(output, file, maxFileBytes)).use { gzip ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        checkActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        raw.update(buffer, read)
                        gzip.write(buffer, 0, read)
                    }
                    // Sin el cierre de gzip, el archivo no se descomprime aunque las sumas cuadren.
                    gzip.finish()
                    gzip.flush()
                    // La copia puede esperar un reintento de subida: debe sobrevivir a un corte.
                    output.fd.sync()
                }
            }
        }
        return Written(raw.done(), file.done())
    }

    /**
     * Relee [target] en flujo: su suma debe ser la que se calculó al escribirlo, y descomprimido
     * debe dar exactamente los bytes de la instantánea.
     */
    private fun matches(target: File, written: Written, checkActive: () -> Unit): Boolean {
        val raw = Measure()
        val file = Measure()
        return try {
            FileInputStream(target).use { input ->
                val measured = MeasuredInput(input, file)
                val buffer = ByteArray(BUFFER_BYTES)
                // La descompresión se corta apenas da un byte más que la instantánea.
                GZIPInputStream(measured, BUFFER_BYTES).use { gzip ->
                    while (true) {
                        checkActive()
                        val read = gzip.read(buffer)
                        if (read < 0) break
                        raw.update(buffer, read)
                        if (raw.size > written.raw.size) return false
                    }
                    // Lo que quede detrás del gzip también cuenta para la suma del archivo.
                    while (measured.read(buffer) >= 0) checkActive()
                }
            }
            raw.done() == written.raw && file.done() == written.file
        } catch (_: IOException) {
            // Un gzip cortado o dañado no se descomprime: la copia no sirve.
            false
        }
    }

    private fun isStorageFull(failure: IOException): Boolean =
        generateSequence<Throwable>(failure) { it.cause }
            .any { it is ErrnoException && it.errno == OsConstants.ENOSPC }

    /** Largo y suma SHA-256 de un flujo, en cifras hexadecimales en minúscula. */
    private data class Sum(val size: Long, val hex: String)

    private class Written(val raw: Sum, val file: Sum)

    private class Measure {
        private val digest = newSha256()
        var size = 0L
            private set

        fun update(bytes: ByteArray, length: Int) = update(bytes, 0, length)

        fun update(bytes: ByteArray, offset: Int, length: Int) {
            digest.update(bytes, offset, length)
            size += length
        }

        fun done(): Sum = Sum(size, digest.digest().toLowerHex())
    }

    /** Cuenta y suma lo que pasa hacia el archivo, y se detiene al pasar el tope. */
    private class MeasuredOutput(
        output: OutputStream,
        private val measure: Measure,
        private val limit: Long,
    ) : FilterOutputStream(output) {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (measure.size + len > limit) throw TooLarge()
            measure.update(b, off, len)
            out.write(b, off, len)
        }
    }

    /** Cuenta y suma lo que se lee del archivo. */
    private class MeasuredInput(input: InputStream, private val measure: Measure) :
        FilterInputStream(input) {
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = `in`.read(b, off, len)
            if (read > 0) measure.update(b, off, read)
            return read
        }

        override fun skip(n: Long): Long {
            // Saltar dejaría bytes sin sumar: se leen.
            val buffer = ByteArray(minOf(n, BUFFER_BYTES.toLong()).toInt())
            val read = read(buffer, 0, buffer.size)
            return if (read < 0) 0 else read.toLong()
        }
    }

    /** El archivo pasó el tope: se deja de escribir. */
    private class TooLarge : IOException()

    private companion object {
        /** 64 KiB: el búfer de lectura, de escritura y del gzip. */
        const val BUFFER_BYTES = 64 * 1024

        const val BYTE_MASK = 0xFF
    }
}

/** Lo que dio comprimir una instantánea. */
internal sealed interface PackageResult {
    /**
     * El archivo comprimido quedó en [file] y se comprobó. [fileSha256] es la suma de sus bytes,
     * en hexadecimal en minúscula, y [rawSizeBytes] el tamaño de la base sin comprimir.
     */
    data class Packed(
        val file: File,
        val fileSizeBytes: Long,
        val fileSha256: String,
        val rawSizeBytes: Long,
        val keyIdHex: String? = null,
    ) : PackageResult {
        /** Si es la copia cifrada, el formato 2. */
        val encrypted: Boolean get() = keyIdHex != null
    }

    /** El archivo pasaba el tope de tamaño de una copia. No se sube. */
    data object TooLarge : PackageResult

    /** No hubo espacio para escribir el archivo. */
    data object StorageFull : PackageResult

    /** El archivo escrito no se descomprime en la instantánea, o su suma cambió. No se sube. */
    data object Mismatch : PackageResult

    /** El cifrado está activo y la clave no abre: no se arma nada, y nunca en claro. */
    data object EncryptionUnavailable : PackageResult

    /** Keystore falló un momento al abrir la clave: se reintenta sin cambiar nada. */
    data object KeyRetry : PackageResult

    /** Otra falla del disco. Solo la clase de [cause] puede ir al registro. */
    class Failed(val cause: IOException) : PackageResult
}

/** Cómo sale una copia: en claro, cifrada con un código, o no sale. */
internal sealed interface PackEncryption {
    /** El cifrado está apagado: la copia sale en claro, el formato 1. */
    data object Plain : PackEncryption

    /** El cifrado está activo y el código sellado abre. */
    class Encrypt(val code: RecoveryCode) : PackEncryption {
        override fun toString(): String = "Encrypt"
    }

    /** El cifrado está activo y el código no abre en este teléfono: no sale nada. */
    data object KeyUnavailable : PackEncryption

    /** Keystore falló un momento: se reintenta. */
    data object RetryLater : PackEncryption
}

/**
 * Los datos de la cabecera de una copia cifrada que no salen del archivo.
 *
 * @property deviceId la instalación, en la forma de texto de un UUID.
 */
internal class CopyFacts(
    val generation: Long,
    val schemaVersion: Int,
    val createdAtUtcMillis: Long,
    val deviceId: String,
)
