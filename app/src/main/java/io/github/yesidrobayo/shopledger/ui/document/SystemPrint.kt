package io.github.yesidrobayo.shopledger.ui.document

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.rescue.ExportFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Escribe [document] como PDF en `cache/exports/` y abre con él el servicio de impresión del
 * sistema, con el título [title]. Al terminar el servicio, o si no se pudo abrir, la carpeta se
 * vacía. Devuelve `false` si no se pudo escribir el archivo o abrir el servicio: el documento
 * sigue registrado igual.
 */
internal suspend fun printPdfWithSystem(
    context: Context,
    activity: Activity?,
    renderer: ReceiptRenderer,
    formats: AppFormats,
    document: ReceiptDocument,
    title: String,
    assets: ReceiptAssets = ReceiptAssets(logo = null, units = emptyMap(), zone = null),
): Boolean {
    val folder = ExportFolder.of(context)
    val target = File(folder, SYSTEM_PRINT_FILE)
    val written = withContext(Dispatchers.IO) {
        ExportFolder.clear(context)
        (folder.isDirectory || folder.mkdirs()) && renderer.writePdf(document, assets, target)
    }
    val paper = PaperSize.of(formats.date.formatLocale)
    val opened = written && activity != null &&
        activity.openSystemPrint(target, title, paper) { ExportFolder.clear(context) }
    if (!opened) withContext(Dispatchers.IO) { ExportFolder.clear(context) }
    return opened
}

/** El nombre del PDF en `cache/exports/`: el mismo siempre, sin datos del negocio. */
private const val SYSTEM_PRINT_FILE = "document.pdf"

/**
 * Abre el servicio de impresión del sistema con el PDF [file], ya escrito, con el nombre [title].
 * Android elige la impresora: una de la red local o por USB, con el complemento que la persona
 * instaló, o `Guardar como PDF`. La app no habla con ninguna impresora ni con ninguna nube.
 *
 * [paper] es la hoja con que se escribió el PDF, para que el diálogo la proponga. [onFinished]
 * corre cuando el servicio termina con el archivo, se haya impreso o no. Devuelve `false` si el
 * sistema no tiene el servicio.
 */
internal fun Context.openSystemPrint(
    file: File,
    title: String,
    paper: PaperSize,
    onFinished: () -> Unit,
): Boolean {
    val manager = getSystemService(PrintManager::class.java) ?: return false
    val media = if (paper == PaperSize.A4) {
        PrintAttributes.MediaSize.ISO_A4
    } else {
        PrintAttributes.MediaSize.NA_LETTER
    }
    val attributes = PrintAttributes.Builder().setMediaSize(media).build()
    return try {
        // Por referencia: la regla del registro rechaza una llamada que se lee como la salida
        // estándar, y esta es la del servicio de impresión del sistema.
        val printWith = manager::print
        printWith(title, FilePrintAdapter(file, title, onFinished), attributes)
        true
    } catch (_: IllegalStateException) {
        false
    }
}

/**
 * Entrega al servicio de impresión un PDF ya escrito. No vuelve a dibujar: el PDF ya tiene su
 * hoja, y el servicio lo escala si la persona elige otro papel.
 */
internal class FilePrintAdapter(
    private val file: File,
    private val name: String,
    private val onFinished: () -> Unit,
) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (_: IOException) {
            callback.onWriteFailed(null)
        }
    }

    override fun onFinish() = onFinished()
}
