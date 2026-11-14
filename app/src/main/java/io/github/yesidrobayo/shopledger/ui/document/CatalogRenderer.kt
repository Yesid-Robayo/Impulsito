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
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogDocument
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogItem
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.theme.paletteOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Lo que un catálogo necesita además de sus datos: el logo y las fotos de producto ya leídas, por
 * id de imagen, y las unidades de medida por id. Una foto que falta no se dibuja: el producto sale
 * sin imagen, sin marca de error y sin relleno.
 */
data class CatalogAssets(
    val logo: Bitmap?,
    val photos: Map<Long, Bitmap>,
    val units: Map<Long, MeasureUnit>,
)

/**
 * Dibuja el catálogo compartible en PDF: logo y nombre del negocio, título, la fecha de los
 * precios y una fila por producto con foto si se eligió, nombre, unidad y precio. Las variantes
 * van sangradas bajo su producto, cada una con su precio. Cada página lleva al pie su número y la
 * leyenda de que no es un comprobante de venta ni una factura, que nada quita.
 *
 * Es papel: no sigue el tema. Tinta y filete salen de los colores claros de la paleta elegida, y
 * la hoja es la del formato regional. Solo dibuja [CatalogDocument], que no tiene costo, margen,
 * ganancia, proveedor ni stock. Se genera en el teléfono, sin red.
 */
class CatalogRenderer(context: Context, private val formats: AppFormats, palette: ColorPalette) {
    private val resources = context.resources
    private val money = DocumentMoney(resources, formats)
    private val figtree: Typeface =
        ResourcesCompat.getFont(context, R.font.figtree_variable) ?: Typeface.DEFAULT
    private val currencyMedium: Typeface =
        ResourcesCompat.getFont(context, R.font.currency_symbols_500) ?: Typeface.DEFAULT
    private val scheme = paletteOf(palette).lightScheme
    private val ink = scheme.onSurface.toArgb()
    private val muted = scheme.onSurfaceVariant.toArgb()
    private val accent = scheme.primary.toArgb()

    /** Cuántas páginas ocupa [document] con [assets]. */
    fun pageCount(document: CatalogDocument, assets: CatalogAssets): Int =
        PaperSize.of(formats.date.formatLocale).let { pagesOf(blocksOf(document, assets, it), it) }
            .size

