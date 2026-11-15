package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El avance de las metas del mes, con el ejemplo de la regla y sus bordes. Datos inventados. */
class GoalRulesTest {
    @Test
    fun `850000 sold against a goal of 1000000 is 8500 basis points`() {
        val progress = GoalRules.progress(Money(850_000), Money(1_000_000))

        assertEquals(GoalAdvance(Money(850_000), Money(1_000_000), BasisPoints(8_500)), progress)
        assertFalse(progress.reached)
    }

    @Test
    fun `reaching the goal exactly counts as reached`() {
        val progress = GoalRules.progress(Money(1_000_000), Money(1_000_000))

        assertEquals(BasisPoints(10_000), progress.progress)
        assertTrue(progress.reached)
    }

    @Test
    fun `a goal surpassed goes past 100 percent`() {
        val progress = GoalRules.progress(Money(1_200_000), Money(1_000_000))

        assertEquals(BasisPoints(12_000), progress.progress)
        assertTrue(progress.reached)
    }

    @Test
    fun `a loss against a profit goal is a negative progress rounded away from zero`() {
        // −50000 × 10000 / 300000 = −1666,67: se redondea a −1667.
        val progress = GoalRules.progress(Money(-50_000), Money(300_000))

        assertEquals(BasisPoints(-1_667), progress.progress)
        assertFalse(progress.reached)
    }

    @Test
    fun `without what was achieved there is no progress and nothing is reached`() {
        val progress = GoalRules.progress(null, Money(1_000_000))

        assertEquals(GoalAdvance(null, Money(1_000_000), null), progress)
        assertFalse(progress.reached)
    }

    @Test
    fun `a goal of zero does not divide`() {
        assertEquals(null, GoalRules.progress(Money(5_000), Money.ZERO).progress)
        assertEquals(null, GoalRules.progress(Money(5_000), Money(-1)).progress)
    }

    @Test
    fun `a progress that does not fit in a long has no value`() {
        val progress = GoalRules.progress(GoalRules.MAX_GOAL, Money(1))

        assertEquals(null, progress.progress)
        assertTrue(progress.reached)
    }

    @Test
    fun `only goals above zero and within the ceiling can be saved`() {
        assertFalse(GoalRules.isValid(Money(-1)))
        assertFalse(GoalRules.isValid(Money.ZERO))
        assertTrue(GoalRules.isValid(Money(1)))
        assertTrue(GoalRules.isValid(Money(9_000_000_000_000_000L)))
        assertFalse(GoalRules.isValid(Money(9_000_000_000_000_001L)))
    }
}
