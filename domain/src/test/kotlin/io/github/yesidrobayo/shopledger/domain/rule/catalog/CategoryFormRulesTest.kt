package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryFormIssue
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryFormRulesTest {
    @Test
    fun `a trimmed name with or without a target margin is valid`() {
        assertEquals(
            CategoryFormCheck.Valid("Lácteos", "lacteos"),
            CategoryFormRules.check(" Lácteos ", null),
        )
        assertEquals(
            CategoryFormCheck.Valid("Lácteos", "lacteos"),
            CategoryFormRules.check("Lácteos", BasisPoints.MAX_TARGET_MARGIN),
        )
        assertEquals(
            CategoryFormCheck.Valid("Lácteos", "lacteos"),
            CategoryFormRules.check("Lácteos", BasisPoints.ZERO),
        )
    }

    @Test
    fun `the name is required and allows 40 characters`() {
        assertEquals(
            CategoryFormCheck.Invalid(setOf(CategoryFormIssue.NAME_EMPTY)),
            CategoryFormRules.check(" ", null),
        )
        assertEquals(
            CategoryFormCheck.Valid("a".repeat(40), "a".repeat(40)),
            CategoryFormRules.check("a".repeat(40), null),
        )
        assertEquals(
            CategoryFormCheck.Invalid(setOf(CategoryFormIssue.NAME_TOO_LONG)),
            CategoryFormRules.check("a".repeat(41), null),
        )
    }

    @Test
    fun `the target margin goes from zero to 99 percent`() {
        assertEquals(
            CategoryFormCheck.Invalid(setOf(CategoryFormIssue.TARGET_MARGIN_OUT_OF_RANGE)),
            CategoryFormRules.check("Lácteos", BasisPoints(9_901L)),
        )
        assertEquals(
            CategoryFormCheck.Invalid(
                setOf(CategoryFormIssue.NAME_EMPTY, CategoryFormIssue.TARGET_MARGIN_OUT_OF_RANGE),
            ),
            CategoryFormRules.check("", BasisPoints(-1L)),
        )
    }
}
