package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import java.math.BigInteger
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarginCalculatorTest {
    @Test
    fun `margin examples are rounded to the nearest basis point`() {
        assertEquals(defined(2000L), MarginCalculator.margin(Money(10_000L), Money(8000L)))
        // Exacto 966,67: se muestra 9,7 %.
        assertEquals(defined(967L), MarginCalculator.margin(Money(1500L), Money(1355L)))
    }

    @Test
    fun `selling below cost gives a negative margin`() {
        assertEquals(defined(-2000L), MarginCalculator.margin(Money(1000L), Money(1200L)))
    }

    @Test
    fun `a price of zero has no margin instead of a division by zero`() {
        assertEquals(Margin.NoData, MarginCalculator.margin(Money.ZERO, Money(1200L)))
        assertEquals(Margin.NoData, MarginCalculator.margin(Money.ZERO, Money.ZERO))
    }

    @Test
    fun `a negative price has no margin either`() {
        assertEquals(Margin.NoData, MarginCalculator.margin(Money(-1L), Money.ZERO))
    }

    @Test
    fun `the margin is measured on the price and never on the cost`() {
        // Con precio 10000 y costo 8000, el sobreprecio sería 2500. La app solo muestra margen.
        assertEquals(defined(2000L), MarginCalculator.margin(Money(10_000L), Money(8000L)))
    }

    @Test
    fun `raising the cost by thirty percent does not give a thirty percent margin`() {
        // 1000 × 1,30 = 1300 deja 23,08 %, exacto 2307,69. Para 30 % el precio es 1429.
        assertEquals(defined(2308L), MarginCalculator.margin(Money(1300L), Money(1000L)))
        assertEquals(suggested(1429L), suggest(base = 1000L, targetBp = 3000L))
    }

    @Test
    fun `a zero cost gives a full margin and selling at cost gives none`() {
        assertEquals(defined(10_000L), MarginCalculator.margin(Money(1000L), Money.ZERO))
        assertEquals(defined(0L), MarginCalculator.margin(Money(1000L), Money(1000L)))
    }

    @Test
    fun `a margin over the highest document total needs 128 bits and fits`() {
        // (10^15 − 0) × 10000 = 10^19 no cabe en Long, pero el margen, 10000, sí.
        assertEquals(
            defined(10_000L),
            MarginCalculator.margin(Money(1_000_000_000_000_000L), Money.ZERO),
        )
    }

    @Test
    fun `a margin that does not fit in a long is out of range`() {
        // (1 − 10^15) × 10000 / 1 es casi −10^19.
        assertEquals(
            Margin.OutOfRange,
            MarginCalculator.margin(Money(1L), Money(1_000_000_000_000_000L)),
        )
        // La resta misma, 1 − (−(2^63 − 1)), ya no cabe en Long.
        assertEquals(
            Margin.OutOfRange,
            MarginCalculator.margin(Money(1L), Money(Long.MIN_VALUE + 1L)),
        )
    }

    @Test
    fun `the catalog cost is the stock value over the stock`() {
        // 24 unidades por 10000 dan 416,67 por unidad. El promedio guardado aquí es otro a
        // propósito, para ver que no se usa.
        assertEquals(
            Checked.Value(Money(417L)),
            MarginCalculator.catalogUnitCost(
                onHand = QuantityMilli(24_000L),
                stockValue = Money(10_000L),
                averageCost = Money(999L),
            ),
        )
    }

    @Test
    fun `a box of a thousand units for 400 rounds to zero per unit but still has cost`() {
        // 0,4 por unidad. El costo existe: lo dice el valor del stock, no el costo por unidad.
        assertEquals(
            Checked.Value(Money.ZERO),
            MarginCalculator.catalogUnitCost(
                onHand = QuantityMilli(1_000_000L),
                stockValue = Money(400L),
                averageCost = Money.ZERO,
            ),
        )
    }

    @Test
    fun `the catalog cost of the margin example matches its average cost`() {
        // 11 unidades a 1355 valen 14905. El margen con precio 1500 es el del ejemplo: 9,7 %.
        val cost =
            MarginCalculator.catalogUnitCost(
                onHand = QuantityMilli(11_000L),
                stockValue = Money(14_905L),
                averageCost = Money(1355L),
            )

        assertEquals(Checked.Value(Money(1355L)), cost)
        assertEquals(defined(967L), MarginCalculator.margin(Money(1500L), Money(1355L)))
    }

    @Test
    fun `without stock or without stock value the catalog cost is the average cost`() {
        val averageCost = Money(1355L)

        assertEquals(
            Checked.Value(averageCost),
            MarginCalculator.catalogUnitCost(QuantityMilli.ZERO, Money.ZERO, averageCost),
        )
        assertEquals(
            Checked.Value(averageCost),
            MarginCalculator.catalogUnitCost(QuantityMilli(-2000L), Money.ZERO, averageCost),
        )
        assertEquals(
            Checked.Value(averageCost),
            MarginCalculator.catalogUnitCost(QuantityMilli(11_000L), Money.ZERO, averageCost),
        )
    }

    @Test
    fun `the highest stock value over one thousandth still fits`() {
        // 9 × 10^15 × 1000 / 1 = 9 × 10^18.
        assertEquals(
            Checked.Value(Money(9_000_000_000_000_000_000L)),
            MarginCalculator.catalogUnitCost(
                onHand = QuantityMilli(1L),
                stockValue = Money(9_000_000_000_000_000L),
                averageCost = Money.ZERO,
            ),
        )
    }

    @Test
    fun `a catalog cost that does not fit in a long is an overflow`() {
        assertEquals(
            Checked.Overflow,
            MarginCalculator.catalogUnitCost(QuantityMilli(1L), Money(Long.MAX_VALUE), Money.ZERO),
        )
    }

    @Test
    fun `the suggestion starts from the replacement cost when it is known`() {
        // El producto tiene costo de catálogo 1355, pero volver a comprarlo cuesta 1500.
        val base = MarginCalculator.suggestionBase(
            replacementCost = Money(1500L),
            onHand = QuantityMilli(11_000L),
            stockValue = Money(14_905L),
            averageCost = Money(1355L),
        )

        assertEquals(ExactUnitCost.perUnit(Money(1500L)), base)
        // 1500 a 30 %: exacto 2142,86.
        assertEquals(suggested(2143L), suggestFrom(base, targetBp = 3000L))
    }

    @Test
    fun `without a replacement cost the suggestion starts from the stock value over the stock`() {
        // 11 unidades que valen 14905: 1355 por unidad, el costo del ejemplo de margen.
        val base = MarginCalculator.suggestionBase(
            replacementCost = Money.ZERO,
            onHand = QuantityMilli(11_000L),
            stockValue = Money(14_905L),
            averageCost = Money(999L),
        )

        assertEquals(ExactUnitCost(Money(14_905L), QuantityMilli(11_000L)), base)
        // 1355 a 30 %: exacto 1935,71. Con paso 100, 2000.
        assertEquals(suggested(1936L), suggestFrom(base, targetBp = 3000L))
        assertEquals(suggested(2000L), suggestFrom(base, targetBp = 3000L, step = 100L))
    }

    @Test
    fun `without stock or without stock value the suggestion starts from the average cost`() {
        val averageCost = Money(1300L)
        val withoutStock = MarginCalculator.suggestionBase(
            Money.ZERO,
            QuantityMilli.ZERO,
            Money.ZERO,
            averageCost,
        )
        val negativeStock = MarginCalculator.suggestionBase(
            Money.ZERO,
            QuantityMilli(-2000L),
            Money.ZERO,
            averageCost,
        )
        val withoutValue = MarginCalculator.suggestionBase(
            Money.ZERO,
            QuantityMilli(5000L),
            Money.ZERO,
            averageCost,
        )

        assertEquals(ExactUnitCost.perUnit(averageCost), withoutStock)
        assertEquals(ExactUnitCost.perUnit(averageCost), negativeStock)
        assertEquals(ExactUnitCost.perUnit(averageCost), withoutValue)
        // 1300 a 35 %: exacto 2000.
        assertEquals(suggested(2000L), suggestFrom(withoutStock, targetBp = 3500L))
    }

    @Test
    fun `a box of a thousand units for 400 suggests a price from its cost of four tenths`() {
        // Sin costo de reposición. El costo existe aunque por unidad redondee a cero, y la
        // sugerencia no pide corregirlo.
        val base = MarginCalculator.suggestionBase(
            replacementCost = Money.ZERO,
            onHand = QuantityMilli(1_000_000L),
            stockValue = Money(400L),
            averageCost = Money.ZERO,
        )

        assertEquals(ExactUnitCost(Money(400L), QuantityMilli(1_000_000L)), base)
        // 0,4 a 0 %, 30 %, 90 % y 99 %: exactos 0,4, 0,57, 4 y 40.
        assertEquals(suggested(1L), suggestFrom(base, targetBp = 0L))
        assertEquals(suggested(1L), suggestFrom(base, targetBp = 3000L))
        assertEquals(suggested(4L), suggestFrom(base, targetBp = 9000L))
        assertEquals(suggested(40L), suggestFrom(base, targetBp = 9900L))
        // 1 con 21 % incluido: 1,21 sube a 2. Con paso 50, 50; y sin impuesto, con paso 100, 100.
        assertEquals(suggested(2L), suggestFrom(base, targetBp = 3000L, taxBp = 2100L))
        assertEquals(
            suggested(50L),
            suggestFrom(base, targetBp = 3000L, taxBp = 2100L, step = 50L),
        )
        assertEquals(suggested(100L), suggestFrom(base, targetBp = 3000L, step = 100L))
    }

    @Test
    fun `a box of 24 for 10000 suggests from its exact cost and not from 417`() {
        val base = MarginCalculator.suggestionBase(
            replacementCost = Money.ZERO,
            onHand = QuantityMilli(24_000L),
            stockValue = Money(10_000L),
            averageCost = Money(417L),
        )

        // 416,67 a 90 %: exacto 4166,67. Con la base redondeada a 417 serían 4170.
        assertEquals(suggested(4167L), suggestFrom(base, targetBp = 9000L))
        assertEquals(suggested(4170L), suggest(base = 417L, targetBp = 9000L))
        // A 99 %: exacto 41666,67, y 41700 desde 417.
        assertEquals(suggested(41_667L), suggestFrom(base, targetBp = 9900L))
        assertEquals(suggested(41_700L), suggest(base = 417L, targetBp = 9900L))
        // A 30 %, exacto 595,24, las dos bases coinciden. Sin margen, 416,67 sube a 417.
        assertEquals(suggested(596L), suggestFrom(base, targetBp = 3000L))
        assertEquals(suggested(417L), suggestFrom(base, targetBp = 0L))
        // 4167 con paso 100 sube a 4200.
        assertEquals(suggested(4200L), suggestFrom(base, targetBp = 9000L, step = 100L))
    }

    @Test
    fun `the average cost example suggests from its stock value and not from its rounded cost`() {
        // 11 unidades que valen 14900: 1354,55 por unidad, guardado como 1355.
        val base = MarginCalculator.suggestionBase(
            replacementCost = Money.ZERO,
            onHand = QuantityMilli(11_000L),
            stockValue = Money(14_900L),
            averageCost = Money(1355L),
        )

        // A 30 %: exacto 1935,06. A 99 %: exacto 135454,55, y 135500 desde 1355.
        assertEquals(suggested(1936L), suggestFrom(base, targetBp = 3000L))
        assertEquals(suggested(135_455L), suggestFrom(base, targetBp = 9900L))
        assertEquals(suggested(135_500L), suggest(base = 1355L, targetBp = 9900L))
    }

    @Test
    fun `a product sold by weight suggests from its exact cost per kilogram`() {
        // 4,250 kg que valen 136250: 32058,82 por kilogramo. A 30 %: exacto 45798,32.
        val base = MarginCalculator.suggestionBase(
            replacementCost = Money.ZERO,
            onHand = QuantityMilli(4250L),
            stockValue = Money(136_250L),
            averageCost = Money(32_059L),
        )

        assertEquals(suggested(45_799L), suggestFrom(base, targetBp = 3000L))
    }

    @Test
    fun `suggested prices round up so the margin never falls below the target`() {
        // Base, margen objetivo, sugerido, sugerido con paso 100 y margen resultante de cada uno.
        // 1300 a 35 %: exacto 2000. Deja 35,00 % con y sin paso.
        assertEquals(suggested(2000L), suggest(base = 1300L, targetBp = 3500L))
        assertEquals(suggested(2000L), suggest(base = 1300L, targetBp = 3500L, step = 100L))
        assertEquals(defined(3500L), MarginCalculator.margin(Money(2000L), Money(1300L)))

        // 1355 a 30 %: exacto 1935,71. Deja 30,01 % sin paso (exacto 3001,03) y 32,25 % con paso.
        assertEquals(suggested(1936L), suggest(base = 1355L, targetBp = 3000L))
        assertEquals(suggested(2000L), suggest(base = 1355L, targetBp = 3000L, step = 100L))
        assertEquals(defined(3001L), MarginCalculator.margin(Money(1936L), Money(1355L)))
        assertEquals(defined(3225L), MarginCalculator.margin(Money(2000L), Money(1355L)))

        // 1000 a 30 %: exacto 1428,57. Deja 30,02 % sin paso (exacto 3002,1) y 33,33 % con paso.
        assertEquals(suggested(1429L), suggest(base = 1000L, targetBp = 3000L))
        assertEquals(suggested(1500L), suggest(base = 1000L, targetBp = 3000L, step = 100L))
        assertEquals(defined(3002L), MarginCalculator.margin(Money(1429L), Money(1000L)))
        assertEquals(defined(3333L), MarginCalculator.margin(Money(1500L), Money(1000L)))
    }

    @Test
    fun `a price with tax included carries the tax on top of the suggestion`() {
        // 1429 con 21 % incluido: 1429 × 1,21 = 1729,09, que sube a 1730.
        assertEquals(suggested(1730L), suggest(base = 1000L, targetBp = 3000L, taxBp = 2100L))
    }

    @Test
    fun `the rounding step applies to the price with its tax`() {
        // 1730 con paso 100 sube a 1800.
        assertEquals(
            suggested(1800L),
            suggest(base = 1000L, targetBp = 3000L, taxBp = 2100L, step = 100L),
        )
    }

    @Test
    fun `a bulk change to a target margin uses the same formula`() {
        // 1500 a 30 %: exacto 2142,86.
        assertEquals(suggested(2143L), suggest(base = 1500L, targetBp = 3000L))
    }

    @Test
    fun `the target margin goes from zero to ninety nine percent`() {
        assertEquals(suggested(1355L), suggest(base = 1355L, targetBp = 0L))
        // 1355 × 10000 / 100.
        assertEquals(suggested(135_500L), suggest(base = 1355L, targetBp = 9900L))
        assertEquals(
            PriceSuggestion.TargetMarginOutOfRange,
            suggest(base = 1355L, targetBp = 9901L),
        )
        assertEquals(PriceSuggestion.TargetMarginOutOfRange, suggest(base = 1355L, targetBp = -1L))
    }

    @Test
    fun `without a known cost there is no suggestion`() {
        assertEquals(PriceSuggestion.NoCost, suggest(base = 0L, targetBp = 3000L))
        assertEquals(PriceSuggestion.NoCost, suggest(base = -5L, targetBp = 3000L))
        // Ni costo de reposición, ni valor de stock, ni costo promedio: con stock o sin él.
        for (onHand in listOf(QuantityMilli.ZERO, QuantityMilli(5000L))) {
            val base = MarginCalculator.suggestionBase(Money.ZERO, onHand, Money.ZERO, Money.ZERO)
            assertEquals(PriceSuggestion.NoCost, suggestFrom(base, targetBp = 3000L))
        }
    }

    @Test
    fun `a tax of one hundred percent doubles the price`() {
        assertEquals(suggested(2000L), suggest(base = 1000L, targetBp = 0L, taxBp = 10_000L))
    }

    @Test
    fun `a tax rate outside zero to one hundred percent is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            suggest(base = 1000L, targetBp = 3000L, taxBp = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            suggest(base = 1000L, targetBp = 3000L, taxBp = 10_001L)
        }
    }

    @Test
    fun `a negative rounding step is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            suggest(base = 1000L, targetBp = 3000L, step = -1L)
        }
    }

    @Test
    fun `a suggestion at the price cap is accepted and one above it is rejected`() {
        assertEquals(suggested(MAX_UNIT_MONEY), suggest(base = MAX_UNIT_MONEY, targetBp = 0L))
        // 999999999999 con paso 1000 sube justo al tope.
        assertEquals(
            suggested(MAX_UNIT_MONEY),
            suggest(base = MAX_UNIT_MONEY - 1L, targetBp = 0L, step = 1000L),
        )
        assertEquals(
            PriceSuggestion.AbovePriceLimit,
            suggest(base = MAX_UNIT_MONEY + 1L, targetBp = 0L),
        )
        // 10^16 / 9900 = 1010101010101,01, sobre el tope.
        assertEquals(
            PriceSuggestion.AbovePriceLimit,
            suggest(base = MAX_UNIT_MONEY, targetBp = 100L),
        )
        // 10^12 más un punto básico de impuesto da 1000100000000.
        assertEquals(
            PriceSuggestion.AbovePriceLimit,
            suggest(base = MAX_UNIT_MONEY, targetBp = 0L, taxBp = 1L),
        )
        // 10^12 no es múltiplo de 3: el siguiente es 1000000000002.
        assertEquals(
            PriceSuggestion.AbovePriceLimit,
            suggest(base = MAX_UNIT_MONEY, targetBp = 0L, step = 3L),
        )
    }

    @Test
    fun `a fractional cost is checked against the price cap without rounding it first`() {
        // 10^15 por mil unidades es exactamente el precio máximo por unidad.
        val atCap = ExactUnitCost(Money(1_000_000_000_000_000L), QuantityMilli(1_000_000L))
        // Una unidad menor más reparte 0,001 por unidad, que ya pasa el tope.
        val aboveCap = ExactUnitCost(Money(1_000_000_000_000_001L), QuantityMilli(1_000_000L))

        assertEquals(suggested(MAX_UNIT_MONEY), suggestFrom(atCap, targetBp = 0L))
        assertEquals(PriceSuggestion.AbovePriceLimit, suggestFrom(aboveCap, targetBp = 0L))
    }

    @Test
    fun `a cost too large to multiply is rejected as above the cap without overflowing`() {
        // El valor de stock máximo en una milésima: 9 × 10^18 por unidad, por 10000 no cabe en Long.
        val base = ExactUnitCost(Money(9_000_000_000_000_000L), QuantityMilli(1L))

        assertEquals(PriceSuggestion.AbovePriceLimit, suggestFrom(base, targetBp = 3000L))
    }

    @Test
    fun `the highest base with the highest margin and tax is rejected without overflowing`() {
        // 10^12 a 99 % da 10^14, y con 100 % de impuesto, 2 × 10^14: muy lejos del máximo de Long.
        assertEquals(
            PriceSuggestion.AbovePriceLimit,
            suggest(base = MAX_UNIT_MONEY, targetBp = 9900L, taxBp = 10_000L, step = 1000L),
        )
    }

    @Test
    fun `the suggestion is the lowest price that reaches the target on seeded cases`() {
        val random = Random(20_261_103)
        val anyRange = List(6000) { anyCost(random) }
        val belowOneMinorUnit = List(2000) { costBelowOneMinorUnit(random) }
        val wholeUnits = List(2000) { wholeUnitCost(random) }
        val costs = anyRange + belowOneMinorUnit + wholeUnits

        assertEquals(10_000, costs.size)
        for (base in costs) {
            val target = random.nextLong(0L, 9901L)
            val tax = if (random.nextBoolean()) 0L else random.nextLong(0L, 10_001L)
            val step = if (random.nextBoolean()) 0L else random.nextLong(1L, 100_001L)
            val result = suggestFrom(base, targetBp = target, taxBp = tax, step = step)
            // Exacto: el menor precio sin impuesto con precio × (10000 − margen) >= costo × 10000,
            // y el costo es monto × 1000 / cantidad. Una sola división, sin techos intermedios.
            val amount = BigInteger.valueOf(base.amount.minor)
            val quantity = BigInteger.valueOf(base.quantity.milli)
            val keep = BigInteger.valueOf(10_000L - target)
            val lowest = ceilDiv(amount * BigInteger.valueOf(10_000_000L), quantity * keep)
            val taxFactor = BigInteger.valueOf(10_000L + tax)
            val withTax = ceilDiv(lowest * taxFactor, BigInteger.valueOf(10_000L))
            val expected =
                if (step == 0L) withTax else ceilToMultiple(withTax, BigInteger.valueOf(step))
            val case = "costo ${base.amount.minor} por ${base.quantity.milli}, margen $target, " +
                "impuesto $tax, paso $step"

            if (expected > BigInteger.valueOf(MAX_UNIT_MONEY)) {
                assertEquals(case, PriceSuggestion.AbovePriceLimit, result)
            } else {
                assertEquals(case, suggested(expected.toLong()), result)
                // Sin el impuesto, el precio alcanza el margen contra el costo exacto:
                // precio × 10000 / (10000 + impuesto) × (10000 − margen) >= costo × 10000.
                val reached = expected * BigInteger.valueOf(10_000L) * keep * quantity
                val needed = amount * BigInteger.valueOf(10_000_000L) * taxFactor
                assertTrue(case, reached >= needed)
                // Con un costo entero y sin impuesto, el margen que muestra la app también llega.
                if (base.quantity == QuantityMilli(1000L) && tax == 0L) {
                    val margin = MarginCalculator.margin(Money(expected.toLong()), base.amount)
                    assertTrue(case, margin is Margin.Defined && margin.value.bp >= target)
                }
            }
        }
    }

    @Test
    fun `a month without returns or fees`() {
        val summary =
            profit(
                salesNetTotal = 4_250_000L,
                salesTotalCost = 2_720_000L,
                recordedExpenses = 640_000L,
                netShrinkage = 85_000L,
            )

        assertEquals(
            Checked.Value(
                ProfitSummary(
                    netSales = Money(4_250_000L),
                    cogs = Money(2_720_000L),
                    grossProfit = Money(1_530_000L),
                    grossMargin = defined(3600L),
                    recordedExpenses = Money(640_000L),
                    netShrinkage = Money(85_000L),
                    paymentCosts = Money.ZERO,
                    totalExpenses = Money(725_000L),
                    netProfit = Money(805_000L),
                ),
            ),
            summary,
        )
    }

    @Test
    fun `the same month with returns fees and cash rounding`() {
        val summary =
            profit(
                salesNetTotal = 4_250_000L,
                returnsNetRefund = 50_000L,
                salesTotalCost = 2_720_000L,
                returnsCostReturned = 30_000L,
                recordedExpenses = 640_000L,
                netShrinkage = 85_000L,
                paymentFees = 42_000L,
                cashRoundingTotal = -300L,
            )

        // Margen exacto 3595,24: se muestra 35,95 %. Costo de cobrar 42000 − (−300).
        assertEquals(
            Checked.Value(
                ProfitSummary(
                    netSales = Money(4_200_000L),
                    cogs = Money(2_690_000L),
                    grossProfit = Money(1_510_000L),
                    grossMargin = defined(3595L),
                    recordedExpenses = Money(640_000L),
                    netShrinkage = Money(85_000L),
                    paymentCosts = Money(42_300L),
                    totalExpenses = Money(767_300L),
                    netProfit = Money(742_700L),
                ),
            ),
            summary,
        )
    }

    @Test
    fun `profit and margin cases with sales cost and expenses`() {
        // Ventas, costo, gastos, merma, ganancia en ventas, ganancia final y margen.
        assertFigures(profit(10_000L, salesTotalCost = 8000L), 2000L, 2000L, defined(2000L))
        assertFigures(profit(1000L, salesTotalCost = 1200L), -200L, -200L, defined(-2000L))
        assertFigures(profit(0L, recordedExpenses = 5000L), 0L, -5000L, Margin.NoData)
    }

    @Test
    fun `a period without data gives zeros and no margin`() {
        assertEquals(
            Checked.Value(
                ProfitSummary(
                    netSales = Money.ZERO,
                    cogs = Money.ZERO,
                    grossProfit = Money.ZERO,
                    grossMargin = Margin.NoData,
                    recordedExpenses = Money.ZERO,
                    netShrinkage = Money.ZERO,
                    paymentCosts = Money.ZERO,
                    totalExpenses = Money.ZERO,
                    netProfit = Money.ZERO,
                ),
            ),
            profit(0L),
        )
    }

    @Test
    fun `one sale with card fee and cash rounding adds to the final profit`() {
        // Venta sin impuesto 4437, costo 2500, comisión 83 y redondeo de efectivo +1.
        val summary =
            profit(4437L, salesTotalCost = 2500L, paymentFees = 83L, cashRoundingTotal = 1L)

        // Margen exacto 4365,56. Costo de cobrar 83 − 1.
        assertFigures(summary, 1937L, 1855L, defined(4366L))
        assertEquals(Money(82L), summary.value().paymentCosts)
    }

    @Test
    fun `a surplus in stock counts reduces the expenses of the period`() {
        val summary = profit(0L, recordedExpenses = 1000L, netShrinkage = -300L)

        assertEquals(Money(700L), summary.value().totalExpenses)
        assertEquals(Money(-700L), summary.value().netProfit)
    }

    @Test
    fun `rounding cash up lowers the cost of collecting payments`() {
        val summary = profit(0L, cashRoundingTotal = 500L)

        assertEquals(Money(-500L), summary.value().paymentCosts)
        assertEquals(Money(500L), summary.value().netProfit)
    }

    @Test
    fun `returns of earlier sales can leave a period with negative sales and no margin`() {
        val summary = profit(0L, returnsNetRefund = 5000L, returnsCostReturned = 3000L)

        assertEquals(Money(-5000L), summary.value().netSales)
        assertEquals(Money(-3000L), summary.value().cogs)
        assertFigures(summary, -2000L, -2000L, Margin.NoData)
    }

    @Test
    fun `the period margin comes from the sums and is never an average`() {
        // Una venta de 10000 con costo 8000 (20 %) y otra de 1000 con costo 100 (90 %). El
        // promedio sería 55 %; con las sumas, 2900 / 11000 da 26,36 %.
        val summary = profit(11_000L, salesTotalCost = 8100L)

        assertEquals(defined(2636L), summary.value().grossMargin)
    }

    @Test
    fun `an absurd cost keeps the figures of the period without a margin`() {
        val summary = profit(1L, salesTotalCost = 1_000_000_000_000_000L)

        assertEquals(Money(-999_999_999_999_999L), summary.value().grossProfit)
        assertEquals(Margin.OutOfRange, summary.value().grossMargin)
    }

    @Test
    fun `figures that do not fit in a long are an overflow`() {
        assertEquals(Checked.Overflow, profit(Long.MAX_VALUE, returnsNetRefund = -1L))
        assertEquals(
            Checked.Overflow,
            profit(0L, recordedExpenses = Long.MAX_VALUE, netShrinkage = 1L),
        )
    }

    private fun defined(bp: Long): Margin = Margin.Defined(BasisPoints(bp))

    private fun suggested(price: Long): PriceSuggestion = PriceSuggestion.Suggested(Money(price))

    /** Sugerencia desde un costo entero por unidad, como el de reposición. */
    private fun suggest(base: Long, targetBp: Long, taxBp: Long = 0L, step: Long = 0L) =
        suggestFrom(ExactUnitCost.perUnit(Money(base)), targetBp, taxBp, step)

    private fun suggestFrom(
        base: ExactUnitCost,
        targetBp: Long,
        taxBp: Long = 0L,
        step: Long = 0L,
    ): PriceSuggestion = MarginCalculator.suggestedPrice(
        base = base,
        targetMargin = BasisPoints(targetBp),
        includedTaxRate = BasisPoints(taxBp),
        roundingStep = Money(step),
    )

    private fun ceilDiv(value: BigInteger, divisor: BigInteger): BigInteger {
        val (quotient, remainder) = value.divideAndRemainder(divisor)
        return if (remainder.signum() == 0) quotient else quotient + BigInteger.ONE
    }

    private fun ceilToMultiple(value: BigInteger, step: BigInteger): BigInteger =
        ceilDiv(value, step) * step

    /**
     * Un costo de cualquier tamaño: el monto llega a 2^54, sobre el valor de stock máximo, y la
     * cantidad a 2^34, sobre el stock máximo. Casi siempre es fraccionario.
     */
    private fun anyCost(random: Random): ExactUnitCost =
        ExactUnitCost(Money(randomBits(random, 54)), QuantityMilli(randomBits(random, 34)))

    /** Menos de una unidad menor por unidad, como la caja de 1000 por 400. */
    private fun costBelowOneMinorUnit(random: Random): ExactUnitCost {
        val quantity = random.nextLong(2000L, 8_000_000_001L)
        return ExactUnitCost(Money(random.nextLong(1L, quantity / 1000L)), QuantityMilli(quantity))
    }

    /** Un costo entero por unidad, como el de reposición, hasta el precio máximo. */
    private fun wholeUnitCost(random: Random): ExactUnitCost =
        ExactUnitCost.perUnit(Money(random.nextLong(1L, MAX_UNIT_MONEY + 1L)))

    /** Un número positivo con una cantidad de bits al azar, para cruzar tamaños muy distintos. */
    private fun randomBits(random: Random, maxBits: Int): Long {
        val bits = random.nextInt(1, maxBits + 1)
        return (random.nextLong() ushr (64 - bits)).coerceAtLeast(1L)
    }

    private fun profit(
        salesNetTotal: Long,
        returnsNetRefund: Long = 0L,
        salesTotalCost: Long = 0L,
        returnsCostReturned: Long = 0L,
        recordedExpenses: Long = 0L,
        netShrinkage: Long = 0L,
        paymentFees: Long = 0L,
        cashRoundingTotal: Long = 0L,
    ): Checked<ProfitSummary> = MarginCalculator.profit(
        ProfitTotals(
            salesNetTotal = Money(salesNetTotal),
            returnsNetRefund = Money(returnsNetRefund),
            salesTotalCost = Money(salesTotalCost),
            returnsCostReturned = Money(returnsCostReturned),
            recordedExpenses = Money(recordedExpenses),
            netShrinkage = Money(netShrinkage),
            paymentFees = Money(paymentFees),
            cashRoundingTotal = Money(cashRoundingTotal),
        ),
    )

    private fun Checked<ProfitSummary>.value(): ProfitSummary =
        (this as? Checked.Value)?.value ?: throw AssertionError("Se esperaba un valor: $this")

    private fun assertFigures(
        summary: Checked<ProfitSummary>,
        grossProfit: Long,
        netProfit: Long,
        margin: Margin,
    ) {
        assertEquals(Money(grossProfit), summary.value().grossProfit)
        assertEquals(Money(netProfit), summary.value().netProfit)
        assertEquals(margin, summary.value().grossMargin)
    }

    private companion object {
        const val MAX_UNIT_MONEY: Long = 1_000_000_000_000L
    }
}
