package io.github.yesidrobayo.shopledger.format

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * La matriz de configuraciones contra el ICU del dispositivo.
 *
 * En la JVM no hay ICU de Android, así que la matriz trae escritos a mano los separadores de cada
 * región, y las pruebas de `:domain` leen y exportan con ellos. Aquí se comprueba que son los que
 * de verdad escriben los formateadores en cada versión de Android: si ICU cambiara, la prueba de la
 * JVM seguiría pasando con datos falsos, y esta lo diría. La fila que toma la región del teléfono
 * se comprueba con la región que hace de teléfono en la JVM.
 *
 * Además, el idioma de la interfaz nunca cambia una cifra: con la moneda de la fila, cualquier
 * idioma de la app escribe los mismos números.
 */
@RunWith(Parameterized::class)
class RegionalMatrixDeviceTest(private val row: RegionalMatrixRow, device: ForeignDevice) {
    @get:Rule
    val foreignDevice = ForeignDeviceRule(device)

    private val region = row.regionOr(RegionalMatrix.INVENTED_DEVICE)
    private val regionLocale =
        requireNotNull(FormatLocale.of(region.formatLocaleTag)) { region.formatLocaleTag }

    @Test
    fun moneyIsWrittenWithTheSeparatorsTheMatrixDeclares() {
        val symbols = moneyFormatter(regionLocale, row.currencyDecimals).inputSymbols
        assertEquals("$row", region.decimalSeparator.char, symbols.decimalSeparator)
        assertEquals("$row", region.grouping, symbols.grouping)
    }

    @Test
    fun quantitiesAreWrittenWithTheSeparatorsTheMatrixDeclares() {
        val symbols = QuantityFormatter(regionLocale).inputSymbols
        assertEquals("$row", region.decimalSeparator.char, symbols.decimalSeparator)
        assertEquals("$row", region.grouping, symbols.grouping)
    }

    @Test
    fun theCsvUsesTheDecimalSeparatorTheMatrixDeclares() {
        assertEquals(
            "$row",
            region.decimalSeparator,
            CsvFormatFactory.decimalSeparator(regionLocale),
        )
    }

    @Test
    fun theInterfaceLanguageNeverChangesTheDigits() {
        // La fila del teléfono usa la región que la regla le puso al dispositivo.
        val formatLocale =
            requireNotNull(
                FormatLocale.of(row.region?.formatLocaleTag ?: Locale.getDefault().toLanguageTag()),
            )
        val languages =
            listOfNotNull(row.uiLanguageTag, "en", "es", "pt").map(Locale::forLanguageTag) +
                Locale.getDefault()
        for (minor in listOf(0L, 5L, -1_234L, 123_456_789L)) {
            val texts =
                languages.map { language ->
                    MoneyFormatter(
                        formatLocale = formatLocale,
                        currencyCode = row.currencyCode,
                        currencyDecimals = row.currencyDecimals,
                        currencySymbol = null,
                        uiLocale = language,
                    ).format(Money(minor))
                }
            assertEquals("$row $texts", 1, texts.toSet().size)
            assertEquals(
                "$row",
                moneyFormatter(formatLocale, row.currencyDecimals).format(Money(minor)),
                texts.first(),
            )
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun parameters(): List<Array<Any>> = RegionalMatrix.ROWS.flatMap { row ->
            ForeignDevice.ALL.map { device -> arrayOf(row, device) }
        }
    }
}
