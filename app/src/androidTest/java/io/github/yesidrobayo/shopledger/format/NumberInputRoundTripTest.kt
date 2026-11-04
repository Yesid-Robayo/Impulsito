package io.github.yesidrobayo.shopledger.format

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Lo que escriben los formateadores, leído por el dominio con los símbolos que ellos mismos dan,
 * vuelve a los mismos enteros. Corre con cada fila de la matriz de configuraciones de la estrategia
 * de pruebas, que es el único lugar donde se nombran una moneda y una región, y con cada idioma
 * extraño del teléfono. Necesita el ICU de Android, por eso corre en el dispositivo.
 *
 * Un decimal de más y un valor sobre su tope se arman también con los formateadores, para que el
 * texto rechazado sea uno que la región de verdad escribiría.
 */
@RunWith(Parameterized::class)
class NumberInputRoundTripTest(private val row: MatrixRow, device: ForeignDevice) {
    @get:Rule
    val foreignDevice = ForeignDeviceRule(device)

    @Test
    fun amountsComeBackToTheSameMinorUnits() {
        val formatter = MoneyFormatter(formatLocale(), row.currencyDecimals)
        for (minor in AMOUNTS) {
            val text = formatter.format(Money(minor))
            assertEquals(
                "$row $text",
                value(Money(minor)),
                money(formatter, text, Limit.DOCUMENT_TOTAL),
            )
            val digits = formatter.parts(Money(minor)).number
            val unsigned = Money(if (minor < 0L) -minor else minor)
            assertEquals(
                "$row $digits",
                value(unsigned),
                money(formatter, digits, Limit.DOCUMENT_TOTAL),
            )
        }
    }

    @Test
    fun aNegativeAmountUsesTheTypographicMinusAndAlsoReadsWithTheKeyboardHyphen() {
        val formatter = MoneyFormatter(formatLocale(), row.currencyDecimals)
        val text = formatter.format(Money(-120_000))
        assertTrue("$row $text", text.contains('\u2212'))
        assertEquals(value(Money(-120_000)), money(formatter, text, Limit.UNIT_MONEY))
        val typed = text.replace('\u2212', '-')
        assertEquals(value(Money(-120_000)), money(formatter, typed, Limit.UNIT_MONEY))
    }

    @Test
    fun oneDecimalMoreThanTheCurrencyHasIsRejected() {
        val formatter = MoneyFormatter(formatLocale(), row.currencyDecimals)
        // Lo que escribiría la misma región con un decimal más. Con el máximo de decimales no hay
        // formateador con uno más: se agrega una cifra al final, donde van los decimales.
        val text =
            if (row.currencyDecimals < MoneyFormatter.MAX_CURRENCY_DECIMALS) {
                MoneyFormatter(formatLocale(), row.currencyDecimals + 1).format(Money(123_451))
            } else {
                formatter.format(Money(123_451)).also { assertTrue(it.last().isDigit()) } + "1"
            }
        assertEquals(
            "$row $text",
            NumberInput.TooManyDecimals(row.currencyDecimals),
            NumberInputParser.money(
                text,
                formatter.inputSymbols,
                row.currencyDecimals,
                Limit.UNIT_MONEY,
            ),
        )
    }

    @Test
    fun anAmountOverItsLimitIsRejectedAndTheLimitItselfIsNot() {
        val formatter = MoneyFormatter(formatLocale(), row.currencyDecimals)
        for (limit in listOf(Limit.UNIT_MONEY, Limit.DOCUMENT_TOTAL)) {
            val cap = Money(limit.max)
            assertEquals(value(cap), money(formatter, formatter.format(cap), limit))
            val over = formatter.format(Money(limit.max + 1))
            assertEquals("$row $over", NumberInput.TooLarge(cap), money(formatter, over, limit))
            val underNegative = formatter.format(Money(-limit.max - 1))
            assertEquals(
                "$row $underNegative",
                NumberInput.TooLarge(cap),
                money(formatter, underNegative, limit),
            )
        }
    }

    @Test
    fun quantitiesComeBackToTheSameThousandths() {
        val formatter = QuantityFormatter(formatLocale())
        for (milli in QUANTITIES) {
            val quantity = QuantityMilli(milli)
            for (allowsFraction in listOf(true, false)) {
                val text = formatter.format(quantity, allowsFraction)
                assertEquals(
                    "$row $text",
                    NumberInput.Value(quantity),
                    NumberInputParser.quantity(text, formatter.inputSymbols, Limit.ON_HAND_MILLI),
                )
            }
        }
    }

    @Test
    fun aQuantityWithFourDecimalsOrOverItsLimitIsRejected() {
        val formatter = QuantityFormatter(formatLocale())
        val fourDecimals = MoneyFormatter(formatLocale(), 4).format(Money(12_345))
        assertEquals(
            "$row $fourDecimals",
            NumberInput.TooManyDecimals(3),
            NumberInputParser.quantity(
                fourDecimals,
                formatter.inputSymbols,
                Limit.LINE_QUANTITY_MILLI,
            ),
        )
        val cap = QuantityMilli(Limit.LINE_QUANTITY_MILLI.max)
        val over = formatter.format(QuantityMilli(Limit.LINE_QUANTITY_MILLI.max + 1), true)
        assertEquals(
            "$row $over",
            NumberInput.TooLarge(cap),
            NumberInputParser.quantity(over, formatter.inputSymbols, Limit.LINE_QUANTITY_MILLI),
        )
    }

