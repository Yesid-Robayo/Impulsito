package io.github.yesidrobayo.shopledger.data.backup

import android.system.ErrnoException
import android.system.OsConstants
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Convierte la instantánea sellada en el archivo que se sube: la base comprimida con gzip, con la
 * suma SHA-256 de sus bytes tal como quedan en el destino.
 *
 * - **En flujo.** Lee la instantánea, la comprime, cuenta y suma en una sola pasada, con un búfer
 *   fijo: nunca carga la base en memoria, que puede pesar cientos de megabytes.
 * - **Con tope.** Si el archivo pasa de [maxFileBytes] se detiene: una copia más grande que lo que
 *   la restauración acepta bajar no serviría para restaurar.
 * - **Comprobado antes de subir.** Las sumas solas no prueban que la copia se pueda abrir: un gzip
 *   sin su cierre da sumas estables y falla al descomprimir. Por eso, ya escrito, vuelve a leer el
 *   archivo en flujo, lo descomprime y compara largo y suma con la instantánea, y además relee la
 *   suma del archivo. Solo entonces borra la instantánea.
 * - **Nada a medias.** Si algo falla o se cancela, el archivo comprimido se borra antes de devolver.
 *   La instantánea queda: la limpieza de la carpeta de preparación la borra.
 *
 * No registra nada: quien la llama decide qué hacer con el resultado.
 *
 * @param ioContext contexto de la compresión, fuera del hilo principal.
 * @param maxFileBytes el mayor archivo comprimido que se acepta.
 * @param compressor abre el compresor sobre el archivo. Siempre el gzip con su nivel por defecto;
 *   las pruebas lo cambian para fabricar un archivo dañado.
 */
internal class BackupPackager(
    private val ioContext: CoroutineContext,
    private val maxFileBytes: Long = BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES,
    private val compressor: (OutputStream) -> GZIPOutputStream = {
        GZIPOutputStream(it, BUFFER_BYTES)
    },
) {
    /**
     * Comprime [snapshot] en [target] y lo comprueba. Si queda bien, borra [snapshot].
     *
     * Si [target] ya existe, es un resto de una ejecución cortada y se borra antes.
     */
    suspend fun compress(snapshot: File, target: File): PackageResult {
        require(target.isAbsolute) { "La ruta del archivo comprimido debe ser absoluta" }
        var packed = false
        try {
            val result = withContext(ioContext) {
                val checkActive = { coroutineContext.ensureActive() }
                pack(snapshot, target, checkActive)
            }
            packed = result is PackageResult.Packed
            return result
        } finally {
            if (!packed) withContext(NonCancellable + ioContext) { target.delete() }
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
        private val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
            private set

        fun update(bytes: ByteArray, length: Int) = update(bytes, 0, length)

        fun update(bytes: ByteArray, offset: Int, length: Int) {
            digest.update(bytes, offset, length)
            size += length
        }

        fun done(): Sum = Sum(size, lowerHex(digest.digest()))
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

        /** Hexadecimal sin pasar por el formato del idioma, que podría escribir otras cifras. */
        fun lowerHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and BYTE_MASK
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }

        const val HEX_DIGITS = "0123456789abcdef"
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
    ) : PackageResult

    /** El archivo pasaba el tope de tamaño de una copia. No se sube. */
    data object TooLarge : PackageResult

    /** No hubo espacio para escribir el archivo. */
    data object StorageFull : PackageResult

    /** El archivo escrito no se descomprime en la instantánea, o su suma cambió. No se sube. */
    data object Mismatch : PackageResult

    /** Otra falla del disco. Solo la clase de [cause] puede ir al registro. */
    class Failed(val cause: IOException) : PackageResult
}
