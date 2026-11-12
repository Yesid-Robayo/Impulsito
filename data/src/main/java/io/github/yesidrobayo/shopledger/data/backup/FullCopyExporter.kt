package io.github.yesidrobayo.shopledger.data.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.data.local.audit.AuditEventEntity
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.backup.FullCopyExport
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.FullCopyExportPort
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Exporta una copia completa al documento que la persona creó con el selector del sistema.
 *
 * 1. Arma la copia con los pasos del respaldo ([LocalCopyBuilder]) en `cache/exports/`. Una base
 *    dañada no se exporta; una que la app no podría restaurar, sí, y lo dice: exportar es la salida
 *    cuando todo lo demás falla.
 * 2. La escribe en el documento, sincroniza y la relee entera: tamaño y SHA-256 deben ser los del
 *    archivo armado. El documento ya tiene su nombre final desde que el selector lo creó, así que
 *    si algo falla después de empezar a escribir, se borra: nunca queda una copia a medias que
 *    parezca buena.
 * 3. Anota `EXPORT_FULL_COPY` en la auditoría. Una falla al anotar no cambia el resultado.
 * 4. Borra todo lo de `cache/exports/` en un `finally`, también si se cancela.
 *
 * Sin red. Solo lee la base: no cambia `verified_seq`, la cabeza ni el destino conectado, porque una
 * copia exportada no es una copia en el destino. Una exportación a la vez.
 *
 * @param builder arma la copia; nulo si la base no se puede abrir.
 * @param exportsDirectory la carpeta `cache/exports/`. Se pide al exportar: pedirla toca el disco.
 * @param sinkFor el documento elegido, por su URI.
 * @param recordExport anota la exportación en la auditoría.
 * @param ioContext el contexto de las lecturas y escrituras, fuera del hilo principal.
 */
internal class FullCopyExporter(
    private val builder: suspend () -> LocalCopyBuilder?,
    private val exportsDirectory: () -> File,
    private val sinkFor: (String) -> ExportSink,
    private val recordExport: suspend () -> Unit,
    private val ioContext: CoroutineContext,
) : FullCopyExportPort {
    private val mutex = Mutex()

    override suspend fun export(documentUri: String): FullCopyExport = mutex.withLock {
        val directory = withContext(ioContext) { exportsDirectory().also(::emptyDirectory) }
        try {
            val copy =
                when (val built = builder()?.build(directory) ?: LocalCopy.Failed) {
                    is LocalCopy.Built -> built
                    LocalCopy.Damaged -> return@withLock FullCopyExport.Damaged
                    LocalCopy.NoSpace -> return@withLock FullCopyExport.NoSpace
                    LocalCopy.TooLarge, LocalCopy.Failed -> return@withLock FullCopyExport.Failed
                }
            val sink = sinkFor(documentUri)
            val written = write(copy, sink)
            if (written != null) {
                withContext(NonCancellable + ioContext) { sink.discard() }
                return@withLock written
            }
            recordExport()
            FullCopyExport.Saved(copy.restorable, copy.sizeBytes)
        } finally {
            withContext(NonCancellable + ioContext) { emptyDirectory(directory) }
        }
    }

    /** Escribe y relee la copia. Devuelve nulo si quedó igual, o el resultado del fallo. */
    private suspend fun write(copy: LocalCopy.Built, sink: ExportSink): FullCopyExport? {
        var done = false
        try {
            val result =
                withContext(ioContext) {
                    try {
                        FileInputStream(copy.file).use { input -> sink.write(input) }
                        val (size, sha) = sink.read().use(::measure)
                        if (size == copy.sizeBytes && sha == copy.sha256) {
                            null
                        } else {
                            FullCopyExport.Failed
                        }
                    } catch (failure: IOException) {
                        if (isNoSpace(failure)) FullCopyExport.NoSpace else FullCopyExport.Failed
                    } catch (_: SecurityException) {
                        FullCopyExport.Failed
                    } catch (_: IllegalArgumentException) {
                        // Un URI que el sistema no reconoce.
                        FullCopyExport.Failed
                    }
                }
            done = true
            return result
        } finally {
            // Cancelada a mitad, la copia escrita hasta ahí no puede quedar con su nombre final.
            if (!done) withContext(NonCancellable + ioContext) { sink.discard() }
        }
    }

    private fun measure(input: InputStream): Pair<Long, String> {
        val digest = newSha256()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
        }
        return total to digest.digest().toLowerHex()
    }

    private fun emptyDirectory(directory: File) {
        if (!directory.isDirectory) directory.mkdirs()
        directory.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        /** La carpeta de los archivos que salen de la app, dentro de `cacheDir`. */
        const val DIRECTORY_NAME = "exports"

        private const val BUFFER_BYTES = 64 * 1024
    }
}

/** El documento donde se escribe una copia exportada. */
internal interface ExportSink {
    /** Escribe todo [input] desde el principio, reemplazando lo que hubiera, y lo deja en el disco. */
    fun write(input: InputStream)

    /** Lee lo que quedó escrito. */
    fun read(): InputStream

    /** Borra el documento: algo falló y no puede quedar con su nombre. Nunca lanza. */
    fun discard()
}

/**
 * Un documento del selector del sistema, por su URI. Escribe con `wt`, que trunca: `w` solo, en
 * algunas versiones, deja al final los bytes de un archivo más largo. Se pide el descriptor para
 * sincronizar antes de releer.
 */
internal class DocumentExportSink(private val resolver: ContentResolver, private val uri: Uri) :
    ExportSink {
    override fun write(input: InputStream) {
        val descriptor =
            resolver.openFileDescriptor(uri, "wt")
                ?: throw FileNotFoundException("El documento no se pudo abrir")
        descriptor.use {
            FileOutputStream(it.fileDescriptor).use { output ->
                input.copyTo(output, BUFFER_BYTES)
                output.flush()
                output.fd.sync()
            }
        }
    }

    override fun read(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("El documento no se pudo leer")

    override fun discard() {
        // Primero se deja vacío y después se borra: hay proveedores que dicen haber borrado sin
        // hacerlo, y un archivo vacío nunca se confunde con una copia.
        try {
            resolver.openFileDescriptor(uri, "wt")?.close()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            Unit
        }
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            Unit
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

/**
 * Anota en la auditoría que se exportó una copia completa. Una falla al anotar nunca cambia el
 * resultado: el archivo ya está escrito y comprobado, y la falla solo queda en el registro, por su
 * clase.
 */
internal suspend fun recordFullCopyExported(ports: DataPorts, clock: Clock, log: EventLog) {
    try {
        ports.auditEvents.insert(
            AuditEventEntity(
                occurredAt = clock.nowEpochMillis(),
                eventType = AuditEventType.EXPORT_FULL_COPY.name,
                result = AuditResult.SUCCESS.name,
            ),
        )
        ports.auditEvents.pruneOldest()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        log.warn(BackupEvent.EXPORT_AUDIT_FAILED, failure)
    }
}
