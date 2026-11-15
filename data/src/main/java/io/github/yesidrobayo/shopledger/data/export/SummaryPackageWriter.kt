package io.github.yesidrobayo.shopledger.data.export

import io.github.yesidrobayo.shopledger.data.backup.isNoSpace
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryFile
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.ManagementSummaryPackagePort
import io.github.yesidrobayo.shopledger.domain.port.SummaryPackageRequest
import io.github.yesidrobayo.shopledger.domain.port.warn
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Escribe el paquete del resumen de gestión: un `.zip` con el PDF de resumen primero y después un
 * CSV por cada archivo pedido, con las mismas columnas, la misma neutralización de fórmulas y el
 * mismo formato regional que la exportación completa.
 *
 * 1. Lee todos los CSV dentro de una sola transacción de lectura: una venta registrada mientras
 *    tanto no entra en ningún archivo, y ninguna venta queda sin sus líneas.
 * 2. Escribe el `.zip` en el archivo pedido, fila por fila, sin armarlo antes en memoria.
 * 3. Lo sincroniza y lo relee entero: tamaño y SHA-256 deben ser los que se escribieron. Si algo
 *    falla, o se cancela, lo borra: nunca queda un paquete a medias que parezca completo.
 * 4. Anota `EXPORT_CSV` en la auditoría, sin montos ni nombres. Una falla al anotar no cambia el
 *    resultado.
 *
 * Sin red. Solo lee la base. Un paquete a la vez.
 *
 * @param rows la lectura de la base, o nulo si la base no se puede abrir.
 * @param recordExport anota la exportación en la auditoría.
 * @param ioContext el contexto de las lecturas y escrituras, fuera del hilo principal.
 */
internal class SummaryPackageWriter(
    private val rows: suspend () -> CsvRowSource?,
    private val recordExport: suspend () -> Unit,
    private val log: EventLog,
    private val ioContext: CoroutineContext,
    private val documentFor: (String) -> CsvDocument = { path -> FileCsvDocument(File(path)) },
) : ManagementSummaryPackagePort {
    private val mutex = Mutex()

    override suspend fun write(
        request: SummaryPackageRequest,
        progress: (CsvExportProgress) -> Unit,
    ): CsvExport = mutex.withLock {
        val source = rows() ?: return@withLock CsvExport.Failed
        val document = documentFor(request.targetPath)
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
            // Cancelado a mitad, lo escrito hasta ahí no puede quedar con su nombre final.
            if (!settled) withContext(NonCancellable + ioContext) { document.discard() }
        }
    }

    private suspend fun writeAndCheck(
        source: CsvRowSource,
        document: CsvDocument,
        request: SummaryPackageRequest,
        progress: (CsvExportProgress) -> Unit,
    ): CsvExport = try {
        var rowCount = 0L
        val written =
            document.write { output -> rowCount = writeArchive(source, output, request, progress) }
        val (size, sha) = document.read().use(::measure)
        if (size == written.sizeBytes && sha == written.sha256) {
            CsvExport.Saved(request.files.size + 1, rowCount)
        } else {
            log.warn(SummaryPackageEvent.CHECK_MISMATCH)
            CsvExport.Failed
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: IOException) {
        log.warn(SummaryPackageEvent.WRITE_FAILED, failure)
        if (isNoSpace(failure)) CsvExport.NoSpace else CsvExport.Failed
    } catch (failure: Exception) {
        // La base o un permiso perdido: solo la clase al registro, nunca un dato ni una ruta.
        log.warn(SummaryPackageEvent.FAILED, failure)
        CsvExport.Failed
    }

    /** Escribe el `.zip` en [output] y devuelve las filas de datos escritas, sin encabezados. */
    private suspend fun writeArchive(
        source: CsvRowSource,
        output: OutputStream,
        request: SummaryPackageRequest,
        progress: (CsvExportProgress) -> Unit,
    ): Long {
        val total = request.files.size + 1
        val zip = ZipOutputStream(output)
        zip.putNextEntry(ZipEntry(request.pdfName))
        File(request.pdfPath).inputStream().use { it.copyTo(zip, BUFFER_BYTES) }
        zip.closeEntry()
        progress(CsvExportProgress(1, total))
        // El escritor no cierra el .zip al terminar cada archivo: solo vacía lo pendiente.
        val writer = OutputStreamWriter(zip, Charsets.UTF_8).buffered(WRITER_BUFFER_CHARS)
        val naming = request.naming
        var rowCount = 0L
        source.snapshot { reader ->
            request.files.forEachIndexed { index, file ->
                val (name, columns) = when (file) {
                    is SummaryFile.Table -> naming.fileName(file.table) to file.table.columns

                    SummaryFile.ClosingInventory ->
                        naming.closingInventoryFileName() to SummaryFile.CLOSING_INVENTORY_COLUMNS
                }
                zip.putNextEntry(ZipEntry(name))
                writer.write(CsvRowWriter.BYTE_ORDER_MARK)
                writer.write(request.rows.header(columns.map(naming::header)))
                val each: (List<CsvCell>) -> Unit = { cells ->
                    writer.write(request.rows.row(cells))
                }
                rowCount += when (file) {
                    is SummaryFile.Table ->
                        reader.forEachRow(file.table, naming::seedName, request.range, each)

                    SummaryFile.ClosingInventory ->
                        reader.forEachClosingStock(request.range.to, naming::seedName, each)
                }
                writer.flush()
                zip.closeEntry()
                progress(CsvExportProgress(index + 2, total))
            }
        }
        zip.finish()
        zip.flush()
        return rowCount
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
    }
}

