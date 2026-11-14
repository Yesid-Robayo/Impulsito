package io.github.yesidrobayo.shopledger.ui.label

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.label.LabelTemplate
import io.github.yesidrobayo.shopledger.domain.rule.label.Code128
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelBarcodeFit
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelItem
import io.github.yesidrobayo.shopledger.domain.rule.print.MonochromeThreshold
import io.github.yesidrobayo.shopledger.ui.currency.CurrencySymbolFonts
import io.github.yesidrobayo.shopledger.ui.currency.moneyText
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Dibuja etiquetas: nombre en dos líneas como máximo, cortado con `…`; precio con el formato del
 * negocio; y el Code 128 con su texto debajo. Es papel, así que no sigue el tema de la app: todo
 * va en negro sobre blanco, y nunca lleva costo ni margen.
 *
 * Todo se mide en puntos de 203 ppp. Para una térmica, la imagen se pasa a blanco y negro puros
 * con [MonochromeThreshold], la misma regla que usa la impresión, así que la vista previa es lo
 * que sale. Para la hoja PDF, el mismo dibujo se escala a puntos de PDF y queda vectorial.
 */
class LabelRenderer(context: Context, private val formats: AppFormats) {
    private val resources = context.resources
    private val chain = CurrencySymbolFonts.chain(resources)
    private val figtree: Typeface =
        ResourcesCompat.getFont(context, R.font.figtree_variable) ?: Typeface.DEFAULT

