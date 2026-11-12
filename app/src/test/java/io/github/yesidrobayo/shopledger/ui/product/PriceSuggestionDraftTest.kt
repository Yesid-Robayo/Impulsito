package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductPricing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lo que dice la sugerencia de precio con cada margen escrito. Datos inventados. */
class PriceSuggestionDraftTest {
    private val pricing = ProductPricing(
        cost = ExactUnitCost.perUnit(Money(1_355L)),
        suggestionBase = ExactUnitCost.perUnit(Money(1_355L)),
        targetMargin = BasisPoints(3_050L),
        taxMode = TaxMode.NONE,
        taxRate = BasisPoints.ZERO,
        roundingStep = Money(100L),
    )

    @Test
    fun `it opens with the target written as a person would write it`() {
        val draft = PriceSuggestionDraft.open(pricing, SYMBOLS)

        assertEquals("30,5", draft.targetText)
        // 1355 con 30,5 % pide 1950, y el paso de 100 lo sube a 2000: 32,25 %.
        assertEquals(
            SuggestionOutcome.Suggested(Money(2_000L), Margin.Defined(BasisPoints(3_225L))),
            draft.outcome,
        )
        assertEquals(Money(2_000L), draft.price)
    }

    @Test
    fun `each problem of the written target is told apart`() {
        assertEquals(SuggestionOutcome.Blank, read(""))
        assertEquals(invalid(TargetMarginProblem.NOT_A_NUMBER), read("3,0,0"))
        assertEquals(invalid(TargetMarginProblem.TOO_MANY_DECIMALS), read("30,123"))
        assertEquals(invalid(TargetMarginProblem.OUT_OF_RANGE), read("99,01"))
        assertEquals(invalid(TargetMarginProblem.OUT_OF_RANGE), read("-5"))
        assertNull(PriceSuggestionDraft("", SuggestionOutcome.Blank).price)
    }

    @Test
    fun `a price above the limit and a missing cost are said`() {
        val huge = pricing.copy(suggestionBase = ExactUnitCost.perUnit(Money(900_000_000_000L)))
        assertEquals(
            SuggestionOutcome.AbovePriceLimit,
            PriceSuggestionDraft.read("99", huge, SYMBOLS),
        )

        val none = pricing.copy(cost = null, suggestionBase = ExactUnitCost.perUnit(Money.ZERO))
        assertEquals(SuggestionOutcome.NoCost, PriceSuggestionDraft.read("30", none, SYMBOLS))
    }

    private fun read(text: String) = PriceSuggestionDraft.read(text, pricing, SYMBOLS)

    private fun invalid(problem: TargetMarginProblem) = SuggestionOutcome.InvalidTarget(problem)

    private companion object {
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    }
}
