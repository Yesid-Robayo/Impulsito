package io.github.yesidrobayo.shopledger.domain.rule.money

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyInputError
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import org.junit.Assert.assertEquals
import org.junit.Test

/** El monto y la nota del dinero que la persona registra a mano, con los bordes escritos a mano. */
class ManualMoneyRulesTest {
    private val max = 1_000_000_000_000_000L

    @Test
    fun `a positive amount within the limit and a trimmed note are kept`() {
        assertEquals(
            ManualMoneyCheck.Valid(Money(300_000), "Casa"),
            ManualMoneyRules.check(Money(300_000), " Casa "),
        )
        assertEquals(
            ManualMoneyCheck.Valid(Money(max), null),
            ManualMoneyRules.check(Money(max), "   "),
        )
        assertEquals(ManualMoneyCheck.Valid(Money(1), null), ManualMoneyRules.check(Money(1), null))
        assertEquals(
            ManualMoneyCheck.Valid(Money(1), "x".repeat(200)),
            ManualMoneyRules.check(Money(1), "x".repeat(200)),
        )
    }

    @Test
    fun `zero, negative, above the limit and a long note are rejected together`() {
        assertEquals(
            ManualMoneyCheck.Invalid(
                setOf(
                    ManualMoneyInputError.AMOUNT_NOT_POSITIVE,
                    ManualMoneyInputError.NOTE_TOO_LONG,
                ),
            ),
            ManualMoneyRules.check(Money(0), "x".repeat(201)),
        )
        assertEquals(
            ManualMoneyCheck.Invalid(setOf(ManualMoneyInputError.AMOUNT_NOT_POSITIVE)),
            ManualMoneyRules.check(Money(-5), null),
        )
        assertEquals(
            ManualMoneyCheck.Invalid(setOf(ManualMoneyInputError.AMOUNT_TOO_LARGE)),
            ManualMoneyRules.check(Money(max + 1), null),
        )
    }

    @Test
    fun `a draw goes out and a contribution comes in`() {
        assertEquals(MoneyMovementType.OWNER_DRAW, ManualMoneyKind.OWNER_DRAW.type)
        assertEquals(MoneyMovementType.OWNER_CONTRIBUTION, ManualMoneyKind.OWNER_CONTRIBUTION.type)
    }
}
