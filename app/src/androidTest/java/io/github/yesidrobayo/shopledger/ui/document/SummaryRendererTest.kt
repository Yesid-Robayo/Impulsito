package io.github.yesidrobayo.shopledger.ui.document

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El PDF del resumen de gestión, dibujado en el teléfono: encabezado, la cascada de `Números`, el
 * impuesto solo si el negocio lo cobra, cada método de pago y la leyenda al pie, que nada quita.
 * Ningún texto lo llama factura, salvo la leyenda que lo niega. Sin red y con datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class SummaryRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val formats = previewFormats()
    private val renderer = SummaryRenderer(context, formats, ColorPalette.INDIGO_MUSTARD)
    private val folder = File(context.getExternalFilesDir(null), "receipt-captures")
        .apply { mkdirs() }

    @Test
    fun theSummaryReadsInOrderWithTheStatisticsCascadeAndEveryMethod() {
        val texts = renderer.textsOf(document(tax = TAX)).map { it.substringBefore('\t') }

        assertEquals(
            listOf(
                "Tienda Central",
                context.getString(R.string.report_doc_title),
                context.getString(
                    R.string.report_doc_period,
                    formats.date.monthYear(YearMonth(2026, 10)),
                ),
                context.getString(R.string.report_doc_created, formats.date.dayMonthYear(TODAY)),
                context.getString(R.string.report_doc_section_sales),
                context.getString(R.string.stats_metric_sales),
                context.getString(R.string.term_cogs),
                context.getString(R.string.report_doc_section_tax),
                context.getString(R.string.stats_tax_collected),
                context.getString(R.string.stats_tax_paid),
                context.getString(R.string.report_doc_tax_note),
                context.getString(R.string.report_doc_section_expenses),
                context.getString(R.string.stats_row_expenses),
                context.getString(R.string.stats_row_fees),
                context.getString(R.string.term_damage_loss),
                context.getString(R.string.report_doc_section_profit),
                context.getString(R.string.term_sales_profit),
                context.getString(R.string.term_final_profit),
                context.getString(R.string.report_doc_section_methods),
                "Efectivo inventado",
                "Tarjeta inventada",
            ),
            texts,
        )
    }

    @Test
    fun aSummaryFromTheDemoStartsSayingItIsSampleData() {
        val demo = context.getString(R.string.report_doc_demo)

        val sample = renderer.textsOf(document(tax = TAX).copy(sampleData = true))
        val real = renderer.textsOf(document(tax = TAX))

        assertEquals(demo, sample.first())
        assertEquals(real, sample.drop(1))
        assertFalse(real.contains(demo))
    }

    @Test
    fun withoutTaxInTheBusinessThePdfHasNoTaxSection() {
        val texts = renderer.textsOf(document(tax = null)).map { it.substringBefore('\t') }

        assertFalse(texts.contains(context.getString(R.string.report_doc_section_tax)))
        assertFalse(texts.contains(context.getString(R.string.stats_tax_collected)))
    }

    @Test
    fun theLegendNamesTheAppAndNoOtherTextCallsItAnInvoice() {
        val legend = renderer.legend()

        assertTrue(legend.contains(context.getString(R.string.app_name)))
        val texts = renderer.textsOf(document(tax = TAX)).joinToString("\n").lowercase()
        // Solo la leyenda niega esas palabras: ningún otro texto del papel las usa.
        for (word in listOf("factura", "invoice", "nota fiscal")) {
            assertFalse(word, texts.contains(word))
        }
    }

    @Test
    fun aLongListOfMethodsGoesOnManyPagesInThePdf() {
        val many = (1..120).map { method("Método inventado $it", 1_000L * it) }
        val file = File(folder, "summary-long.pdf")

        assertTrue(renderer.writePdf(document(tax = TAX, methods = many), file))
        val pages = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            PdfRenderer(it).use { pdf -> pdf.pageCount }
        }
        assertTrue(pages > 1)
    }

    private fun document(
        tax: TaxEstimate?,
        methods: List<PaymentMethodTotal> = listOf(
            method("Efectivo inventado", 3_000_000),
            method("Tarjeta inventada", 1_760_000),
        ),
    ) = SummaryDocument(
        summary = ManagementSummary(
            period = ReportPeriod.Month(YearMonth(2026, 10)),
            profit = requireNotNull(MarginCalculator.profit(TOTALS).valueOrNull()),
            tax = tax,
            methods = methods,
            salesWithoutCost = 0,
            hasMovements = true,
            contents = listOf(SummaryContent.SALES_AND_PAYMENTS),
        ),
        businessName = "Tienda Central",
        createdOn = TODAY,
    )

    private fun method(name: String, amount: Long) =
        PaymentMethodTotal(name, PaymentMethodKind.CASH, 1, Money(amount), Money.ZERO)

    private companion object {
        val TODAY = BusinessDate(LocalDate(2026, 11, 3))
        val TAX = TaxEstimate(collected = Money(510_000), paid = Money(120_000))
        val TOTALS = ProfitTotals(
            salesNetTotal = Money(4_400_000),
            returnsNetRefund = Money(150_000),
            salesTotalCost = Money(2_800_000),
            returnsCostReturned = Money(80_000),
            recordedExpenses = Money(640_000),
            netShrinkage = Money(50_000),
            paymentFees = Money(35_000),
            cashRoundingTotal = Money.ZERO,
        )
    }
}