    @Test
    fun percentagesComeBackToTheSameBasisPoints() {
        val formatter = PercentFormatter(formatLocale())
        // El formateador muestra un decimal de porcentaje: vuelven exactos los múltiplos de diez.
        for (bp in PERCENTAGES) {
            val rate = BasisPoints(bp)
            for (text in listOf(
                formatter.format(rate),
                formatter.formatChange(rate),
                formatter.formatPointsChange(rate),
            )) {
                assertEquals("$row $text", NumberInput.Value(rate), percent(formatter, text))
            }
        }
    }

    @Test
    fun aPercentageWithThreeDecimalsOrOverItsLimitIsRejected() {
        val formatter = PercentFormatter(formatLocale())
        val threeDecimals = MoneyFormatter(formatLocale(), 3).format(Money(12_345))
        assertEquals(
            "$row $threeDecimals",
            NumberInput.TooManyDecimals(2),
            percent(formatter, threeDecimals),
        )
        val over = formatter.format(BasisPoints(BasisPoints.MAX_BULK_PERCENT.bp + 10))
        assertEquals(
            "$row $over",
            NumberInput.TooLarge(BasisPoints.MAX_BULK_PERCENT),
            percent(formatter, over),
        )
    }

    /**
     * El formato regional de la fila. La fila que lo toma del dispositivo usa el idioma del
     * teléfono que fija la regla, así que se resuelve dentro de cada prueba.
     */
    private fun formatLocale(): FormatLocale =
        requireNotNull(FormatLocale.of(row.formatLocale ?: Locale.getDefault().toLanguageTag())) {
            "La fila $row no tiene un formato regional válido"
        }

    private fun money(formatter: MoneyFormatter, text: String, limit: Limit): NumberInput<Money> =
        NumberInputParser.money(text, formatter.inputSymbols, row.currencyDecimals, limit)

    private fun percent(formatter: PercentFormatter, text: String): NumberInput<BasisPoints> =
        NumberInputParser.percent(text, formatter.inputSymbols, BasisPoints.MAX_BULK_PERCENT)

    private fun value(amount: Money): NumberInput<Money> = NumberInput.Value(amount)

    /**
     * Una fila de la matriz: la moneda solo nombra la fila, porque lo que cambia la lectura son sus
     * decimales y el formato regional. `null` es el formato del dispositivo.
     */
    data class MatrixRow(
        val currency: String,
        val currencyDecimals: Int,
        val formatLocale: String?,
    ) {
        override fun toString(): String = "$currency $currencyDecimals ${formatLocale ?: "device"}"
    }

    companion object {
        private val AMOUNTS: List<Long> =
            listOf(
                0L,
                1L,
                -1L,
                5L,
                1_234L,
                -1_234L,
                12_350L,
                123_456_789L,
                -123_456_789L,
                1_000_000_007L,
                Limit.UNIT_MONEY.max,
                -Limit.UNIT_MONEY.max,
                Limit.DOCUMENT_TOTAL.max,
                -Limit.DOCUMENT_TOTAL.max,
            )

        private val QUANTITIES: List<Long> =
            listOf(
                0L,
                1L,
                250L,
                1_500L,
                12_000L,
                -2_000L,
                -250L,
                Limit.LINE_QUANTITY_MILLI.max,
                Limit.ON_HAND_MILLI.max,
            )

        private val PERCENTAGES: List<Long> =
            listOf(0L, 10L, 1_250L, 2_000L, -2_000L, -9_990L, 99_990L)

        /** Las filas de la matriz de configuraciones, en su orden. */
        private val MATRIX: List<MatrixRow> =
            listOf(
                MatrixRow("JPY", 0, "ja-JP"),
                MatrixRow("VND", 0, "vi-VN"),
                MatrixRow("USD", 2, "en-US"),
                MatrixRow("EUR", 2, "es-ES"),
                MatrixRow("BRL", 2, "pt-BR"),
                MatrixRow("INR", 2, "en-IN"),
                MatrixRow("CAD", 2, "en-CA"),
                MatrixRow("CHF", 2, "de-CH"),
                MatrixRow("KWD", 3, "en-KW"),
                MatrixRow("CLF", 4, "es-CL"),
                MatrixRow("XXX", 0, null),
                MatrixRow("ARS", 2, "es-AR"),
                MatrixRow("MXN", 2, "es-MX"),
                MatrixRow("JPY", 0, "en-JP"),
                MatrixRow("CLP", 0, "es-CL"),
                MatrixRow("ARS", 0, "es-AR"),
                MatrixRow("XTS", 4, "en"),
                MatrixRow("COP", 0, "es-CO"),
            )

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun parameters(): List<Array<Any>> =
            MATRIX.flatMap { row -> ForeignDevice.ALL.map { device -> arrayOf(row, device) } }
    }
}
