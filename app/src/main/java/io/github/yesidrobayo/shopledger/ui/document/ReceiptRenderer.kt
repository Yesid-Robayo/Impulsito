package io.github.yesidrobayo.shopledger.ui.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptKind
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptLine
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.sale.saleQuantityText
import io.github.yesidrobayo.shopledger.ui.theme.paletteOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Lo que un documento necesita además de sus datos: la imagen del logo, ya leída, las unidades de
 * medida por id y la zona del negocio, para la hora. Sin zona, el documento lleva solo la fecha.
 */
data class ReceiptAssets(
    val logo: Bitmap?,
    val units: Map<Long, MeasureUnit>,
    val zone: BusinessTimeZone?,
)

/**
 * Dibuja comprobantes en PDF y en imagen, con el mismo motor que muestra la vista previa: lo que
 * se ve es lo que sale.
 *
 * Es papel: no sigue el tema de la app. La tinta y el filete del encabezado salen de los colores
 * claros de la paleta elegida, sobre el blanco del papel, y un documento generado en modo oscuro
 * se ve igual. Todo se mide en puntos de PDF; la imagen es la misma página al doble.
 *
 * Solo dibuja [ReceiptDocument], que no tiene costo, margen, ganancia, comisión ni notas internas.
 * La leyenda de que no es una factura la pone siempre este motor, al pie, y nada la quita.
 *
 * Los textos salen de los recursos en el idioma de la interfaz, y los números del formato del
 * negocio. Cada monto se dibuja por tramos, con la fuente que tiene su símbolo y las cifras
 * tabulares alineadas a la derecha.
 */
class ReceiptRenderer(context: Context, private val formats: AppFormats, palette: ColorPalette) {
    private val resources = context.resources
    private val money = DocumentMoney(resources, formats)
    private val figtree: Typeface =
        ResourcesCompat.getFont(context, R.font.figtree_variable) ?: Typeface.DEFAULT
    private val currencyMedium: Typeface =
        ResourcesCompat.getFont(context, R.font.currency_symbols_500) ?: Typeface.DEFAULT
    private val currencyBold: Typeface =
        ResourcesCompat.getFont(context, R.font.currency_symbols_600) ?: Typeface.DEFAULT_BOLD
    private val scheme = paletteOf(palette).lightScheme
    private val ink = scheme.onSurface.toArgb()
    private val muted = scheme.onSurfaceVariant.toArgb()
    private val accent = scheme.primary.toArgb()

    /**
     * Escribe [document] como PDF en [target], en la hoja del formato regional, en tantas páginas
     * como haga falta. Devuelve `false` si no quedó escrito entero, y entonces no deja archivo.
     */
    fun writePdf(document: ReceiptDocument, assets: ReceiptAssets, target: File): Boolean {
        val paper = PaperSize.of(formats.date.formatLocale)
        val column = min(COLUMN_WIDTH, paper.width - 2 * PAGE_MARGIN)
        val left = (paper.width - column) / 2f
        val blocks = blocksOf(document, assets, column)
        val pdf = PdfDocument()
        return try {
            var number = 0
            var page: PdfDocument.Page? = null
            var top = 0f
            for (block in blocks) {
                val current = page
                if (current == null || top + block.height > paper.height - PAGE_MARGIN) {
                    current?.let(pdf::finishPage)
                    number++
                    page = pdf.startPage(
                        PdfDocument.PageInfo.Builder(paper.width, paper.height, number).create(),
                    )
                    top = PAGE_MARGIN.toFloat()
                }
                val canvas = requireNotNull(page).canvas
                canvas.save()
                canvas.translate(left, 0f)
                block.draw(canvas, top)
                canvas.restore()
                top += block.height
            }
            page?.let(pdf::finishPage)
            FileOutputStream(target).use { out ->
                pdf.writeTo(out)
                out.flush()
                out.fd.sync()
            }
            true
        } catch (_: IOException) {
            target.delete()
            false
        } finally {
            pdf.close()
        }
    }

