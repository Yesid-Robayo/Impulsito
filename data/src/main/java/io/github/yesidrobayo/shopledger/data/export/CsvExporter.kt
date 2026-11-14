package io.github.yesidrobayo.shopledger.data.export

import android.content.ContentResolver
import android.net.Uri
import androidx.room3.withReadTransaction
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.DocumentExportSink
import io.github.yesidrobayo.shopledger.data.backup.isNoSpace
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.audit.AuditEventEntity
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.model.export.CsvDateRange
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CsvExportPort
import io.github.yesidrobayo.shopledger.domain.port.CsvExportRequest
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.warn
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.export.SheetCellWriter
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Exporta los datos del negocio a un `.zip` con un CSV por archivo, en el documento que la persona
 * creó con el selector del sistema.
 *
 * 1. Lee todas las tablas dentro de una sola transacción de lectura: si mientras tanto se registra
 *    una venta, ningún archivo la ve, y ninguna venta queda sin sus líneas. La venta sigue igual: la
 *    lectura no detiene las escrituras.
 * 2. Escribe el `.zip` directo en el documento, fila por fila, sin armarlo antes en el teléfono: no
 *    deja ningún archivo con datos en la app, y no necesita el doble de espacio. Cada CSV empieza con
 *    la marca de UTF-8 y lleva el encabezado.
 * 3. Sincroniza y relee el documento entero: tamaño y SHA-256 deben ser los que se escribieron. El
 *    documento ya tiene su nombre final desde que el selector lo creó, así que si algo falla, o se
 *    cancela, se borra: nunca queda una exportación a medias que parezca completa.
 * 4. Anota `EXPORT_CSV` en la auditoría, sin montos ni nombres. Una falla al anotar no cambia el
 *    resultado.
 *
 * Sin red. Solo lee la base. Una exportación a la vez.
 *
 * @param rows la lectura de la base, o nulo si la base no se puede abrir.
 * @param documentFor el documento elegido, por su URI.
 * @param recordExport anota la exportación en la auditoría.
 * @param ioContext el contexto de las lecturas y escrituras, fuera del hilo principal.
 */
