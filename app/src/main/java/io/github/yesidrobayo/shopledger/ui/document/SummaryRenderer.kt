package io.github.yesidrobayo.shopledger.ui.document

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.theme.paletteOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max

/**
 * Lo que el PDF del resumen de gestión dibuja: las cifras del período, el nombre del negocio que
 * lo encabeza, si lo tiene, y el día en que se creó.
 *
 * @property sampleData si sale de la demostración: el PDF empieza con `Datos de ejemplo`, para
 *   que nunca se confunda con el resumen de un negocio real.
 */
data class SummaryDocument(
    val summary: ManagementSummary,
    val businessName: String?,
    val createdOn: BusinessDate,
    val sampleData: Boolean = false,
)

/**
 * Dibuja el PDF del `Resumen de gestión`: nombre del negocio, título, período y fecha de creación;
 * después ventas, impuesto estimado si el negocio cobra impuesto, gastos, ganancias y cada método
 * de pago. Cada página lleva al pie la leyenda de que la app no reemplaza la contabilidad oficial
 * y de que el resumen no es una factura, un libro contable ni una declaración, y su número.
 *
 * Las cifras son las de la pantalla, en la misma cascada que `Números`: lo que se resta va con su
 * signo. Es material de apoyo para quien lleva la contabilidad, por eso sí lleva costo y ganancia.
 *
 * Es papel: no sigue el tema. Tinta y filete salen de los colores claros de la paleta elegida, y
 * la hoja es la del formato regional. Se genera en el teléfono, sin red.
 */
class SummaryRenderer(context: Context, private val formats: AppFormats, palette: ColorPalette) {
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

