package io.github.yesidrobayo.shopledger.ui.encryption

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.yesidrobayo.shopledger.R
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * El kit de recuperación: un PDF de una página con el código en 7 grupos, la fecha, cómo usarlo y,
 * si ya está publicada, la dirección del formato público y la herramienta de rescate.
 *
 * Es el único lugar por donde el código sale de la app, y siempre por decisión de la persona, al
 * documento que ella eligió con el selector del sistema. Nunca pasa por el portapapeles, un
 * `Intent` ni un archivo temporal: el PDF se arma en memoria y se escribe directo en el documento.
 * Si algo falla después de empezar a escribir, el documento se borra: un kit a medias podría
 * parecer bueno y no servir.
 *
 * Los textos salen de [context], que ya está en el idioma de la app.
 */
class RecoveryKitWriter(private val context: Context) {
    /** Escribe el kit en [uri]. Devuelve `false` si no quedó escrito entero. */
    fun write(uri: String, groups: List<String>, createdDate: String): Boolean {
        val target = Uri.parse(uri)
        val document = PdfDocument()
        return try {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create(),
            )
            draw(page.canvas, groups, createdDate)
            document.finishPage(page)
            val written =
                context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                    document.writeTo(out)
                    out.flush()
                    (out as? FileOutputStream)?.fd?.sync()
                    true
                } ?: false
            if (!written) delete(target)
            written
        } catch (_: IOException) {
            delete(target)
            false
        } catch (_: SecurityException) {
            delete(target)
            false
        } finally {
            document.close()
        }
    }

    private fun draw(canvas: android.graphics.Canvas, groups: List<String>, createdDate: String) {
        val appName = context.getString(R.string.app_name)
        var y = MARGIN.toFloat()
        y =
            text(
                canvas,
                context.getString(R.string.recovery_kit_title, appName),
                y,
                TITLE_SIZE,
                bold = true,
            )
        y =
            text(
                canvas,
                context.getString(R.string.recovery_kit_created, createdDate),
                y + GAP_SMALL,
                BODY_SIZE,
            )
        y = code(canvas, groups, y + GAP_LARGE)
        y =
            text(
                canvas,
                context.getString(R.string.recovery_kit_howto_title),
                y + GAP_LARGE,
                HEADING_SIZE,
                bold = true,
            )
        y =
            text(
                canvas,
                context.getString(R.string.recovery_kit_howto_body, appName),
                y + GAP_SMALL,
                BODY_SIZE,
            )
        y =
            text(
                canvas,
                context.getString(R.string.recovery_kit_keep_body),
                y + GAP_SMALL,
                BODY_SIZE,
            )
        val url = context.getString(R.string.rescue_tool_url)
        // Sin dirección publicada, la línea no va: un enlace vacío no ayuda a nadie.
        if (url.isNotBlank()) {
            text(
                canvas,
                context.getString(R.string.recovery_kit_format, url),
                y + GAP_SMALL,
                BODY_SIZE,
            )
        }
    }

    /** El código, cuatro grupos por línea, en letra de ancho fijo para no confundir caracteres. */
    private fun code(canvas: android.graphics.Canvas, groups: List<String>, top: Float): Float {
        val paint =
            TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = CODE_SIZE
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
        var y = top
        groups.chunked(GROUPS_PER_LINE).forEach { line ->
            y += CODE_SIZE
            canvas.drawText(line.joinToString("   "), MARGIN.toFloat(), y, paint)
            y += CODE_LINE_GAP
        }
        return y
    }

    /** Un párrafo que se parte al ancho de la página. Devuelve dónde termina. */
    private fun text(
        canvas: android.graphics.Canvas,
        value: String,
        top: Float,
        size: Float,
        bold: Boolean = false,
    ): Float {
        val paint =
            TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = size
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        val width = PAGE_WIDTH - 2 * MARGIN
        val layout =
            StaticLayout.Builder
                .obtain(value, 0, value.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
        canvas.save()
        canvas.translate(MARGIN.toFloat(), top)
        layout.draw(canvas)
        canvas.restore()
        return top + layout.height
    }

    private fun delete(target: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, target)
        } catch (_: FileNotFoundException) {
            Unit
        } catch (_: SecurityException) {
            Unit
        } catch (_: IllegalArgumentException) {
            // No es un documento del selector: no hay nada que borrar por este camino.
            Unit
        }
    }

    private companion object {
        /** A4 en puntos de PDF. */
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 56
        const val TITLE_SIZE = 22f
        const val HEADING_SIZE = 15f
        const val BODY_SIZE = 12f
        const val CODE_SIZE = 26f
        const val CODE_LINE_GAP = 14f
        const val GAP_SMALL = 8f
        const val GAP_LARGE = 24f
        const val GROUPS_PER_LINE = 4
    }
}
