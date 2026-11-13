package io.github.yesidrobayo.shopledger.domain.rule.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDetails
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDraft
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListInputError
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lo que se revisa de una lista antes de guardarla. */
class PriceListFormRulesTest {
    @Test
    fun `a fixed list keeps its trimmed name and drops what only a relative one uses`() {
        val check = PriceListFormRules.check(
            PriceListDraft("  Mayorista  ", PriceListKind.FIXED, 7L, BasisPoints(10L)),
        )

        assertEquals(
            PriceListFormCheck.Valid(
                PriceListDetails("Mayorista", "mayorista", PriceListPricing.Fixed),
            ),
            check,
        )
    }

    @Test
    fun `a relative list carries its base and adjustment, up to both limits`() {
        for (bp in listOf(-10_000L, 1_000_000L)) {
            val check = PriceListFormRules.check(relative(base = 7L, bp = bp))
            val expected = PriceListPricing.Relative(7L, BasisPoints(bp))
            assertEquals(expected, (check as PriceListFormCheck.Valid).details.pricing)
        }
    }

    @Test
    fun `every missing or wrong field is reported at once`() {
        assertEquals(
            PriceListFormCheck.Invalid(
                setOf(
                    PriceListInputError.NAME_EMPTY,
                    PriceListInputError.BASE_MISSING,
                    PriceListInputError.ADJUSTMENT_MISSING,
                ),
            ),
            PriceListFormRules.check(
                PriceListDraft("", PriceListKind.RELATIVE, null, null),
            ),
        )
        assertEquals(
            PriceListFormCheck.Invalid(
                setOf(
                    PriceListInputError.NAME_TOO_LONG,
                    PriceListInputError.ADJUSTMENT_OUT_OF_RANGE,
                ),
            ),
            PriceListFormRules.check(relative(7L, -10_001L, "x".repeat(41))),
        )
        assertEquals(
            PriceListFormCheck.Invalid(setOf(PriceListInputError.ADJUSTMENT_OUT_OF_RANGE)),
            PriceListFormRules.check(relative(7L, 1_000_001L)),
        )
        assertEquals(
            PriceListFormCheck.Invalid(setOf(PriceListInputError.BASE_MISSING)),
            PriceListFormRules.check(
                PriceListDraft("Especial", PriceListKind.RELATIVE, null, BasisPoints(5L)),
            ),
        )
        assertEquals(
            PriceListFormCheck.Invalid(setOf(PriceListInputError.ADJUSTMENT_MISSING)),
            PriceListFormRules.check(PriceListDraft("Especial", PriceListKind.RELATIVE, 7L, null)),
        )
        assertEquals(
            PriceListFormCheck.Invalid(setOf(PriceListInputError.NAME_EMPTY)),
            PriceListFormRules.check(relative(7L, 0L, name = " ")),
        )
    }

    @Test
    fun `forty characters fit, counting an emoji as one`() {
        val name = "😀" + "a".repeat(39)
        val check = PriceListFormRules.check(relative(7L, 0L, name))
        assertEquals(name, (check as PriceListFormCheck.Valid).details.name)
    }

    private fun relative(base: Long, bp: Long, name: String = "Especial") =
        PriceListDraft(name, PriceListKind.RELATIVE, base, BasisPoints(bp))
}