    /**
     * Escribe [document] como PDF en [target]. Devuelve `false` si no quedó escrito entero, y
     * entonces no deja archivo.
     */
    fun writePdf(document: SummaryDocument, target: File): Boolean {
        val paper = PaperSize.of(formats.date.formatLocale)
        val pages = pagesOf(blocksOf(document, paper), paper)
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

    /**
     * Los textos del resumen en el orden en que se dibujan, sin el pie. Es lo que leen las pruebas
     * para saber qué lleva el papel sin mirar píxeles.
     */
    internal fun textsOf(document: SummaryDocument): List<String> =
        blocksOf(document, PaperSize.of(formats.date.formatLocale)).mapNotNull { it.text }

    /** La leyenda del pie, igual en cada página. */
    internal fun legend(): String =
        resources.getString(R.string.report_doc_legend, resources.getString(R.string.app_name))

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
        // Al pie de cada página: la leyenda y el número, siempre. Ningún dato los quita.
        val footerTop = (paper.height - MARGIN - FOOTER_SPACE).toFloat() + FOOTER_GAP
        val legend = layout(
            legend(),
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

    /** Los bloques del resumen, de arriba abajo, para el ancho útil de [paper]. */
    private fun blocksOf(document: SummaryDocument, paper: PaperSize): List<Block> = buildList {
        val width = paper.width - 2 * MARGIN
        val summary = document.summary
        val profit = summary.profit
        // De la demostración: lo primero que se lee es que son datos de ejemplo.
        if (document.sampleData) {
            val demo = resources.getString(R.string.report_doc_demo)
            add(TextBlock(demo, width, NAME_SIZE, BOLD, ink, GAP))
        }
        document.businessName?.let { add(TextBlock(it, width, NAME_SIZE, BOLD, ink, GAP)) }
        add(text(R.string.report_doc_title, width, TITLE_SIZE, BOLD, ink))
        add(
            TextBlock(
                resources.getString(R.string.report_doc_period, periodText(summary.period)),
                width,
                BODY_SIZE,
                REGULAR,
                muted,
                GAP,
            ),
        )
        add(
            TextBlock(
                resources.getString(
                    R.string.report_doc_created,
                    formats.date.dayMonthYear(document.createdOn),
                ),
                width,
                BODY_SIZE,
                REGULAR,
                muted,
                GAP,
            ),
        )
        add(RuleBlock(accent, width, SECTION_GAP))

        add(section(R.string.report_doc_section_sales, width))
        add(row(R.string.stats_metric_sales, profit.netSales, width))
        add(row(R.string.term_cogs, -profit.cogs, width))

        summary.tax?.let { tax ->
            add(section(R.string.report_doc_section_tax, width))
            add(row(R.string.stats_tax_collected, tax.collected, width))
            add(row(R.string.stats_tax_paid, tax.paid, width))
            add(
                TextBlock(
                    resources.getString(R.string.report_doc_tax_note),
                    width,
                    SMALL_SIZE,
                    REGULAR,
                    muted,
                    GAP,
                ),
            )
        }

        add(section(R.string.report_doc_section_expenses, width))
        add(row(R.string.stats_row_expenses, -profit.recordedExpenses, width))
        if (profit.paymentCosts != Money.ZERO) {
            add(row(R.string.stats_row_fees, -profit.paymentCosts, width))
        }
        if (profit.netShrinkage < Money.ZERO) {
            // Un sobrante reduce los gastos: suma, como en `Números`.
            add(row(R.string.term_surplus, -profit.netShrinkage, width))
        } else if (profit.netShrinkage > Money.ZERO) {
            add(row(R.string.term_damage_loss, -profit.netShrinkage, width))
        }

        add(section(R.string.report_doc_section_profit, width))
        add(row(R.string.term_sales_profit, profit.grossProfit, width, strong = true))
        add(row(R.string.term_final_profit, profit.netProfit, width, strong = true))

        if (summary.methods.isNotEmpty()) {
            add(section(R.string.report_doc_section_methods, width))
            summary.methods.forEach { method ->
                add(RowBlock(method.name, method.amount, width, strong = false))
            }
        }
    }

    /** El período con sus fechas completas: el papel se lee fuera del año en curso. */
    private fun periodText(period: ReportPeriod): String = when (period) {
        is ReportPeriod.Month -> formats.date.monthYear(period.yearMonth)

        else ->
            if (period.start == period.end) {
                formats.date.dayMonthYear(period.start)
            } else {
                resources.getString(
                    R.string.format_date_range,
                    formats.date.dayMonthYear(period.start),
                    formats.date.dayMonthYear(period.end),
                )
            }
    }

    private fun text(res: Int, width: Int, size: Float, weight: Int, color: Int) =
        TextBlock(resources.getString(res), width, size, weight, color, GAP)

    private fun section(res: Int, width: Int) =
        TextBlock(resources.getString(res), width, SECTION_SIZE, BOLD, ink, SECTION_GAP)

    private fun row(res: Int, amount: Money, width: Int, strong: Boolean = false) =
        RowBlock(resources.getString(res), amount, width, strong)

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

    /**
     * El pincel de un tramo de monto, con la fuente que tiene sus caracteres. Las cifras van con
     * su ancho propio, sin la variante tabular, para que se puedan copiar y buscar en el PDF.
     */
    private fun moneyPaint(font: DocumentFont, weight: Int): Paint = when (font) {
        DocumentFont.APP -> textPaint(BODY_SIZE, weight, ink)

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

    /** Un trozo del resumen que sabe su alto antes de dibujarse. */
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

    /**
     * Una fila de cifra: el nombre a la izquierda, en dos líneas como máximo, y el monto a la
     * derecha, con su símbolo en la fuente que lo tiene. [strong] marca las ganancias.
     */
    private inner class RowBlock(
        private val label: String,
        amount: Money,
        private val width: Int,
        strong: Boolean,
    ) : Block {
        private val weight = if (strong) BOLD else REGULAR
        private val runs = money.runsOf(amount).map { it.text to moneyPaint(it.font, weight) }
        private val amountWidth = runs.fold(0f) { sum, (text, paint) ->
            sum + paint.measureText(text)
        }
        private val labelLayout = layout(
            label,
            textPaint(BODY_SIZE, weight, ink),
            (width - amountWidth - COLUMN_GAP).toInt(),
            Layout.Alignment.ALIGN_NORMAL,
        )
        override val height: Float = ROW_GAP + labelLayout.height
        override val text: String = label + TEXT_SEPARATOR + runs.joinToString("") { it.first }

        override fun draw(canvas: Canvas, top: Float) {
            canvas.save()
            canvas.translate(0f, top + ROW_GAP)
            labelLayout.draw(canvas)
            canvas.restore()
            val baseline = top + ROW_GAP + labelLayout.getLineBaseline(0)
            var x = width - amountWidth
            for ((text, paint) in runs) {
                canvas.drawText(text, x, baseline, paint)
                x += paint.measureText(text)
            }
        }
    }

    private companion object {
        const val MARGIN = 40
        const val FOOTER_SPACE = 48
        const val FOOTER_GAP = 6f
        const val NAME_SIZE = 16f
        const val TITLE_SIZE = 20f
        const val SECTION_SIZE = 12f
        const val BODY_SIZE = 10f
        const val SMALL_SIZE = 9f

        /** La leyenda nunca baja de 8 puntos: tiene que leerse en papel. */
        const val LEGEND_SIZE = 8f
        const val REGULAR = 400
        const val BOLD = 600
        const val GAP = 4f
        const val ROW_GAP = 6f
        const val SECTION_GAP = 12f
        const val HEADER_RULE = 1f
        const val COLUMN_GAP = 12f
        const val MAX_TEXT_LINES = 2
        const val TEXT_SEPARATOR = "\t"
    }
}
