package io.github.yesidrobayo.shopledger.format

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Cantidades en milésimas con el formato regional que se pide, sea cual sea el idioma del teléfono.
 * Los valores esperados están escritos a mano.
 */
@RunWith(Parameterized::class)
class QuantityFormatterTest(device: ForeignDevice) {
    @get:Rule
    val foreignDevice = ForeignDeviceRule(device)

    @Test
    fun aCountingUnitShowsAWholeNumber() {
        assertEquals("12", quantity("es-AR").format(QuantityMilli(12_000), allowsFraction = false))
        assertEquals("12", quantity("en-US").format(QuantityMilli(12_000), allowsFraction = false))
    }

    @Test
    fun aUnitWithFractionsAlwaysShowsThreeDecimals() {
        assertEquals("0,250", quantity("es-AR").format(QuantityMilli(250), allowsFraction = true))
        assertEquals("0.250", quantity("en-US").format(QuantityMilli(250), allowsFraction = true))
        assertEquals("1,500", quantity("pt-BR").format(QuantityMilli(1_500), allowsFraction = true))
        assertEquals("2.000", quantity("en-US").format(QuantityMilli(2_000), allowsFraction = true))
    }

    @Test
    fun aNegativeQuantityUsesTheTypographicMinus() {
        val commaRegion = quantity("es-AR")
        assertEquals("\u22122", commaRegion.format(QuantityMilli(-2_000), allowsFraction = false))
        assertEquals("\u22120,250", commaRegion.format(QuantityMilli(-250), allowsFraction = true))
    }

    @Test
    fun aFractionInACountingUnitIsShownInsteadOfRounded() {
        assertEquals(
            "12,500",
            quantity("es-AR").format(QuantityMilli(12_500), allowsFraction = false),
        )
        assertEquals("0.001", quantity("en-US").format(QuantityMilli(1), allowsFraction = false))
    }

    @Test
    fun largeQuantitiesAreGroupedLikeTheRegionDoes() {
        val onHand = QuantityMilli(Limit.ON_HAND_MILLI.max)
        assertEquals("8.000.000,000", quantity("es-AR").format(onHand, allowsFraction = true))
        assertEquals("8,000,000", quantity("en-US").format(onHand, allowsFraction = false))
        assertEquals("80,00,000", quantity("en-IN").format(QuantityMilli(8_000_000_000), false))
        assertEquals("1\u00A0500,000", quantity("fr-FR").format(QuantityMilli(1_500_000), true))
    }

    @Test
    fun aCountIsGroupedLikeTheRegionDoes() {
        assertEquals("1.250", quantity("es-AR").formatCount(1_250))
        assertEquals("1,250", quantity("en-US").formatCount(1_250))
        assertEquals("1,00,000", quantity("en-IN").formatCount(100_000))
        assertEquals("1 250", quantity("fr-FR").formatCount(1_250))
        assertEquals("14", quantity("en-IN").formatCount(14))
        assertEquals("−3", quantity("es-AR").formatCount(-3))
        assertEquals("9,223,372,036,854,775,807", quantity("en-US").formatCount(Long.MAX_VALUE))
    }

    @Test
    fun theExtremesOfLongAreExact() {
        assertEquals(
            "9,223,372,036,854,775.807",
            quantity("en-US").format(QuantityMilli(Long.MAX_VALUE), allowsFraction = true),
        )
        assertEquals(
            "\u22129,223,372,036,854,775.808",
            quantity("en-US").format(QuantityMilli(Long.MIN_VALUE), allowsFraction = false),
        )
    }

    @Test
    fun zeroIsShownInBothKindsOfUnit() {
        assertEquals("0", quantity("es-AR").format(QuantityMilli.ZERO, allowsFraction = false))
        assertEquals("0,000", quantity("es-AR").format(QuantityMilli.ZERO, allowsFraction = true))
    }

    @Test
    fun aRegionWithOtherDigitsStillGetsLatinDigits() {
        assertEquals("\u22120.250", quantity("ar-EG").format(QuantityMilli(-250), true))
        assertEquals("12", quantity("th-TH-u-nu-thai").format(QuantityMilli(12_000), false))
    }

    private fun quantity(tag: String): QuantityFormatter =
        QuantityFormatter(requireNotNull(FormatLocale.of(tag)))

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "device {0}")
        fun devices(): List<ForeignDevice> = ForeignDevice.ALL
    }
}