internal class CsvExporter(
    private val rows: suspend () -> CsvRowSource?,
    private val documentFor: (String) -> CsvDocument,
    private val recordExport: suspend () -> Unit,
    private val log: EventLog,
    private val ioContext: CoroutineContext,
) : CsvExportPort {
    private val mutex = Mutex()

    override suspend fun export(
        request: CsvExportRequest,
        progress: (CsvExportProgress) -> Unit,
    ): CsvExport = mutex.withLock {
        val source = rows() ?: return@withLock CsvExport.Failed
        val document = documentFor(request.documentUri)
        var settled = false
        try {
            val result =
                withContext(ioContext) { writeAndCheck(source, document, request, progress) }
            settled = true
            if (result is CsvExport.Saved) {
                recordExport()
            } else {
                withContext(NonCancellable + ioContext) { document.discard() }
            }
            result
        } finally {
            // Cancelada a mitad, lo escrito hasta ahí no puede quedar con su nombre final.
            if (!settled) withContext(NonCancellable + ioContext) { document.discard() }
        }
    }

    private suspend fun writeAndCheck(
        source: CsvRowSource,
        document: CsvDocument,
        request: CsvExportRequest,
        progress: (CsvExportProgress) -> Unit,
    ): CsvExport = try {
        var rowCount = 0L
        val written =
            document.write { output ->
                rowCount = writeArchive(source, output, request, progress)
            }
        val (size, sha) = document.read().use(::measure)
        if (size == written.sizeBytes && sha == written.sha256) {
            CsvExport.Saved(CsvExportTable.entries.size, rowCount)
        } else {
            log.warn(CsvExportEvent.CHECK_MISMATCH)
            CsvExport.Failed
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: IOException) {
        log.warn(CsvExportEvent.WRITE_FAILED, failure)
        if (isNoSpace(failure)) CsvExport.NoSpace else CsvExport.Failed
    } catch (failure: Exception) {
        // La base, un URI que el sistema no reconoce o un permiso perdido: solo la clase al registro.
        log.warn(CsvExportEvent.FAILED, failure)
        CsvExport.Failed
    }

    /**
     * Escribe el `.zip` en [output], o el `.xlsx` si la petición trae celdas de hoja, y devuelve las
     * filas de datos escritas.
     */
    private suspend fun writeArchive(
        source: CsvRowSource,
        output: OutputStream,
        request: CsvExportRequest,
        progress: (CsvExportProgress) -> Unit,
    ): Long {
        val sheet = request.spreadsheet
        if (sheet != null) return writeWorkbook(source, output, request, sheet, progress)
        val tables = CsvExportTable.entries
        val zip = ZipOutputStream(output)
        // El escritor no cierra el .zip al terminar cada archivo: solo vacía lo pendiente.
        val writer = OutputStreamWriter(zip, Charsets.UTF_8).buffered(WRITER_BUFFER_CHARS)
        var total = 0L
        source.snapshot { reader ->
            tables.forEachIndexed { done, table ->
                zip.putNextEntry(ZipEntry(request.naming.fileName(table)))
                writer.write(CsvRowWriter.BYTE_ORDER_MARK)
                writer.write(request.rows.header(table.columns.map(request.naming::header)))
                total +=
                    reader.forEachRow(table, request.naming::seedName, request.range) { cells ->
                        writer.write(request.rows.row(cells))
                    }
                writer.flush()
                zip.closeEntry()
                progress(CsvExportProgress(done + 1, tables.size))
            }
        }
        zip.finish()
        zip.flush()
        return total
    }

    /** Escribe el `.xlsx` en [output], una hoja por archivo y en la misma lectura que el CSV. */
    private suspend fun writeWorkbook(
        source: CsvRowSource,
        output: OutputStream,
        request: CsvExportRequest,
        cells: SheetCellWriter,
        progress: (CsvExportProgress) -> Unit,
    ): Long {
        val tables = CsvExportTable.entries
        val zip = ZipOutputStream(output)
        val names = tables.map { request.naming.fileName(it).removeSuffix(CSV_EXTENSION) }
        val workbook = XlsxWorkbookWriter(zip, names)
        workbook.start()
        var total = 0L
        source.snapshot { reader ->
            tables.forEachIndexed { done, table ->
                workbook.startSheet(table.columns.map(request.naming::header))
                total +=
                    reader.forEachRow(table, request.naming::seedName, request.range) { row ->
                        workbook.row(row.map(cells::cell))
                    }
                workbook.endSheet()
                progress(CsvExportProgress(done + 1, tables.size))
            }
        }
        zip.finish()
        zip.flush()
        return total
    }

    private fun measure(input: InputStream): Pair<Long, String> {
        val digest = newSha256()
        val buffer = ByteArray(BUFFER_BYTES)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            size += read
        }
        return size to digest.digest().toLowerHex()
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val WRITER_BUFFER_CHARS = 64 * 1024
        const val CSV_EXTENSION = ".csv"
    }
}

/** Las tablas de la base, leídas en una sola transacción. */
internal interface CsvRowSource {
    /** Corre [block] con una lectura que ve la base igual de principio a fin. */
    suspend fun <T> snapshot(block: suspend (CsvRowReader) -> T): T
}

/** Recorre las filas de un archivo de la exportación. */
internal interface CsvRowReader {
    /**
     * Llama a [each] con las celdas de cada fila de [table], en orden. Devuelve cuántas fueron. Con
     * [range], los documentos y movimientos se limitan a esas fechas de negocio; los catálogos no.
     */
    suspend fun forEachRow(
        table: CsvExportTable,
        seedName: (SeedTable, String) -> String,
        range: CsvDateRange?,
        each: (List<CsvCell>) -> Unit,
    ): Long
}

/**
 * La base de la app, leída en una transacción de lectura: toma su foto al leer la primera fila y la
 * conserva hasta el final, mientras la conexión de escritura sigue.
 */