    /**
     * [document] como una sola imagen larga, sobre blanco: la columna del PDF con su margen, a
     * [scale] píxeles por punto. El doble, el valor por defecto, es la imagen que se comparte.
     */
    fun renderImage(
        document: ReceiptDocument,
        assets: ReceiptAssets,
        scale: Float = IMAGE_SCALE,
    ): Bitmap {
        val blocks = blocksOf(document, assets, COLUMN_WIDTH)
        val height = blocks.fold(0f) { sum, block -> sum + block.height } + 2 * IMAGE_MARGIN
        val width = COLUMN_WIDTH + 2 * IMAGE_MARGIN
        val bitmap = Bitmap.createBitmap(
            ceil(width * scale).toInt(),
            ceil(height * scale).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        canvas.translate(IMAGE_MARGIN, IMAGE_MARGIN)
        var top = 0f
        for (block in blocks) {
            block.draw(canvas, top)
            top += block.height
        }
        return bitmap
    }

    /**
     * Los textos del documento en el orden en que se dibujan, cada fila con su nombre y su monto.
     * Es lo que leen las pruebas para saber qué lleva el papel sin mirar píxeles.
     */
    internal fun textsOf(document: ReceiptDocument, assets: ReceiptAssets): List<String> =
        blocksOf(document, assets, COLUMN_WIDTH).mapNotNull { it.text }

    /** Los bloques del documento, de arriba abajo, para una columna de [width] puntos. */
    private fun blocksOf(
        document: ReceiptDocument,
        assets: ReceiptAssets,
        width: Int,
    ): List<Block> = buildList {
        val header = document.header
        assets.logo?.let { add(LogoBlock(it, width)) }
        header.businessName?.let { add(centered(it, width, NAME_SIZE, BOLD, ink, GAP)) }
        header.businessDetails?.let { add(centered(it, width, BODY_SIZE, REGULAR, muted, GAP)) }
        if (isNotEmpty()) add(RuleBlock(width, accent, HEADER_RULE, SECTION_GAP))
        add(centered(titleOf(document.kind), width, TITLE_SIZE, BOLD, ink, SECTION_GAP))
        add(centered(numberOf(document), width, BODY_SIZE, REGULAR, ink, GAP))
        add(centered(whenOf(document, assets.zone), width, BODY_SIZE, REGULAR, muted, SMALL_GAP))
        document.customerName?.let {
            val customer = resources.getString(R.string.receipt_doc_customer, it)
            add(centered(customer, width, BODY_SIZE, REGULAR, muted, SMALL_GAP))
        }
        add(RuleBlock(width, muted, THIN_RULE, SECTION_GAP))
        document.lines.forEach { add(lineRow(it, assets.units, width)) }
        add(RuleBlock(width, muted, THIN_RULE, SECTION_GAP))
        document.amounts.forEach { add(amountRow(it, width)) }
        document.footerText?.let { add(centered(it, width, BODY_SIZE, REGULAR, ink, SECTION_GAP)) }
        val legend = resources.getString(R.string.legal_receipt_not_invoice)
        add(centered(legend, width, LEGEND_SIZE, REGULAR, muted, SECTION_GAP))
    }

    private fun titleOf(kind: ReceiptKind): String = when (kind) {
        ReceiptKind.SALE -> resources.getString(R.string.receipt_doc_title)
    }

    private fun numberOf(document: ReceiptDocument): String {
        val digits = formats.quantity.format(QuantityMilli.ofUnits(document.number), false)
        val number = when (document.kind) {
            ReceiptKind.SALE -> resources.getString(R.string.receipt_doc_sale_number, digits)
        }
        if (!document.voided) return number
        val status = resources.getString(R.string.receipt_doc_voided)
        return resources.getString(R.string.receipt_doc_number_status, number, status)
    }

    private fun whenOf(document: ReceiptDocument, zone: BusinessTimeZone?): String {
        val date = formats.date.dayMonthYear(document.businessDate)
        zone ?: return date
        val time = formats.date.time(document.occurredAt, zone)
        return resources.getString(R.string.receipt_doc_date_time, date, time)
    }

    private fun lineRow(line: ReceiptLine, units: Map<Long, MeasureUnit>, width: Int): Block {
        val quantity = saleQuantityText(line.quantity, units[line.unitId], resources, formats)
        val label = resources.getString(R.string.receipt_doc_line, quantity, line.productName)
        val indent = if (line.extra) EXTRA_INDENT else 0f
        return RowBlock(label, line.amount, width, indent, BODY_SIZE, MEDIUM, ink, ROW_GAP)
    }

    private fun amountRow(row: ReceiptAmount, width: Int): Block {
        val label = when (row) {
            is ReceiptAmount.Subtotal -> resources.getString(R.string.receipt_doc_subtotal)

            is ReceiptAmount.Adjustment -> resources.getString(
                if (row.amount < Money.ZERO) {
                    R.string.receipt_doc_discount
                } else {
                    R.string.receipt_doc_surcharge
                },
            )

            is ReceiptAmount.Tax -> {
                val name = row.name ?: resources.getString(R.string.receipt_doc_tax)
                if (row.included) {
                    resources.getString(R.string.receipt_doc_tax_included, name)
                } else {
                    name
                }
            }

            is ReceiptAmount.Rounding -> resources.getString(R.string.receipt_doc_rounding)

            is ReceiptAmount.Tip -> resources.getString(R.string.receipt_doc_tip)

            is ReceiptAmount.Total -> resources.getString(R.string.receipt_doc_total)

            is ReceiptAmount.Payment -> row.methodName

            is ReceiptAmount.Change -> resources.getString(R.string.receipt_doc_change)

            is ReceiptAmount.BalanceDue -> resources.getString(R.string.receipt_doc_balance_due)

            is ReceiptAmount.Return -> {
                val number = formats.quantity.format(QuantityMilli.ofUnits(row.number), false)
                resources.getString(R.string.receipt_doc_return, number)
            }
        }
        return when (row) {
            is ReceiptAmount.Total ->
                RowBlock(label, row.amount, width, 0f, TOTAL_SIZE, BOLD, ink, TOTAL_GAP)

            is ReceiptAmount.Tax ->
                if (row.included) {
                    RowBlock(label, row.amount, width, 0f, BODY_SIZE, MEDIUM, muted, ROW_GAP)
                } else {
                    RowBlock(label, row.amount, width, 0f, BODY_SIZE, MEDIUM, ink, ROW_GAP)
                }

            is ReceiptAmount.Subtotal, is ReceiptAmount.Adjustment, is ReceiptAmount.Rounding,
            is ReceiptAmount.Tip, is ReceiptAmount.Payment, is ReceiptAmount.Change,
            is ReceiptAmount.BalanceDue, is ReceiptAmount.Return,
            -> RowBlock(label, row.amount, width, 0f, BODY_SIZE, MEDIUM, ink, ROW_GAP)
        }
    }

    private fun centered(
        text: String,
        width: Int,
        size: Float,
        weight: Int,
        color: Int,
        before: Float,
    ): Block = TextBlock(layout(text, textPaint(size, weight, color), width, CENTER), before)

    private fun layout(text: String, paint: TextPaint, width: Int, align: Layout.Alignment) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(width, 1))
            .setAlignment(align)
            .build()

