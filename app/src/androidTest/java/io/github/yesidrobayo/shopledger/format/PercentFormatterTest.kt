package io.github.yesidrobayo.shopledger.format

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Porcentajes en puntos básicos, con un decimal y la forma del formato regional, sea cual sea el
 * idioma del teléfono. Los valores esperados están escritos a mano.
 */
@RunWith(Parameterized::class)
class PercentFormatterTest(device: ForeignDevice) {
    @get:Rule
    val foreignDevice = ForeignDeviceRule(device)

    @Test
    fun aMarginWithOneDecimalInTheFormOfTheRegion() {
        assertEquals("20,0\u00A0%", percent("de-DE").format(BasisPoints(2_000)))
        assertEquals("20.0%", percent("en-US").format(BasisPoints(2_000)))
        assertEquals("20,0%", percent("pt-BR").format(BasisPoints(2_000)))
        assertEquals("%20,0", percent("tr-TR").format(BasisPoints(2_000)))
    }

    @Test
    fun aTaxRateKeepsEveryDecimalItWasWrittenWith() {
        assertEquals("21.0%", percent("en-US").formatRate(BasisPoints(2_100)))
        assertEquals("10,5%", percent("pt-BR").formatRate(BasisPoints(1_050)))
        assertEquals("8,25 %", percent("de-DE").formatRate(BasisPoints(825)))
        assertEquals("%0,05", percent("tr-TR").formatRate(BasisPoints(5)))
        assertEquals("100.0%", percent("en-US").formatRate(BasisPoints(10_000)))
    }

    @Test
    fun aNegativeMarginUsesTheTypographicMinus() {
        assertEquals("\u221220,0%", percent("pt-BR").format(BasisPoints(-2_000)))
        assertEquals("\u2212%20,0", percent("tr-TR").format(BasisPoints(-2_000)))
    }

    @Test
    fun theDecimalIsRoundedToTheNearestTenthAwayFromZeroOnATie() {
        // 967 puntos básicos son 9,67 %: se muestra 9,7 %, y los cálculos siguen con 967.
        assertEquals("9.7%", percent("en-US").format(BasisPoints(967)))
        assertEquals("9.7%", percent("en-US").format(BasisPoints(965)))
        assertEquals("9.6%", percent("en-US").format(BasisPoints(964)))
        assertEquals("\u22129.7%", percent("en-US").format(BasisPoints(-965)))
        assertEquals("\u22129.6%", percent("en-US").format(BasisPoints(-964)))
    }

    @Test
    fun aValueThatRoundsToZeroHasNoSign() {
        assertEquals("0.0%", percent("en-US").format(BasisPoints(-4)))
        assertEquals("0.0%", percent("en-US").formatChange(BasisPoints(-4)))
        assertEquals("0.0%", percent("en-US").formatChange(BasisPoints(4)))
    }

    @Test
    fun aChangeAlwaysCarriesItsSign() {
        assertEquals("+8,0%", percent("pt-BR").formatChange(BasisPoints(800)))
        assertEquals("\u22123,2%", percent("pt-BR").formatChange(BasisPoints(-320)))
        assertEquals("0,0%", percent("pt-BR").formatChange(BasisPoints.ZERO))
        assertEquals("+%8,0", percent("tr-TR").formatChange(BasisPoints(800)))
        assertEquals("+8,0\u00A0%", percent("de-DE").formatChange(BasisPoints(800)))
    }

    @Test
    fun aMarginChangeIsInPointsWithoutThePercentSign() {
        assertEquals("+1,5", percent("es-AR").formatPointsChange(BasisPoints(150)))
        assertEquals("\u22120,8", percent("es-AR").formatPointsChange(BasisPoints(-80)))
        assertEquals("0.0", percent("en-US").formatPointsChange(BasisPoints.ZERO))
    }

    @Test
    fun largePercentagesAreGroupedLikeTheRegionDoes() {
        assertEquals("12.345,7%", percent("pt-BR").format(BasisPoints(1_234_567)))
        assertEquals("1,000.0%", percent("en-US").format(BasisPoints(100_000)))
    }

    @Test
    fun aRegionWithOtherDigitsStillGetsLatinDigits() {
        assertEquals("\u221220.0%", percent("ar-EG").format(BasisPoints(-2_000)))
        assertEquals("+8.0%", percent("th-TH-u-nu-thai").formatChange(BasisPoints(800)))
    }

    private fun percent(tag: String): PercentFormatter =
        PercentFormatter(requireNotNull(FormatLocale.of(tag)))

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "device {0}")
        fun devices(): List<ForeignDevice> = ForeignDevice.ALL
    }
}