internal class RoomCsvRowSource(private val database: AppDatabase) : CsvRowSource {
    override suspend fun <T> snapshot(block: suspend (CsvRowReader) -> T): T =
        database.withReadTransaction {
            val connection = this
            block(
                object : CsvRowReader {
                    override suspend fun forEachRow(
                        table: CsvExportTable,
                        seedName: (SeedTable, String) -> String,
                        range: CsvDateRange?,
                        each: (List<CsvCell>) -> Unit,
                    ): Long {
                        val query = CsvTableQueries.of(table)
                        val ranged = range != null
                        return connection.usePrepared(query.sqlFor(ranged)) { statement ->
                            if (range != null && query.usesRange(ranged)) {
                                // Las fechas se guardan como `AAAA-MM-DD`: comparar el texto ordena.
                                statement.bindText(1, range.from.toString())
                                statement.bindText(2, range.to.toString())
                            }
                            var count = 0L
                            while (statement.step()) {
                                each(query.cells(CsvRow(statement, seedName)))
                                count++
                            }
                            count
                        }
                    }
                },
            )
        }
}

/** El documento donde se escribe una exportación, como lo deja [write]. */
internal data class WrittenDocument(val sizeBytes: Long, val sha256: String)

/** El documento elegido por la persona. */
internal interface CsvDocument {
    /**
     * Escribe con [block] desde el principio, reemplazando lo que hubiera, y lo deja en el disco.
     * Devuelve el tamaño y la suma de lo escrito.
     */
    suspend fun write(block: suspend (OutputStream) -> Unit): WrittenDocument

    /** Lee lo que quedó escrito. */
    fun read(): InputStream

    /** Lo deja vacío y lo borra: algo falló y no puede quedar con su nombre. Nunca lanza. */
    fun discard()
}

/**
 * Un documento del selector del sistema, por su URI. Escribe con `wt`, que trunca, y pide el
 * descriptor para sincronizar antes de releer. Borrar es lo mismo que con una copia exportada.
 */
internal class ContentCsvDocument(private val resolver: ContentResolver, private val uri: Uri) :
    CsvDocument {
    override suspend fun write(block: suspend (OutputStream) -> Unit): WrittenDocument {
        val descriptor =
            resolver.openFileDescriptor(uri, "wt")
                ?: throw FileNotFoundException("El documento no se pudo abrir")
        return descriptor.use {
            FileOutputStream(it.fileDescriptor).use { file ->
                val measured = MeasuringOutputStream(file.buffered(BUFFER_BYTES), newSha256())
                block(measured)
                measured.flush()
                file.fd.sync()
                WrittenDocument(measured.size, measured.digest.digest().toLowerHex())
            }
        }
    }

    override fun read(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("El documento no se pudo leer")

    override fun discard() = DocumentExportSink(resolver, uri).discard()

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

/** Cuenta y suma lo que pasa hacia [out], sin cerrarlo antes de tiempo. */
private class MeasuringOutputStream(out: OutputStream, val digest: MessageDigest) :
    FilterOutputStream(out) {
    var size = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        digest.update(b.toByte())
        size++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        digest.update(b, off, len)
        size += len
    }
}

/**
 * Anota en la auditoría que se exportó un CSV. Una falla al anotar nunca cambia el resultado: el
 * archivo ya está escrito y comprobado, y la falla solo queda en el registro, por su clase.
 */
internal suspend fun recordCsvExported(ports: DataPorts, clock: Clock, log: EventLog) {
    try {
        ports.auditEvents.insert(
            AuditEventEntity(
                occurredAt = clock.nowEpochMillis(),
                eventType = AuditEventType.EXPORT_CSV.name,
                result = AuditResult.SUCCESS.name,
            ),
        )
        ports.auditEvents.pruneOldest()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        log.warn(CsvExportEvent.AUDIT_FAILED, failure)
    }
}

/** Los eventos del registro de la exportación. Solo motivos y clases, nunca un dato ni un URI. */
internal enum class CsvExportEvent(override val text: String) : LogEvent {
    WRITE_FAILED("La exportación CSV no se pudo escribir"),
    CHECK_MISMATCH("La exportación CSV releída no coincide con lo escrito"),
    FAILED("La exportación CSV falló"),
    AUDIT_FAILED("La exportación CSV no se pudo anotar en la auditoría"),
}
