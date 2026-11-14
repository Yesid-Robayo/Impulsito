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
import io.github.yesidrobayo.shopledger.domain.rule.print.MonochromeThreshold
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
    private val paper = Style(
        nameSize = NAME_SIZE,
        titleSize = TITLE_SIZE,
        bodySize = BODY_SIZE,
        totalSize = TOTAL_SIZE,
        legendSize = LEGEND_SIZE,
        ink = ink,
        muted = muted,
        accent = accent,
        headerRule = HEADER_RULE,
        thinRule = THIN_RULE,
        amountWeight = MEDIUM,
        unit = 1f,
        withLogo = true,
    )

    // En térmica no hay grises: todo es negro, los montos van en 600 y el logo no se imprime.
    private val thermal = Style(
        nameSize = THERMAL_NAME,
        titleSize = THERMAL_TITLE,
        bodySize = THERMAL_BODY,
        totalSize = THERMAL_TOTAL,
        legendSize = THERMAL_LEGEND,
        ink = Color.BLACK,
        muted = Color.BLACK,
        accent = Color.BLACK,
        headerRule = THERMAL_RULE,
        thinRule = THERMAL_RULE,
        amountWeight = BOLD,
        unit = THERMAL_UNIT,
        withLogo = false,
    )

    /**
     * Escribe [document] como PDF en [target], en la hoja del formato regional, en tantas páginas
     * como haga falta. Devuelve `false` si no quedó escrito entero, y entonces no deja archivo.
     */
    fun writePdf(document: ReceiptDocument, assets: ReceiptAssets, target: File): Boolean {
        val sheet = PaperSize.of(formats.date.formatLocale)
        val column = min(COLUMN_WIDTH, sheet.width - 2 * PAGE_MARGIN)
        val left = (sheet.width - column) / 2f
        val blocks = blocksOf(document, assets, column, paper)
        val pdf = PdfDocument()
        return try {
            var number = 0
            var page: PdfDocument.Page? = null
            var top = 0f
            for (block in blocks) {
                val current = page
                if (current == null || top + block.height > sheet.height - PAGE_MARGIN) {
                    current?.let(pdf::finishPage)
                    number++
                    page = pdf.startPage(
                        PdfDocument.PageInfo.Builder(sheet.width, sheet.height, number).create(),
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
        val blocks = blocksOf(document, assets, COLUMN_WIDTH, paper)
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
     * [document] para una térmica de [dots] puntos de ancho, en blanco y negro puros, con la misma
     * regla de un bit que usa la impresión: lo que se ve es lo que sale. Va sin logo, con todo en
     * negro y con los tamaños de la térmica.
     */
    fun renderThermal(document: ReceiptDocument, assets: ReceiptAssets, dots: Int): Bitmap {
        val column = dots - 2 * THERMAL_PADDING
        return thermalBitmap(blocksOf(document, assets, column, thermal), dots)
    }

    /**
     * La página de prueba de una térmica de [dots] puntos: [title] y [body], centrados y con los
     * tamaños de la térmica. El texto lleva letras como `ñ`, `ç` y `ã`, dibujadas como imagen.
     */
    fun renderThermalTest(title: String, body: String, dots: Int): Bitmap {
        val column = dots - 2 * THERMAL_PADDING
        val u = thermal.unit
        val blocks = listOf(
            centered(title, column, thermal.titleSize, BOLD, Color.BLACK, 0f),
            centered(body, column, thermal.bodySize, REGULAR, Color.BLACK, SECTION_GAP * u),
        )
        return thermalBitmap(blocks, dots)
    }

    /** [blocks] sobre blanco, a [dots] de ancho, pasados a blanco y negro puros. */
    private fun thermalBitmap(blocks: List<Block>, dots: Int): Bitmap {
        val height = blocks.fold(0f) { sum, block -> sum + block.height } + 2 * THERMAL_PADDING
        val bitmap = Bitmap.createBitmap(dots, ceil(height).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.translate(THERMAL_PADDING.toFloat(), THERMAL_PADDING.toFloat())
        var top = 0f
        for (block in blocks) {
            block.draw(canvas, top)
            top += block.height
        }
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
     * Los textos del documento en el orden en que se dibujan, cada fila con su nombre y su monto.
     * Es lo que leen las pruebas para saber qué lleva el papel sin mirar píxeles.
     */
    internal fun textsOf(document: ReceiptDocument, assets: ReceiptAssets): List<String> =
        blocksOf(document, assets, COLUMN_WIDTH, paper).mapNotNull { it.text }

    /**
     * Los bloques del documento, de arriba abajo, para una columna de [width] unidades con el
     * estilo [style]: puntos de PDF en papel, puntos de la cabeza en térmica.
     */
    private fun blocksOf(
        document: ReceiptDocument,
        assets: ReceiptAssets,
        width: Int,
        style: Style,
    ): List<Block> = buildList {
        val header = document.header
        val u = style.unit
        if (style.withLogo) assets.logo?.let { add(LogoBlock(it, width)) }
        header.businessName?.let {
            add(centered(it, width, style.nameSize, BOLD, style.ink, GAP * u))
        }
        header.businessDetails?.let {
            add(centered(it, width, style.bodySize, REGULAR, style.muted, GAP * u))
        }
        if (isNotEmpty()) {
            add(RuleBlock(width, style.accent, style.headerRule, SECTION_GAP * u, AFTER_RULE * u))
        }
        val title = titleOf(document)
        add(centered(title, width, style.titleSize, BOLD, style.ink, SECTION_GAP * u))
        numberOf(document)?.let {
            add(centered(it, width, style.bodySize, REGULAR, style.ink, GAP * u))
        }
        val whenText = whenOf(document, assets.zone)
        add(centered(whenText, width, style.bodySize, REGULAR, style.muted, SMALL_GAP * u))
        dateNoteOf(document)?.let {
            add(centered(it, width, style.bodySize, REGULAR, style.ink, SMALL_GAP * u))
        }
        document.customerName?.let {
            val customer = resources.getString(R.string.receipt_doc_customer, it)
            add(centered(customer, width, style.bodySize, REGULAR, style.muted, SMALL_GAP * u))
        }
        add(RuleBlock(width, style.muted, style.thinRule, SECTION_GAP * u, AFTER_RULE * u))
        document.lines.forEach { add(lineRow(it, assets.units, width, style)) }
        add(RuleBlock(width, style.muted, style.thinRule, SECTION_GAP * u, AFTER_RULE * u))
        document.amounts.forEach { add(amountRow(it, width, style)) }
        document.footerText?.let {
            add(centered(it, width, style.bodySize, REGULAR, style.ink, SECTION_GAP * u))
        }
        val legend = resources.getString(legendOf(document.kind))
        add(centered(legend, width, style.legendSize, REGULAR, style.muted, SECTION_GAP * u))
    }

    /**
     * La leyenda de cada tipo, al pie y siempre: ninguno es una factura, y una cotización o un
     * pedido dicen además que no son un comprobante de venta.
     */
    private fun legendOf(kind: ReceiptKind): Int = when (kind) {
        ReceiptKind.SALE -> R.string.legal_receipt_not_invoice
        ReceiptKind.QUOTE -> R.string.quote_doc_legend
        ReceiptKind.ORDER -> R.string.order_doc_legend
    }

    /** El título: `Comprobante de venta`, o `Cotización 41` y `Pedido 58` con su número. */
    private fun titleOf(document: ReceiptDocument): String {
        val digits = digitsOf(document)
        return when (document.kind) {
            ReceiptKind.SALE -> resources.getString(R.string.receipt_doc_title)
            ReceiptKind.QUOTE -> resources.getString(R.string.quote_doc_title, digits)
            ReceiptKind.ORDER -> resources.getString(R.string.order_doc_title, digits)
        }
    }

    private fun digitsOf(document: ReceiptDocument): String =
        formats.quantity.format(QuantityMilli.ofUnits(document.number), false)

    /** La vigencia de una cotización o la entrega de un pedido, si tienen. */
    private fun dateNoteOf(document: ReceiptDocument): String? {
        document.validUntil?.let {
            return resources.getString(
                R.string.quote_doc_valid_until,
                formats.date.dayMonthYear(it),
            )
        }
        return document.dueDate?.let {
            resources.getString(R.string.order_delivery_row, formats.date.dayMonthYear(it))
        }
    }

    /**
     * El número bajo el título de un comprobante de venta, con `Anulada` si se anuló. Una
     * cotización y un pedido ya lo llevan en el título.
     */
    private fun numberOf(document: ReceiptDocument): String? {
        val digits = digitsOf(document)
        val number = when (document.kind) {
            ReceiptKind.SALE -> resources.getString(R.string.receipt_doc_sale_number, digits)
            ReceiptKind.QUOTE, ReceiptKind.ORDER -> return null
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

    private fun lineRow(
        line: ReceiptLine,
        units: Map<Long, MeasureUnit>,
        width: Int,
        style: Style,
    ): Block {
        val quantity = saleQuantityText(line.quantity, units[line.unitId], resources, formats)
        val label = resources.getString(R.string.receipt_doc_line, quantity, line.productName)
        val indent = if (line.extra) EXTRA_INDENT * style.unit else 0f
        return RowBlock(label, line.amount, width, indent, style, style.bodySize, style.ink)
    }

    private fun amountRow(row: ReceiptAmount, width: Int, style: Style): Block {
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

            is ReceiptAmount.Shipping -> resources.getString(R.string.order_summary_shipping)

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
                RowBlock(label, row.amount, width, 0f, style, style.totalSize, style.ink, true)

            is ReceiptAmount.Tax -> {
                val color = if (row.included) style.muted else style.ink
                RowBlock(label, row.amount, width, 0f, style, style.bodySize, color)
            }

            is ReceiptAmount.Subtotal, is ReceiptAmount.Adjustment, is ReceiptAmount.Rounding,
            is ReceiptAmount.Tip, is ReceiptAmount.Payment, is ReceiptAmount.Change,
            is ReceiptAmount.Shipping,
            is ReceiptAmount.BalanceDue, is ReceiptAmount.Return,
            -> RowBlock(label, row.amount, width, 0f, style, style.bodySize, style.ink)
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

    /** Un filete de lado a lado, con [before] de aire encima y [after] debajo. */
    private class RuleBlock(
        private val width: Int,
        color: Int,
        stroke: Float,
        private val before: Float,
        after: Float,
    ) : Block {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = stroke
        }
        override val height: Float = before + stroke + after
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
     * a la derecha, en una sola línea y sobre la línea base de la primera. La del [total] va en
     * negrita y con más aire encima.
     */
    private inner class RowBlock(
        label: String,
        amount: Money,
        private val width: Int,
        private val indent: Float,
        style: Style,
        size: Float,
        color: Int,
        total: Boolean = false,
    ) : Block {
        private val before = (if (total) TOTAL_GAP else ROW_GAP) * style.unit
        private val weight = if (total) BOLD else style.amountWeight
        private val runs = money.runsOf(amount).map {
            it.text to moneyPaint(it.font, size, weight, color)
        }
        private val amountWidth = runs.fold(0f) { sum, (text, paint) ->
            sum + paint.measureText(text)
        }
        private val labelLayout = layout(
            label,
            textPaint(size, if (total) BOLD else REGULAR, color),
            (width - indent - amountWidth - COLUMN_GAP * style.unit).toInt(),
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

        // Térmica: alturas en puntos de la cabeza, de 203 ppp.
        const val THERMAL_NAME = 28f
        const val THERMAL_TITLE = 24f
        const val THERMAL_BODY = 22f
        const val THERMAL_TOTAL = 30f
        const val THERMAL_LEGEND = 20f

        /** Una línea negra de 2 puntos: una de medio punto no sale en térmica. */
        const val THERMAL_RULE = 2f

        /** Cuántos puntos de la cabeza vale un punto de aire: el texto es 2,2 veces el del PDF. */
        const val THERMAL_UNIT = 2.2f

        /** El borde blanco a cada lado: la cabeza no imprime pegada al borde del papel. */
        const val THERMAL_PADDING = 8
    }

    /**
     * Cómo se dibuja: tamaños, tintas, filetes, el peso de los montos y si lleva logo. [unit]
     * multiplica el aire entre bloques: un punto de PDF en papel, o los puntos de la cabeza que
     * vale un punto en térmica.
     */
    private class Style(
        val nameSize: Float,
        val titleSize: Float,
        val bodySize: Float,
        val totalSize: Float,
        val legendSize: Float,
        val ink: Int,
        val muted: Int,
        val accent: Int,
        val headerRule: Float,
        val thinRule: Float,
        val amountWeight: Int,
        val unit: Float,
        val withLogo: Boolean,
    )
}
