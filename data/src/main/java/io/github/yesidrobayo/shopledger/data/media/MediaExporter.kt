package io.github.yesidrobayo.shopledger.data.media

import io.github.yesidrobayo.shopledger.data.backup.ExportSink
import io.github.yesidrobayo.shopledger.data.backup.PackEncryption
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoExport
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * `Exportar fotos`: un `media-AAAAMMDD.zip` aparte de la copia, en el documento que eligió la
 * persona. Nunca entra en la copia de la base.
 *
 * - **Con el cifrado activo, nada en claro.** Cada foto va como `<suma>.slmd`, cifrada con la clave
 *   de las copias; sin la clave no se exporta nada. Sin cifrado, cada una va como `<suma>.webp`.
 * - **Sin comprimir:** un WebP ya está comprimido, y cifrado no se comprime.
 * - **Comprobado.** El ZIP se arma en `cache/exports/`, se copia al documento, se relee y se compara
 *   su suma. Si algo falla, el documento se descarta y el temporal se borra siempre.
 *
 * Solo lee la base y los archivos: no cambia nada, ni el estado del respaldo.
 *
 * @param photos las fotos en uso, o `null` si la base todavía no se puede leer.
 */
internal class MediaExporter(
    private val photos: suspend () -> List<StoredImage>?,
    private val mediaDirectory: () -> File,
    private val workDirectory: () -> File,
    private val sinkFor: (String) -> ExportSink,
    private val encryption: suspend () -> PackEncryption,
    private val deviceId: suspend () -> String,
    private val nowUtcMillis: () -> Long,
    private val ioContext: CoroutineContext,
) {
    private val mutex = Mutex()

    suspend fun export(documentUri: String): PhotoExport = mutex.withLock {
        val inUse = photos() ?: return PhotoExport.Failed
        val directory = mediaDirectory()
        val files =
            withContext(ioContext) {
                inUse.map { it.sha256 to File(directory, StoredImage.fileNameOf(it.sha256)) }
                    .filter { (_, file) -> file.isFile }
            }
        if (files.isEmpty()) return PhotoExport.NoPhotos
        val key =
            when (val mode = encryption()) {
                PackEncryption.Plain -> null
                is PackEncryption.Encrypt -> BackupKey.from(mode.code)
                PackEncryption.KeyUnavailable -> return PhotoExport.KeyUnavailable
                PackEncryption.RetryLater -> return PhotoExport.Failed
            }
        val device = if (key == null) "" else deviceId()
        val now = nowUtcMillis()
        val temporary = File(workDirectory(), TEMPORARY_NAME)
        val sink = sinkFor(documentUri)
        var saved = false
        try {
            return withContext(ioContext) {
                temporary.parentFile?.mkdirs()
                val sum = writeZip(temporary, files, key, device, now)
                FileInputStream(temporary).use(sink::write)
                if (sink.read().use(::sha256Of) != sum) return@withContext PhotoExport.Failed
                saved = true
                PhotoExport.Saved(files.size)
            }
        } catch (_: IOException) {
            return PhotoExport.Failed
        } finally {
            withContext(NonCancellable + ioContext) {
                temporary.delete()
                if (!saved) sink.discard()
            }
        }
    }

    /** Arma el ZIP en [target] y devuelve su suma. */
    private fun writeZip(
        target: File,
        files: List<Pair<String, File>>,
        key: BackupKey?,
        deviceId: String,
        now: Long,
    ): String {
        FileOutputStream(target).use { output ->
            // Sin cerrar el ZIP: cerrarlo cerraría el archivo antes de sincronizarlo.
            val zip = ZipOutputStream(output)
            zip.setLevel(Deflater.NO_COMPRESSION)
            for ((sha, file) in files) {
                val plain = file.readBytes()
                val form = if (key == null) MediaForm.Plain else MediaForm.Sealed(key.keyIdHex)
                zip.putNextEntry(ZipEntry(MediaNames.fileOf(sha, form)))
                zip.write(if (key == null) plain else MediaSeal.seal(plain, key, deviceId, now))
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
            output.fd.sync()
        }
        return FileInputStream(target).use(::sha256Of)
    }

    private fun sha256Of(input: InputStream): String {
        val digest = newSha256()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toLowerHex()
    }

    companion object {
        private const val TEMPORARY_NAME = "media-export.zip.tmp"
        private const val BUFFER_BYTES = 64 * 1024
    }
}