/**
 * Lo que había de cada producto al terminar un día: la suma de su libro de stock hasta esa fecha
 * de negocio, con su nombre y su unidad base. Solo suma en la base, una fila por producto con
 * movimientos, en el orden en que se crearon. Una fila del libro nunca se edita, así que la suma
 * da lo mismo aunque un movimiento se haya registrado tarde con una fecha anterior.
 */
internal object ClosingStockQuery {
    /** Con `?1`, el último día incluido, como `AAAA-MM-DD`. */
    const val SQL =
        """
        SELECT p.name, u.system_key, u.name, SUM(m.quantity_milli)
        FROM stock_movements m
        JOIN products p ON p.id = m.product_id
        LEFT JOIN units u ON u.id = p.base_unit_id
        WHERE m.business_date <= ?1
        GROUP BY m.product_id
        ORDER BY m.product_id
        """

    /** Las celdas de una fila, en el orden de las columnas del archivo. */
    fun cells(row: CsvRow): List<CsvCell> =
        listOf(row.text(), row.seed(SeedTable.UNITS), row.quantity())
}

/**
 * Un archivo propio de la app, como el paquete en la carpeta de lo que sale de ella: lo escribe
 * desde el principio, lo sincroniza y lo relee. Borrarlo nunca lanza.
 */
internal class FileCsvDocument(private val file: File) : CsvDocument {
    override suspend fun write(block: suspend (OutputStream) -> Unit): WrittenDocument {
        val folder = file.parentFile
        if (folder != null && !folder.isDirectory && !folder.mkdirs()) {
            throw IOException("Sin carpeta para el paquete")
        }
        return FileOutputStream(file).use { out ->
            val measured = MeasuringOutputStream(out.buffered(BUFFER_BYTES), newSha256())
            block(measured)
            measured.flush()
            out.fd.sync()
            WrittenDocument(measured.size, measured.digest.digest().toLowerHex())
        }
    }

    override fun read(): InputStream = file.inputStream()

    override fun discard() {
        try {
            file.delete()
        } catch (_: SecurityException) {
            // Sin permiso para borrarlo no queda nada que hacer: la carpeta se vacía al salir.
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

/** Lo que el resumen de gestión anota en el registro: motivos y clases, nunca datos ni rutas. */
internal enum class SummaryPackageEvent(override val text: String) : LogEvent {
    WRITE_FAILED("El resumen de gestión no se pudo escribir"),
    CHECK_MISMATCH("El resumen de gestión releído no coincide con lo escrito"),
    FAILED("El resumen de gestión falló"),
}
