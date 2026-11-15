package io.github.yesidrobayo.shopledger.ui.document

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptKind
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptLine
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixSale
import io.github.yesidrobayo.shopledger.ui.format.businessFormatsOf
import java.util.Locale
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * El comprobante de la venta de la matriz, dibujado en el teléfono con la moneda, los decimales,
 * el formato y el idioma de la interfaz de cada fila con moneda confirmada. Es la misma venta que
 * la prueba de los módulos en `:data` registra y de la que arma su comprobante, con las cifras de
 * [RegionalMatrixSale].
 *
 * Cada monto del papel, de las líneas a los pagos, conserva las cifras del entero guardado, con
 * los decimales de la moneda tras el separador decimal de la región, escritos a mano; y los
 * negativos, el descuento y un redondeo hacia abajo, llevan el signo menos tipográfico. Datos
 * inventados.
 */
@RunWith(Parameterized::class)
class RegionalMatrixReceiptTest(private val row: RegionalMatrixRow) {
    private val region = row.regionOr(RegionalMatrix.INVENTED_DEVICE)
    private val language = row.uiLanguageTag ?: "en"
    private val sale = RegionalMatrixSale.saleFor(row.taxMode)
    private val rounding =
        RegionalMatrixSale.cashRoundingOf(sale.cashPart, row.cashRounding.minor)

    @Test
    fun everyAmountOfThePaperKeepsTheMinorUnitsInTheRowFormat() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(language))
        val context = base.createConfigurationContext(configuration)
        val formats =
            businessFormatsOf(row.regionalSettings(), Locale.forLanguageTag(language))
        val renderer = ReceiptRenderer(context, formats, ColorPalette.INDIGO_MUSTARD)
        val zone = requireNotNull(BusinessTimeZone.of(region.businessTimeZoneId))
        val document = document()

        val texts = renderer.textsOf(document, ReceiptAssets(null, emptyMap(), zone))

        // Cada fila con monto lleva su nombre, un tabulador y el monto, en el orden del papel.
        val shown = texts.filter { TAB in it }.map { it.substringAfter(TAB) }
        val minors = document.lines.map { it.amount.minor } +
            document.amounts.map { it.amount.minor }
        assertEquals("$row: $texts", minors.size, shown.size)
        minors.zip(shown).forEach { (minor, text) -> assertAmount(minor, text) }
    }

    /** [text] escribe [minor] con los decimales de la fila y el signo que corresponde. */
    private fun assertAmount(minor: Long, text: String) {
        val decimals = row.currencyDecimals
        val digits = (if (minor < 0L) -minor else minor).toString().padStart(decimals + 1, '0')
        val case = "$row: $minor como $text"
        assertEquals(case, digits, text.filter { it in '0'..'9' })
        if (decimals > 0) {
            val fraction = "${region.decimalSeparator.char}${digits.takeLast(decimals)}"
            assertTrue(case, text.contains(fraction))
        }
        assertEquals(case, minor < 0L, text.contains(TYPOGRAPHIC_MINUS))
        assertFalse(case, text.contains('-'))
    }

    /**
     * El comprobante que arma la venta de la matriz: lo que cobra cada línea tras su descuento, el
     * subtotal, el descuento de toda la venta, el impuesto de la fila, el redondeo, el total y los
     * dos pagos, como lo comprueba la prueba de los módulos sobre la base.
     */
    private fun document(): ReceiptDocument {
        val rate = BasisPoints(RegionalMatrixSale.TAX_RATE_BP)
        val amounts = buildList<ReceiptAmount> {
            add(ReceiptAmount.Subtotal(Money(RegionalMatrixSale.SUBTOTAL)))
            add(ReceiptAmount.Adjustment(Money(RegionalMatrixSale.ORDER_ADJUSTMENT)))
            when (row.taxMode) {
                TaxMode.NONE -> Unit

                TaxMode.INCLUDED ->
                    add(ReceiptAmount.Tax(TAX_NAME, rate, Money(sale.tax), included = true))

                TaxMode.ADDED ->
                    add(ReceiptAmount.Tax(TAX_NAME, rate, Money(sale.tax), included = false))
            }
            if (rounding != 0L) add(ReceiptAmount.Rounding(Money(rounding)))
            add(ReceiptAmount.Total(Money(sale.total + rounding)))
            add(ReceiptAmount.Payment("Efectivo inventado", Money(sale.cashPart + rounding)))
            add(ReceiptAmount.Payment("Tarjeta inventada", Money(RegionalMatrixSale.CARD_PAYMENT)))
        }
        return ReceiptDocument(
            kind = ReceiptKind.SALE,
            number = 1L,
            voided = false,
            header = ReceiptHeader(
                logoMediaId = null,
                businessName = "Tienda inventada",
                businessDetails = null,
            ),
            businessDate = requireNotNull(BusinessDate.parse("2026-11-02")),
            occurredAt = Instant.fromEpochMilliseconds(SALE_AT),
            customerName = null,
            lines = listOf(
                ReceiptLine(
                    productName = "Aceite inventado",
                    quantity = QuantityMilli(RegionalMatrixSale.OIL_SOLD_MILLI),
                    unitId = UNIT_ID,
                    amount = Money(RegionalMatrixSale.OIL_BASE),
                    extra = false,
                ),
                ReceiptLine(
                    productName = "Queso inventado",
                    quantity = QuantityMilli(RegionalMatrixSale.CHEESE_SOLD_MILLI),
                    unitId = KILOGRAM_ID,
                    amount = Money(RegionalMatrixSale.CHEESE_BASE),
                    extra = false,
                ),
            ),
            amounts = amounts,
            footerText = null,
        )
    }

    companion object {
        private const val TAB = '\t'
        private const val TYPOGRAPHIC_MINUS = '−'
        private const val TAX_NAME = "Tasa inventada"
        private const val UNIT_ID = 1L
        private const val KILOGRAM_ID = 2L

        /** 2026-11-02 04:30 UTC, el instante de la venta de la matriz. */
        private const val SALE_AT = 1_793_593_800_000L

        /** Las filas con moneda confirmada: sin ella no hay venta ni comprobante. */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): List<RegionalMatrixRow> =
            RegionalMatrix.ROWS.filter { it.currencyCode.isConfirmed }
    }
}
