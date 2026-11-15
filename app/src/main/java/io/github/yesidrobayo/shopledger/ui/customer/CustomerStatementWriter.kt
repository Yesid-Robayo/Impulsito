package io.github.yesidrobayo.shopledger.ui.customer

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.StringRes
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.ui.currency.CurrencySymbolFonts
import io.github.yesidrobayo.shopledger.ui.currency.moneyText
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * El extracto de un cliente como PDF, para compartirlo desde `cache/exports/`.
 *
 * Sale de [StatementDocument], que no tiene costo, margen, ganancia ni notas: el documento no puede
 * llevarlos. Lleva el nombre del negocio si se escribió, el del cliente, la fecha, el saldo y cada
 * fila del libro de la más antigua a la más reciente, con su saldo después; una fila compensada va
 * marcada como anulada. Al pie, la leyenda de que no es una factura.
 *
 * Los textos salen de [resources], en el idioma de la interfaz, y los números de [formats], en el
 * formato del negocio. Se arma en memoria y se escribe de una vez: si algo falla, no queda nada.
 */
internal class CustomerStatementWriter(
    private val resources: Resources,
    private val formats: AppFormats,
) {
    private val chain = CurrencySymbolFonts.chain(resources)

    /** Escribe el extracto en [target]. Devuelve `false` si no quedó escrito entero. */
    fun write(document: StatementDocument, target: File): Boolean {
        val pdf = PdfDocument()
        return try {
            Pages(pdf).apply {
                header(document)
                document.lines.forEach { row(it) }
                footer()
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

    private fun money(amount: Money, signed: Boolean = false): String {
        val parts = formats.money.parts(amount)
        val plus = signed && amount > Money.ZERO
        return moneyText(parts, chain.resolve(parts.symbol, parts.currencyCode), plus = plus).text
    }

    /** Las páginas del extracto: abre una nueva cuando la fila siguiente no cabe. */
    private inner class Pages(private val pdf: PdfDocument) {
        private var number = 0
        private var page: PdfDocument.Page? = null
        private var y = 0f

        private val canvas: Canvas get() = requireNotNull(page).canvas

        init {
            newPage()
        }

        fun header(document: StatementDocument) {
            document.businessName?.let { y = text(it, y, HEADING_SIZE, bold = true) + GAP_SMALL }
            y = text(string(R.string.customer_statement_doc_title), y, TITLE_SIZE, bold = true)
            val customer = document.customerName ?: string(R.string.customer_anonymous)
            y = text(customer, y + GAP_SMALL, BODY_SIZE)
            y = text(formats.date.longDate(document.issuedOn), y + GAP_SMALL, BODY_SIZE)
            val balance = resources.getString(
                R.string.customer_ledger_balance_after,
                money(document.balance),
            )
            y = text(balance, y + GAP_SMALL, HEADING_SIZE, bold = true) + GAP_LARGE
            columns()
        }

        fun row(line: StatementDocumentLine) {
            if (y + ROW_HEIGHT > PAGE_HEIGHT - MARGIN - FOOTER_SPACE) {
                finishPage()
                newPage()
                columns()
            }
            val concept = string(typeLabel(line.type)).let {
                if (line.voided) {
                    resources.getString(
                        R.string.customer_statement_voided_row,
                        it,
                    )
                } else {
                    it
                }
            }
            val cells = listOf(
                formats.date.dayMonthYear(line.date),
                concept,
                money(line.amount, signed = true),
                money(line.balanceAfter),
            )
            drawRow(cells, bold = false)
        }

        fun footer() {
            if (y + FOOTER_SPACE > PAGE_HEIGHT - MARGIN) {
                finishPage()
                newPage()
            }
            text(string(R.string.statement_doc_legend), y + GAP_LARGE, SMALL_SIZE)
        }

        fun finish() = finishPage()

        private fun columns() {
            drawRow(
                listOf(
                    string(R.string.customer_payment_date),
                    string(R.string.customer_statement_concept),
                    string(R.string.customer_payment_amount),
                    string(R.string.customer_balance),
                ),
                bold = true,
            )
        }

        private fun drawRow(cells: List<String>, bold: Boolean) {
            val paint = paint(BODY_SIZE, bold)
            val width = PAGE_WIDTH - 2 * MARGIN
            val baseline = y + BODY_SIZE
            var x = MARGIN.toFloat()
            cells.forEachIndexed { index, cell ->
                val share = width * COLUMN_SHARES[index]
                // Los montos van a la derecha de su columna, como en pantalla.
                val numeric = index >= FIRST_AMOUNT_COLUMN
                val shown = fit(cell, paint, share - CELL_PADDING)
                val drawX = if (numeric) x + share - paint.measureText(shown) else x
                canvas.drawText(shown, drawX, baseline, paint)
                x += share
            }
            y += ROW_HEIGHT
            val rule = Paint().apply {
                color = Color.LTGRAY
                strokeWidth = RULE_WIDTH
            }
            canvas.drawLine(
                MARGIN.toFloat(),
                y - ROW_GAP,
                (PAGE_WIDTH - MARGIN).toFloat(),
                y - ROW_GAP,
                rule,
            )
        }

        /** Un texto que no cabe en su columna se corta con puntos suspensivos: los montos siempre caben. */
        private fun fit(value: String, paint: TextPaint, width: Float): String {
            if (paint.measureText(value) <= width) return value
            var end = value.length
            while (end > 0 &&
                paint.measureText(value, 0, end) + paint.measureText(ELLIPSIS) > width
            ) {
                end--
            }
            return value.substring(0, end) + ELLIPSIS
        }

        private fun newPage() {
            number++
            page =
                pdf.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create(),
                )
            y = MARGIN.toFloat()
        }

        private fun finishPage() {
            page?.let(pdf::finishPage)
            page = null
        }

        /** Un párrafo que se parte al ancho de la página. Devuelve dónde termina. */
        private fun text(value: String, top: Float, size: Float, bold: Boolean = false): Float {
            val width = PAGE_WIDTH - 2 * MARGIN
            val layout = StaticLayout.Builder
                .obtain(value, 0, value.length, paint(size, bold), width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            canvas.save()
            canvas.translate(MARGIN.toFloat(), top)
            layout.draw(canvas)
            canvas.restore()
            return top + layout.height
        }
    }

    private fun string(@StringRes id: Int): String = resources.getString(id)

    private fun paint(size: Float, bold: Boolean) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private companion object {
        /** A4 en puntos de PDF. */
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 48
        const val TITLE_SIZE = 20f
        const val HEADING_SIZE = 14f
        const val BODY_SIZE = 10f
        const val SMALL_SIZE = 9f
        const val GAP_SMALL = 6f
        const val GAP_LARGE = 20f
        const val ROW_HEIGHT = 20f
        const val ROW_GAP = 5f
        const val RULE_WIDTH = 0.5f
        const val CELL_PADDING = 8f
        const val FOOTER_SPACE = 48f
        const val FIRST_AMOUNT_COLUMN = 2
        const val ELLIPSIS = "…"

        /** Fecha, concepto, monto y saldo: los montos llevan la parte más ancha. */
        val COLUMN_SHARES = floatArrayOf(0.2f, 0.3f, 0.25f, 0.25f)
    }
}

/** El nombre de cada tipo de fila, como lo muestra el extracto. */
@StringRes
internal fun typeLabel(type: CustomerLedgerEntryType): Int = when (type) {
    CustomerLedgerEntryType.OPENING_BALANCE -> R.string.customer_ledger_opening_balance
    CustomerLedgerEntryType.CREDIT_SALE -> R.string.customer_ledger_credit_sale
    CustomerLedgerEntryType.PAYMENT -> R.string.customer_ledger_payment
    CustomerLedgerEntryType.RETURN_CREDIT -> R.string.customer_ledger_return_credit
    CustomerLedgerEntryType.ADJUSTMENT -> R.string.customer_ledger_adjustment
    CustomerLedgerEntryType.ENTRY_VOID -> R.string.customer_ledger_entry_void
}
