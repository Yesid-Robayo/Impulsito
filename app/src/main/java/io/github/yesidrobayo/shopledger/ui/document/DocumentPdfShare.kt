package io.github.yesidrobayo.shopledger.ui.document

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptKind
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.receipt.decodeStored
import io.github.yesidrobayo.shopledger.ui.rescue.ExportFolder
import io.github.yesidrobayo.shopledger.ui.rescue.exportFile
import io.github.yesidrobayo.shopledger.ui.rescue.pdfShareIntent
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Un documento para compartir como PDF: lo que dibuja el motor y lo que necesita además, el logo
 * de la plantilla, las unidades por id y la zona del negocio. [attempt] distingue dos pedidos seguidos del mismo
 * documento.
 */
data class DocumentShareRequest(
    val document: ReceiptDocument,
    val logo: StoredImage?,
    val units: Map<Long, MeasureUnit>,
    val zone: BusinessTimeZone?,
    val attempt: Long,
)

/**
 * Cuando llega [request], escribe el documento como PDF en `cache/exports/` con el mismo motor del
 * comprobante y abre el selector del sistema, con permiso solo de lectura: otra app hace el envío,
 * solo porque la persona lo tocó. El archivo se llama como el título del documento, con su número
 * y nada más del negocio. Al volver del selector, la carpeta se vacía. [onFinished] dice si el
 * selector llegó a abrirse.
 */
@Composable
fun ShareDocumentPdf(request: DocumentShareRequest?, onFinished: (Boolean) -> Unit) {
    val context = LocalContext.current
    val formats = LocalAppFormats.current
    val palette = AppTheme.palette
    val scope = rememberCoroutineScope()
    val chooser =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            scope.launch { withContext(Dispatchers.IO) { ExportFolder.clear(context) } }
        }
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        val document = request.document
        val digits = formats.quantity.format(QuantityMilli.ofUnits(document.number), false)
        val title = when (document.kind) {
            ReceiptKind.SALE -> R.string.receipt_doc_title
            ReceiptKind.QUOTE -> R.string.quote_doc_title
            ReceiptKind.ORDER -> R.string.order_doc_title
        }
        val name = context.resources.getString(title, digits) + PDF_EXTENSION
        val renderer = ReceiptRenderer(context, formats, palette)
        val uri = withContext(Dispatchers.IO) {
            exportFile(context, name) { target ->
                // Sin logo, o si no se puede leer, el documento sale igual.
                val logo = request.logo?.let { decodeStored(context, it) }
                renderer.writePdf(
                    document,
                    ReceiptAssets(logo, request.units, request.zone),
                    target,
                )
            }
        }
        val shared = uri != null && try {
            chooser.launch(pdfShareIntent(uri))
            true
        } catch (_: ActivityNotFoundException) {
            withContext(Dispatchers.IO) { ExportFolder.clear(context) }
            false
        }
        onFinished(shared)
    }
}

/** Para que el selector y la app que recibe sepan qué es. */
private const val PDF_EXTENSION = ".pdf"