    /**
     * Escribe [document] como PDF en [target]. Devuelve `false` si no quedó escrito entero, y
     * entonces no deja archivo.
     */
    fun writePdf(document: CatalogDocument, assets: CatalogAssets, target: File): Boolean {
        val paper = PaperSize.of(formats.date.formatLocale)
        val pages = pagesOf(blocksOf(document, assets, paper), paper)
        val pdf = PdfDocument()
        return try {
            pages.forEachIndexed { index, blocks ->
                val page = pdf.startPage(
                    PdfDocument.PageInfo.Builder(paper.width, paper.height, index + 1).create(),
                )
                drawPage(page.canvas, paper, blocks, index + 1, pages.size)
                pdf.finishPage(page)
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

    /** La primera página, sobre blanco, a [scale] píxeles por punto: la vista previa. */
    fun renderFirstPage(document: CatalogDocument, assets: CatalogAssets, scale: Float): Bitmap {
        val paper = PaperSize.of(formats.date.formatLocale)
        val pages = pagesOf(blocksOf(document, assets, paper), paper)
        val bitmap = Bitmap.createBitmap(
            ceil(paper.width * scale).toInt(),
            ceil(paper.height * scale).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        drawPage(canvas, paper, pages.firstOrNull().orEmpty(), 1, max(pages.size, 1))
        return bitmap
    }

    /**
     * Los textos del catálogo en el orden en que se dibujan, sin el pie. Es lo que leen las
     * pruebas para saber qué lleva el papel sin mirar píxeles.
     */
    internal fun textsOf(document: CatalogDocument, assets: CatalogAssets): List<String> =
        blocksOf(document, assets, PaperSize.of(formats.date.formatLocale)).mapNotNull { it.text }

    /** Reparte los bloques en páginas: uno que no cabe abre la siguiente. */
    private fun pagesOf(blocks: List<Block>, paper: PaperSize): List<List<Block>> {
        val room = paper.height - 2 * MARGIN - FOOTER_SPACE
        val pages = mutableListOf<MutableList<Block>>()
        var used = Float.MAX_VALUE
        for (block in blocks) {
            if (used + block.height > room) {
                pages += mutableListOf<Block>()
                used = 0f
            }
            pages.last() += block
            used += block.height
        }
        return pages.ifEmpty { listOf(mutableListOf()) }
    }

    private fun drawPage(
        canvas: Canvas,
        paper: PaperSize,
        blocks: List<Block>,
        number: Int,
        count: Int,
    ) {
        val width = paper.width - 2 * MARGIN
        var top = MARGIN.toFloat()
        canvas.save()
        canvas.translate(MARGIN.toFloat(), 0f)
        for (block in blocks) {
            block.draw(canvas, top)
            top += block.height
        }
        // Al pie de cada página: la leyenda y el número, siempre.
        val footerTop = (paper.height - MARGIN - FOOTER_SPACE).toFloat() + FOOTER_GAP
        val legend = layout(
            resources.getString(R.string.catalog_doc_legend),
            textPaint(LEGEND_SIZE, REGULAR, muted),
            width,
            Layout.Alignment.ALIGN_NORMAL,
        )
        canvas.save()
        canvas.translate(0f, footerTop)
        legend.draw(canvas)
        canvas.restore()
        val pagePaint = textPaint(LEGEND_SIZE, REGULAR, muted)
        val pageText = resources.getString(
            R.string.catalog_doc_page,
            formats.quantity.format(QuantityMilli.ofUnits(number.toLong()), false),
            formats.quantity.format(QuantityMilli.ofUnits(count.toLong()), false),
        )
        val baseline = footerTop + legend.height + LEGEND_SIZE + FOOTER_GAP
        canvas.drawText(pageText, width - pagePaint.measureText(pageText), baseline, pagePaint)
        canvas.restore()
    }

    /** Los bloques del catálogo, de arriba abajo, para el ancho útil de [paper]. */
    private fun blocksOf(
        document: CatalogDocument,
        assets: CatalogAssets,
        paper: PaperSize,
    ): List<Block> = buildList {
        val width = paper.width - 2 * MARGIN
        assets.logo?.let { add(LogoBlock(it)) }
        document.header.businessName?.let {
            add(TextBlock(it, width, NAME_SIZE, BOLD, ink, GAP))
        }
        val title = document.title ?: resources.getString(R.string.catalog_default_title)
        add(TextBlock(title, width, TITLE_SIZE, BOLD, ink, GAP))
        val date = formats.date.dayMonthYear(document.pricesOn)
        val prices = resources.getString(R.string.catalog_doc_prices_date, date)
        add(TextBlock(prices, width, BODY_SIZE, REGULAR, muted, GAP))
        add(RuleBlock(accent, width, SECTION_GAP))
        document.sections.forEach { section ->
            section.categoryName?.let {
                add(TextBlock(it, width, SECTION_SIZE, BOLD, ink, SECTION_GAP))
            }
            section.items.forEach { item -> addAll(itemBlocks(item, assets, width)) }
        }
    }

    private fun itemBlocks(item: CatalogItem, assets: CatalogAssets, width: Int): List<Block> {
        val photo = item.photoMediaId?.let { assets.photos[it] }
        val unit = unitOf(assets.units[item.unitId])
        val head = ItemBlock(item.name, unit, item.price, photo, 0f, width)
        val variants = item.variants.map { variant ->
            ItemBlock(variant.label, null, variant.price, null, VARIANT_INDENT, width)
        }
        return listOf(head) + variants
    }

    /** El símbolo de la unidad, como en pantalla: `kg`, o el nombre de una unidad propia. */
    private fun unitOf(unit: MeasureUnit?): String? = unit?.let {
        SeedText.unitSymbol(resources, it.systemKey, it.symbol)
            ?: SeedText.displayName(resources, SeedTable.UNITS, it.systemKey, it.name)
    }

    private fun layout(text: String, paint: TextPaint, width: Int, align: Layout.Alignment) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(width, 1))
            .setAlignment(align)
            .setMaxLines(MAX_TEXT_LINES)
            .setEllipsize(TextUtils.TruncateAt.END)
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

    private fun moneyPaint(font: DocumentFont): Paint = when (font) {
        DocumentFont.APP -> textPaint(BODY_SIZE, MEDIUM, ink).apply {
            fontFeatureSettings = TABULAR
        }

        DocumentFont.CURRENCY -> TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = BODY_SIZE
            typeface = currencyMedium
        }

        DocumentFont.SYSTEM -> TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = BODY_SIZE
            typeface = Typeface.DEFAULT
        }
    }

    /** Un trozo del catálogo que sabe su alto antes de dibujarse. */
    private interface Block {
        val height: Float
        val text: String?

        fun draw(canvas: Canvas, top: Float)
    }

    /** Un párrafo de dos líneas como máximo, con [before] de aire encima. */
    private inner class TextBlock(
        override val text: String,
        width: Int,
        size: Float,
        weight: Int,
        color: Int,
        private val before: Float,
    ) : Block {
        private val drawn =
            layout(text, textPaint(size, weight, color), width, Layout.Alignment.ALIGN_NORMAL)
        override val height: Float = before + drawn.height

        override fun draw(canvas: Canvas, top: Float) {
            canvas.save()
            canvas.translate(0f, top + before)
            drawn.draw(canvas)
            canvas.restore()
        }
    }

    /** El filete del encabezado, de lado a lado. */
    private class RuleBlock(color: Int, private val width: Int, private val before: Float) :
        Block {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = HEADER_RULE
        }
        override val height: Float = before + HEADER_RULE + GAP
        override val text: String? = null

        override fun draw(canvas: Canvas, top: Float) {
            val y = top + before + HEADER_RULE / 2
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
    }

    /** El logo, a la izquierda, sin pasar de [LOGO_MAX] de ancho ni de alto. */
    private class LogoBlock(private val logo: Bitmap) : Block {
        private val fit = min(LOGO_MAX / logo.width, LOGO_MAX / logo.height)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override val height: Float = logo.height * fit + GAP
        override val text: String? = null

        override fun draw(canvas: Canvas, top: Float) {
            val rect = RectF(0f, top, logo.width * fit, top + logo.height * fit)
            canvas.drawBitmap(logo, null, rect, paint)
        }
    }

    /**
     * Una fila de producto o de variante: foto si la hay, nombre en dos líneas como máximo, la
     * unidad debajo y el precio a la derecha, con su símbolo en la fuente que lo tiene.
     */
    private inner class ItemBlock(
        private val name: String,
        private val unit: String?,
        price: Money?,
        private val photo: Bitmap?,
        private val indent: Float,
        private val width: Int,
    ) : Block {
        private val runs = price?.let { amount ->
            money.runsOf(amount).map { it.text to moneyPaint(it.font) }
        }.orEmpty()
        private val priceWidth = runs.fold(0f) { sum, (text, paint) ->
            sum + paint.measureText(text)
        }
        private val namePaint = textPaint(BODY_SIZE, if (indent > 0f) REGULAR else BOLD, ink)
        private val unitPaint = textPaint(SMALL_SIZE, REGULAR, muted)
        private val textLeft = indent + if (photo != null) PHOTO_SIZE + COLUMN_GAP else 0f
        private val nameLayout = layout(
            name,
            namePaint,
            (width - textLeft - priceWidth - COLUMN_GAP).toInt(),
            Layout.Alignment.ALIGN_NORMAL,
        )
        private val textHeight =
            nameLayout.height + (if (unit != null) unitPaint.fontSpacing else 0f)
        override val height: Float =
            ROW_GAP + max(textHeight, if (photo != null) PHOTO_SIZE else 0f)
        override val text: String = name + TEXT_SEPARATOR + runs.joinToString("") { it.first }

        override fun draw(canvas: Canvas, top: Float) {
            photo?.let {
                val fit = min(PHOTO_SIZE / it.width, PHOTO_SIZE / it.height)
                val y = top + ROW_GAP
                val rect = RectF(indent, y, indent + it.width * fit, y + it.height * fit)
                canvas.drawBitmap(it, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            canvas.save()
            canvas.translate(textLeft, top + ROW_GAP)
            nameLayout.draw(canvas)
            canvas.restore()
            unit?.let {
                val baseline = top + ROW_GAP + nameLayout.height - unitPaint.fontMetrics.ascent
                canvas.drawText(it, textLeft, baseline, unitPaint)
            }
            val baseline = top + ROW_GAP + nameLayout.getLineBaseline(0)
            var x = width - priceWidth
            for ((text, paint) in runs) {
                canvas.drawText(text, x, baseline, paint)
                x += paint.measureText(text)
            }
        }
    }

    private companion object {
        const val MARGIN = 40
        const val FOOTER_SPACE = 40
        const val FOOTER_GAP = 6f
        const val NAME_SIZE = 16f
        const val TITLE_SIZE = 20f
        const val SECTION_SIZE = 12f
        const val BODY_SIZE = 10f
        const val SMALL_SIZE = 9f

        /** La leyenda nunca baja de 8 puntos: tiene que leerse en papel. */
        const val LEGEND_SIZE = 8f
        const val REGULAR = 400
        const val MEDIUM = 500
        const val BOLD = 600
        const val TABULAR = "'tnum'"
        const val GAP = 4f
        const val ROW_GAP = 6f
        const val SECTION_GAP = 12f
        const val HEADER_RULE = 1f
        const val COLUMN_GAP = 12f
        const val VARIANT_INDENT = 16f
        const val PHOTO_SIZE = 40f
        const val LOGO_MAX = 64f
        const val MAX_TEXT_LINES = 2
        const val TEXT_SEPARATOR = "\t"
    }
}