    private fun textPaint(size: Float, weight: Int, color: Int) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = figtree
            // Figtree es variable: el peso se elige en el eje, igual en Android 8 que en los
            // nuevos.
            setFontVariationSettings("'wght' $weight")
        }

    /** El pincel de un tramo de monto, con la fuente que tiene sus caracteres. */
    private fun moneyPaint(font: DocumentFont, size: Float, weight: Int, color: Int): Paint =
        when (font) {
            DocumentFont.APP -> textPaint(size, weight, color).apply {
                fontFeatureSettings = TABULAR
            }

            DocumentFont.CURRENCY -> TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                typeface = if (weight >= BOLD) currencyBold else currencyMedium
            }

            DocumentFont.SYSTEM -> TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                typeface = if (weight >= BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }

    /** Un trozo del documento que sabe su alto antes de dibujarse. */
    private interface Block {
        val height: Float

        /** Lo que dice el bloque, o `null` si no lleva texto. */
        val text: String?

        fun draw(canvas: Canvas, top: Float)
    }

    /** Un párrafo, con [before] de aire encima. */
    private class TextBlock(private val layout: StaticLayout, private val before: Float) : Block {
        override val height: Float = before + layout.height
        override val text: String = layout.text.toString()

        override fun draw(canvas: Canvas, top: Float) {
            canvas.save()
            canvas.translate(0f, top + before)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    /** Un filete de lado a lado, con [before] de aire encima y [AFTER_RULE] debajo. */
    private class RuleBlock(
        private val width: Int,
        color: Int,
        stroke: Float,
        private val before: Float,
    ) : Block {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = stroke
        }
        override val height: Float = before + stroke + AFTER_RULE
        override val text: String? = null

        override fun draw(canvas: Canvas, top: Float) {
            val y = top + before + paint.strokeWidth / 2
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
    }

    /** El logo, centrado, sin pasar de [LOGO_MAX] de ancho ni de alto, sin deformarlo. */
    private class LogoBlock(private val logo: Bitmap, private val width: Int) : Block {
        private val fit = min(LOGO_MAX / logo.width, LOGO_MAX / logo.height)
        private val drawnWidth = logo.width * fit
        private val drawnHeight = logo.height * fit
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override val height: Float = drawnHeight
        override val text: String? = null

        override fun draw(canvas: Canvas, top: Float) {
            val left = (width - drawnWidth) / 2
            canvas.drawBitmap(logo, null, RectF(left, top, left + drawnWidth, top + height), paint)
        }
    }

    /**
     * Una fila con su nombre a la izquierda, que se parte en varias líneas si no cabe, y su monto
     * a la derecha, en una sola línea y sobre la línea base de la primera.
     */
    private inner class RowBlock(
        label: String,
        amount: Money,
        private val width: Int,
        private val indent: Float,
        size: Float,
        weight: Int,
        color: Int,
        private val before: Float,
    ) : Block {
        private val runs = money.runsOf(amount).map {
            it.text to moneyPaint(it.font, size, weight, color)
        }
        private val amountWidth = runs.fold(0f) { sum, (text, paint) ->
            sum + paint.measureText(text)
        }
        private val labelLayout = layout(
            label,
            textPaint(size, if (weight >= BOLD) BOLD else REGULAR, color),
            (width - indent - amountWidth - COLUMN_GAP).toInt(),
            Layout.Alignment.ALIGN_NORMAL,
        )
        private val amountHeight = runs.maxOf { (_, paint) ->
            paint.fontMetrics.let { it.descent - it.ascent }
        }
        override val height: Float = before + max(labelLayout.height.toFloat(), amountHeight)
        override val text: String = label + TEXT_SEPARATOR + runs.joinToString("") { it.first }

        override fun draw(canvas: Canvas, top: Float) {
            canvas.save()
            canvas.translate(indent, top + before)
            labelLayout.draw(canvas)
            canvas.restore()
            val baseline = top + before + labelLayout.getLineBaseline(0)
            var x = width - amountWidth
            for ((text, paint) in runs) {
                canvas.drawText(text, x, baseline, paint)
                x += paint.measureText(text)
            }
        }
    }

    private companion object {
        /** El ancho de la columna del documento: el de un comprobante, centrado en la hoja. */
        const val COLUMN_WIDTH = 360
        const val PAGE_MARGIN = 48
        const val IMAGE_MARGIN = 24f
        const val IMAGE_SCALE = 2f

        const val NAME_SIZE = 16f
        const val TITLE_SIZE = 12f
        const val BODY_SIZE = 10f
        const val TOTAL_SIZE = 14f

        /** La leyenda nunca baja de 8 puntos: tiene que leerse en papel. */
        const val LEGEND_SIZE = 8f
        const val REGULAR = 400
        const val MEDIUM = 500
        const val BOLD = 600
        const val TABULAR = "'tnum'"

        /** Entre el nombre y el monto de una fila, en el texto que leen las pruebas. */
        const val TEXT_SEPARATOR = "\t"

        const val SMALL_GAP = 2f
        const val GAP = 4f
        const val ROW_GAP = 4f
        const val TOTAL_GAP = 8f
        const val SECTION_GAP = 10f
        const val AFTER_RULE = 2f
        const val HEADER_RULE = 1f
        const val THIN_RULE = 0.5f
        const val COLUMN_GAP = 12f
        const val EXTRA_INDENT = 12f
        const val LOGO_MAX = 96f
        val CENTER = Layout.Alignment.ALIGN_CENTER
    }
}