    /** La etiqueta de [item] en [size], en blanco y negro puros, como sale en papel. */
    fun render(item: LabelItem, template: LabelTemplate, size: LabelSize): Bitmap {
        val label = Measured(item, template, size.dots, size.minimumModule)
        val bitmap = Bitmap.createBitmap(size.dots, label.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        label.draw(canvas)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (index in pixels.indices) {
            val dark = MonochromeThreshold.isDark(pixels[index])
            pixels[index] = if (dark) Color.BLACK else Color.WHITE
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return bitmap
    }

    /**
     * Escribe en [target] una hoja A4 con las etiquetas de [labels], cada una tantas veces como
     * sus copias, en [SHEET_COLUMNS] columnas. Devuelve `false` si no quedó escrita entera.
     */
    fun writeSheet(labels: List<LabelItem>, template: LabelTemplate, target: File): Boolean {
        val pdf = PdfDocument()
        return try {
            Sheet(pdf).apply {
                labels.forEach { item -> repeat(item.copies) { add(item, template) } }
                finish()
            }
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

    /** Las páginas de la hoja: abre una nueva cuando la etiqueta siguiente no cabe. */
    private inner class Sheet(private val pdf: PdfDocument) {
        private val scale = (PAGE_WIDTH - 2 * PAGE_MARGIN - (SHEET_COLUMNS - 1) * GUTTER) /
            SHEET_COLUMNS / LabelSize.SHEET_LABEL_DOTS
        private val columnWidth = LabelSize.SHEET_LABEL_DOTS * scale
        private var number = 0
        private var page: PdfDocument.Page? = null
        private var column = 0
        private var top = PAGE_MARGIN
        private var rowHeight = 0f

        fun add(item: LabelItem, template: LabelTemplate) {
            val sheet = LabelSize.SHEET
            val label = Measured(item, template, sheet.dots, sheet.minimumModule)
            val height = label.height * scale
            if (column == SHEET_COLUMNS) {
                column = 0
                top += rowHeight + GUTTER
                rowHeight = 0f
            }
            if (page == null || top + height > PAGE_HEIGHT - PAGE_MARGIN) {
                finishPage()
                number++
                page = pdf.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), number)
                        .create(),
                )
                column = 0
                top = PAGE_MARGIN
                rowHeight = 0f
            }
            val canvas = requireNotNull(page).canvas
            canvas.save()
            canvas.translate(PAGE_MARGIN + column * (columnWidth + GUTTER), top)
            canvas.scale(scale, scale)
            label.draw(canvas)
            canvas.restore()
            rowHeight = maxOf(rowHeight, height)
            column++
        }

        fun finish() = finishPage()

        private fun finishPage() {
            page?.let(pdf::finishPage)
            page = null
        }
    }

    /** Una etiqueta ya medida: sabe su alto antes de dibujarse. */
    private inner class Measured(
        item: LabelItem,
        template: LabelTemplate,
        private val width: Int,
        minimumModule: Int,
    ) {
        private val inner = width - 2 * PADDING
        private val name: StaticLayout? =
            if (template == LabelTemplate.CODE_ONLY) null else nameLayout(item.name, inner)
        private val price: String? = if (template.showsPrice) money(item) else null
        private val pricePaint = paint(PRICE_SIZE, BOLD).apply { fontFeatureSettings = TABULAR }
        private val code: String? = item.code?.takeIf { template.showsCode }
        private val symbol = code?.let(Code128::encode)
        private val module =
            symbol?.let { LabelBarcodeFit.moduleWidth(it.width, width, minimumModule) }
        private val codePaint = paint(CODE_SIZE, REGULAR).apply { textAlign = Paint.Align.CENTER }

        init {
            // Un monto nunca se corta: si no cabe, se achica hasta caber.
            if (price != null) {
                while (
                    pricePaint.measureText(price) > inner &&
                    pricePaint.textSize > MIN_PRICE_SIZE
                ) {
                    pricePaint.textSize -= 2f
                }
            }
        }

        val height: Int =
            PADDING +
                (name?.height?.plus(GAP) ?: 0) +
                (price?.let { lineHeight(pricePaint) + GAP } ?: 0) +
                (if (module != null) BAR_HEIGHT + GAP else 0) +
                (code?.let { lineHeight(codePaint) } ?: 0) +
                PADDING

        fun draw(canvas: Canvas) {
            var y = PADDING.toFloat()
            name?.let { layout ->
                canvas.save()
                canvas.translate(PADDING.toFloat(), y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + GAP
            }
            price?.let { text ->
                y += -pricePaint.fontMetrics.ascent
                canvas.drawText(text, PADDING.toFloat(), y, pricePaint)
                y += pricePaint.fontMetrics.descent + GAP
            }
            val bars = symbol
            val moduleWidth = module
            if (bars != null && moduleWidth != null) {
                val left = (width - bars.width * moduleWidth) / 2f
                val ink = Paint().apply { color = Color.BLACK }
                bars.modules.forEachIndexed { index, black ->
                    if (black) {
                        val x = left + index * moduleWidth
                        canvas.drawRect(x, y, x + moduleWidth, y + BAR_HEIGHT, ink)
                    }
                }
                y += BAR_HEIGHT + GAP
            }
            code?.let { text ->
                y += -codePaint.fontMetrics.ascent
                canvas.drawText(text, width / 2f, y, codePaint)
            }
        }
    }

    private fun nameLayout(text: String, width: Int): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint(NAME_SIZE, BOLD), width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setMaxLines(NAME_LINES)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()

    private fun money(item: LabelItem): String {
        val parts = formats.money.parts(item.price)
        return moneyText(parts, chain.resolve(parts.symbol, parts.currencyCode)).text
    }

    private fun lineHeight(paint: Paint): Int =
        (paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt() + 1

    private fun paint(size: Float, weight: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = figtree
        // Figtree es variable: el peso se elige en el eje, igual en Android 8 que en los nuevos.
        setFontVariationSettings("'wght' $weight")
    }

    private companion object {
        const val PADDING = 16
        const val GAP = 8
        const val NAME_SIZE = 26f
        const val NAME_LINES = 2
        const val PRICE_SIZE = 44f
        const val MIN_PRICE_SIZE = 20f
        const val CODE_SIZE = 22f

        /** 9 mm de barras a 203 ppp: una cámara las encuentra sin acercarse demasiado. */
        const val BAR_HEIGHT = 72
        const val REGULAR = 400
        const val BOLD = 600
        const val TABULAR = "'tnum'"

        /** A4 en puntos de PDF, con 10 mm de margen. */
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val PAGE_MARGIN = 28f
        const val GUTTER = 8f
        const val SHEET_COLUMNS = 3
    }
}
