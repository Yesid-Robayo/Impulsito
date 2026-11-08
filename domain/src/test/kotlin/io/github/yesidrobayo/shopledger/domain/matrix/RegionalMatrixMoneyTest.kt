package io.github.yesidrobayo.shopledger.domain.matrix

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.CashRounding
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.PriceSuggestion
import io.github.yesidrobayo.shopledger.domain.rule.TaxCalculator
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Los ejemplos de dinero de las reglas de negocio con cada fila de la matriz de configuraciones: los
 * mismos enteros en unidad menor con cualquier moneda, decimales, formato o zona. Lo que cambia de
 * una fila a otra entra por sus ajustes: el paso del redondeo de efectivo, el modo de impuesto y los
 * símbolos con que la persona escribe una cantidad.
 *
 * Corren con zonas e idiomas extraños de la JVM, que cambiarían el resultado si una regla los leyera.
 */
@RunWith(Parameterized::class)
class RegionalMatrixMoneyTest(
    private val row: RegionalMatrixRow,
    defaults: ForeignSystemDefaults,
) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    private val settings = row.regionalSettings()
    private val symbols = row.regionOr(RegionalMatrix.INVENTED_DEVICE).numberSymbols

    @Test
    fun `a third of a kilo gives the same minor units with any number of decimals`() {
        // La persona escribe la cantidad con los separadores de su región.
        val typed =
            NumberInputParser.quantity(
                written(333, 3, symbols),
                symbols,
                Limit.LINE_QUANTITY_MILLI,
            )
        assertEquals(NumberInput.Value(QuantityMilli(333)), typed)
        val quantity = QuantityMilli(333)

        // 6160,5 y 616,05 suben al entero más cercano, se lean con 0, 2 o 3 decimales.
        assertEquals(Checked.Value(Money(6_161)), Money(18_500).timesQuantity(quantity))
        assertEquals(Checked.Value(Money(616)), Money(1_850).timesQuantity(quantity))
    }

    @Test
    fun `the universal limits and the wide multiplication do not depend on the currency`() {
        val million = QuantityMilli.ofUnits(1_000_000)

        assertEquals(
            Checked.Value(Money(Limit.DOCUMENT_TOTAL.max)),
            Money(1_000_000_000).timesQuantity(million, Limit.DOCUMENT_TOTAL),
        )
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            Money(Limit.UNIT_MONEY.max).timesQuantity(million, Limit.DOCUMENT_TOTAL),
        )
        assertEquals(
            Checked.Value(1_000_000_000_000_000_000L),
            mulDivRound(1_000_000_000, 1_000_000_000_000, 1_000),
        )
        assertEquals(Checked.Overflow, mulDivRound(3_037_000_500, 3_037_000_500, 1))
        assertEquals(Checked.Value(1L), mulDivRound(1, 1, 2))
        assertEquals(Checked.Value(-1L), mulDivRound(-1, 1, 2))
    }

    @Test
    fun `the documented cash rounding examples hold in every configuration`() {
        // Paso, parte en efectivo y lo que se suma, de la tabla de la regla.
        val examples =
            listOf(
                Triple(5L, 1_232L, -2L),
                Triple(5L, 1_233L, 2L),
                Triple(5L, 1_237L, -2L),
                Triple(10L, 1_235L, 5L),
                Triple(50L, 1_234L, 16L),
                Triple(0L, 1_234L, 0L),
            )
        for ((step, cashPart, expected) in examples) {
            assertEquals(
                "$cashPart al paso $step",
                Money(expected),
                CashRounding.amount(Money(cashPart), Money(step)),
            )
        }
    }

    @Test
    fun `the cash rounding step of the row rounds to its nearest multiple`() {
        val step = settings.cashRounding
        // Lo que se suma a 1234 con el paso de cada fila de la matriz: sin redondeo, 5, 50 y mil.
        val expected =
            when (step.minor) {
                0L -> 0L
                5L -> 1L
                50L -> 16L
                1_000L -> -234L
                else -> error("Falta en esta prueba el ejemplo del paso de la fila $row")
            }
        assertEquals(Money(expected), CashRounding.amount(Money(1_234), step))

        // Con cualquier parte en efectivo, el cobro cae en un múltiplo del paso y nunca se mueve
        // más de medio paso.
        for (cashPart in -3_000L..3_000L step 7) {
            val adjustment = CashRounding.amount(Money(cashPart), step).minor
            if (step.minor == 0L) {
                assertEquals(0L, adjustment)
            } else {
                assertEquals("$cashPart", 0L, (cashPart + adjustment) % step.minor)
                assertTrue("$cashPart", 2 * abs(adjustment) <= step.minor)
            }
        }
    }

    @Test
    fun `the documented margins are the same basis points in every configuration`() {
        assertEquals(Margin.Defined(BasisPoints(2_000)), margin(10_000, 8_000))
        assertEquals(Margin.Defined(BasisPoints(967)), margin(1_500, 1_355))
        assertEquals(Margin.Defined(BasisPoints(-2_000)), margin(1_000, 1_200))
        assertEquals(Margin.NoData, margin(0, 1_200))
    }

    @Test
    fun `the margin is measured without tax whatever the tax mode of the row`() {
        // Un precio de 10000 sin impuesto, que con 21 % incluido se escribe 12100.
        val writtenPrice = if (row.taxMode == TaxMode.INCLUDED) 12_100L else 10_000L
        val net = TaxCalculator.catalogNetPrice(Money(writtenPrice), row.taxMode, TAX_RATE)
        assertEquals(Checked.Value(Money(10_000L)), net)
        assertEquals(
            Margin.Defined(BasisPoints(2_000)),
            MarginCalculator.margin(requireNotNull(net.valueOrNull()), Money(8_000)),
        )
        // Sobre el precio escrito, con el impuesto dentro, la ganancia parecería mayor.
        if (row.taxMode == TaxMode.INCLUDED) {
            assertEquals(Margin.Defined(BasisPoints(3_388)), margin(writtenPrice, 8_000))
        }
    }

    @Test
    fun `the suggested price follows the tax mode of the row`() {
        // Solo con impuesto incluido el precio lleva la tasa: si se suma al cobrar, no.
        val includedRate =
            when (row.taxMode) {
                TaxMode.INCLUDED -> TAX_RATE
                TaxMode.NONE, TaxMode.ADDED -> BasisPoints.ZERO
            }
        val base = ExactUnitCost.perUnit(Money(1_000))
        val expected = if (row.taxMode == TaxMode.INCLUDED) 1_730L else 1_429L

        assertEquals(
            PriceSuggestion.Suggested(Money(expected)),
            MarginCalculator.suggestedPrice(base, BasisPoints(3_000), includedRate, Money.ZERO),
        )
        assertEquals(
            PriceSuggestion.Suggested(Money(2_000)),
            MarginCalculator.suggestedPrice(
                ExactUnitCost.perUnit(Money(1_300)),
                BasisPoints(3_500),
                BasisPoints.ZERO,
                Money.ZERO,
            ),
        )
        assertEquals(
            PriceSuggestion.Suggested(Money(2_000)),
            MarginCalculator.suggestedPrice(
                ExactUnitCost.perUnit(Money(1_355)),
                BasisPoints(3_000),
                BasisPoints.ZERO,
                Money(100),
            ),
        )
    }

    private fun margin(price: Long, cost: Long): Margin =
        MarginCalculator.margin(Money(price), Money(cost))

    companion object {
        /** La tasa del ejemplo de impuesto incluido de las reglas: 21 %. */
        private val TAX_RATE = BasisPoints(2_100)

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun parameters(): List<Array<Any>> = RegionalMatrix.withForeignDefaults()
    }
}
