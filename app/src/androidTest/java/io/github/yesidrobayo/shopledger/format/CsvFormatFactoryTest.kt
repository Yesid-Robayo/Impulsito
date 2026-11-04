package io.github.yesidrobayo.shopledger.format

import android.icu.text.DecimalFormatSymbols
import android.icu.util.ULocale
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * El separador decimal de los CSV sale del formato regional que se pide, sea cual sea el idioma del
 * teléfono, y es el mismo que muestran las cantidades en pantalla. Los valores esperados están
 * escritos a mano.
 */
@RunWith(Parameterized::class)
class CsvFormatFactoryTest(device: ForeignDevice) {
    @get:Rule
    val foreignDevice = ForeignDeviceRule(device)

    @Test
    fun regionsWithADecimalCommaGiveTheComma() {
        for (tag in listOf("es-AR", "pt-BR", "de-DE", "fr-FR", "es-ES")) {
            assertEquals(tag, DecimalSeparator.COMMA, decimalSeparator(tag))
        }
    }

    @Test
    fun regionsWithADecimalPointGiveThePoint() {
        for (tag in listOf("en-US", "es-MX", "en-IN", "de-CH", "ja-JP")) {
            assertEquals(tag, DecimalSeparator.POINT, decimalSeparator(tag))
        }
    }

    @Test
    fun aRegionWithOtherDigitsStillGivesALatinSeparator() {
        assertEquals(DecimalSeparator.POINT, decimalSeparator("ar-EG"))
        assertEquals(DecimalSeparator.POINT, decimalSeparator("th-TH-u-nu-thai"))
    }

    @Test
    fun theCsvCellsFollowTheRegionAndTheSettings() {
        val commaRegion = factory("es-AR", CsvSeparator.SEMICOLON, currencyDecimals = 2)
        assertEquals("12,34", commaRegion.moneyCell(Money(1234L)))
        assertEquals("-0,250", commaRegion.quantityCell(QuantityMilli(-250L)))

        val pointRegion = factory("en-US", CsvSeparator.COMMA, currencyDecimals = 3)
        assertEquals("1.234", pointRegion.moneyCell(Money(1234L)))

        val noDecimals = factory("pt-BR", CsvSeparator.SEMICOLON, currencyDecimals = 0)
        assertEquals("1234", noDecimals.moneyCell(Money(1234L)))

        // La coma como separador de columnas en una región de coma decimal pone el número entre comillas.
        val quoted = factory("es-AR", CsvSeparator.COMMA, currencyDecimals = 2)
        assertEquals("\"12,34\"", quoted.moneyCell(Money(1234L)))
    }

    @Test
    fun everyRegionIcuKnowsGivesACommaOrAPoint() {
        val others =
            regions().filter { (_, formatLocale) ->
                val symbols = DecimalFormatSymbols.getInstance(formatLocale.numberLocale)
                symbols.decimalSeparator != ',' && symbols.decimalSeparator != '.'
            }
        assertEquals("Regiones con otro separador", emptyList<String>(), others.map { it.first })
    }

    @Test
    fun theCsvSeparatorIsTheOneTheScreenShowsForQuantities() {
        val mismatches =
            regions().filter { (_, formatLocale) ->
                // 1,500 con tres decimales: el separador es el cuarto carácter desde el final.
                val shown = QuantityFormatter(formatLocale).format(QuantityMilli(1500L), true)
                shown[shown.length - 4] != CsvFormatFactory.decimalSeparator(formatLocale).char
            }
        assertEquals(
            "Regiones con otro en pantalla",
            emptyList<String>(),
            mismatches.map {
                it.first
            },
        )
    }

    @Test
    fun amountsUseTheSeparatorOfNumbersEvenWhereMoneyHasItsOwn() {
        // En pantalla, esta región escribe los montos con el signo de su moneda donde va la coma decimal,
        // como `1234$56`. Una hoja de cálculo lee un solo separador decimal en todo el archivo, y ese
        // signo no lo es: el CSV usa el de los números, en los montos y en las cantidades.
        val csv = factory("pt-CV", CsvSeparator.SEMICOLON, currencyDecimals = 2)
        assertEquals("1234,56", csv.moneyCell(Money(123_456L)))
        assertEquals("1234,500", csv.quantityCell(QuantityMilli(1_234_500L)))
    }

    /** Cada región que ICU conoce en este teléfono, con su etiqueta. */
    private fun regions(): List<Pair<String, FormatLocale>> =
        ULocale.getAvailableLocales().mapNotNull { locale ->
            val tag = locale.toLanguageTag()
            FormatLocale.of(tag)?.let { tag to it }
        }

    private fun decimalSeparator(tag: String): DecimalSeparator =
        CsvFormatFactory.decimalSeparator(requireNotNull(FormatLocale.of(tag)))

    private fun factory(tag: String, separator: CsvSeparator, currencyDecimals: Int) =
        CsvFormatFactory.create(requireNotNull(FormatLocale.of(tag)), separator, currencyDecimals)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "device {0}")
        fun devices(): List<ForeignDevice> = ForeignDevice.ALL
    }
}
