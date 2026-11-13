package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El libro de puntos: el saldo encadenado dentro del tope, el signo y el origen de cada tipo, la
 * compensación, el canje que no supera el saldo y lo ganado que no pasa del tope. Los ejemplos van
 * escritos a mano.
 */
class LoyaltyLedgerRulesTest {
    private fun draft(
        type: LoyaltyEntryType,
        points: Long,
        source: LoyaltyEntrySource = LoyaltyEntrySource.Sale(SALE),
        note: String? = null,
    ) = LoyaltyEntryDraft(CUSTOMER, type, points, source, note)

    @Test
    fun earningAddsToThePreviousBalance() {
        assertEquals(
            LoyaltyEntryCheck.Valid(62L),
            LoyaltyLedgerRules.next(50L, draft(LoyaltyEntryType.EARN, 12L)),
        )
    }

    @Test
    fun redeemingSubtractsAndMayStartANegativeBalanceOnlyByVoiding() {
        assertEquals(
            LoyaltyEntryCheck.Valid(50L),
            LoyaltyLedgerRules.next(150L, draft(LoyaltyEntryType.REDEEM, -100L)),
        )
        // Anular lo ganado tras canjearlo deja el saldo negativo, y se acepta.
        assertEquals(
            LoyaltyEntryCheck.Valid(-12L),
            LoyaltyLedgerRules.next(
                0L,
                draft(LoyaltyEntryType.ENTRY_VOID, -12L, LoyaltyEntrySource.Reverses(1L)),
            ),
        )
    }

    @Test
    fun zeroPointsAreRejected() {
        assertEquals(
            LoyaltyEntryCheck.Invalid(LoyaltyEntryIssue.ZERO_POINTS),
            LoyaltyLedgerRules.next(0L, draft(LoyaltyEntryType.EARN, 0L)),
        )
    }

    @Test
    fun eachTypeKeepsItsSign() {
        val wrong = LoyaltyEntryCheck.Invalid(LoyaltyEntryIssue.WRONG_SIGN)
        assertEquals(wrong, LoyaltyLedgerRules.next(0L, draft(LoyaltyEntryType.EARN, -1L)))
        assertEquals(wrong, LoyaltyLedgerRules.next(10L, draft(LoyaltyEntryType.REDEEM, 1L)))
        val none = LoyaltyEntrySource.None
        assertEquals(
            LoyaltyEntryCheck.Valid(-5L),
            LoyaltyLedgerRules.next(
                0L,
                draft(LoyaltyEntryType.ADJUSTMENT, -5L, none, "Corrección"),
            ),
        )
    }

    @Test
    fun eachTypeKeepsItsSource() {
        val wrong = LoyaltyEntryCheck.Invalid(LoyaltyEntryIssue.WRONG_SOURCE)
        val none = LoyaltyEntrySource.None
        assertEquals(wrong, LoyaltyLedgerRules.next(0L, draft(LoyaltyEntryType.EARN, 1L, none)))
        assertEquals(wrong, LoyaltyLedgerRules.next(5L, draft(LoyaltyEntryType.REDEEM, -1L, none)))
        assertEquals(wrong, LoyaltyLedgerRules.next(0L, draft(LoyaltyEntryType.ENTRY_VOID, 1L)))
        assertEquals(wrong, LoyaltyLedgerRules.next(0L, draft(LoyaltyEntryType.ADJUSTMENT, 1L)))
    }

    @Test
    fun aNoteUpToTwoHundredCharactersIsKept() {
        val longest = "a".repeat(LoyaltyLedgerRules.NOTE_MAX)
        assertEquals(
            LoyaltyEntryCheck.Valid(1L),
            LoyaltyLedgerRules.next(0L, draft(LoyaltyEntryType.EARN, 1L, note = longest)),
        )
        assertEquals(
            LoyaltyEntryCheck.Invalid(LoyaltyEntryIssue.NOTE_TOO_LONG),
            LoyaltyLedgerRules.next(0L, draft(LoyaltyEntryType.EARN, 1L, note = longest + "a")),
        )
    }

    @Test
    fun theBalanceStaysWithinTheLimitBothWays() {
        val above = LoyaltyEntryCheck.Invalid(LoyaltyEntryIssue.BALANCE_ABOVE_LIMIT)
        assertEquals(
            LoyaltyEntryCheck.Valid(MAX),
            LoyaltyLedgerRules.next(MAX - 1L, draft(LoyaltyEntryType.EARN, 1L)),
        )
        assertEquals(above, LoyaltyLedgerRules.next(MAX, draft(LoyaltyEntryType.EARN, 1L)))
        val void = LoyaltyEntrySource.Reverses(1L)
        assertEquals(
            LoyaltyEntryCheck.Valid(-MAX),
            LoyaltyLedgerRules.next(-MAX + 1L, draft(LoyaltyEntryType.ENTRY_VOID, -1L, void)),
        )
        assertEquals(
            above,
            LoyaltyLedgerRules.next(-MAX, draft(LoyaltyEntryType.ENTRY_VOID, -1L, void)),
        )
        // Un saldo que ni siquiera cabe en un entero de 64 bits también queda fuera.
        assertEquals(
            above,
            LoyaltyLedgerRules.next(Long.MAX_VALUE, draft(LoyaltyEntryType.EARN, 1L)),
        )
    }

    @Test
    fun aReversalHasTheOppositePointsAndPointsToTheRow() {
        val earn = entry(LoyaltyEntryType.EARN, 12L)

        assertEquals(
            LoyaltyEntryDraft(
                CUSTOMER,
                LoyaltyEntryType.ENTRY_VOID,
                -12L,
                LoyaltyEntrySource.Reverses(earn.id),
                "Error",
            ),
            LoyaltyLedgerRules.reversalOf(earn, "Error"),
        )
        assertNull(LoyaltyLedgerRules.reversalOf(entry(LoyaltyEntryType.ENTRY_VOID, -12L), null))
    }

    @Test
    fun aBlankNoteIsNotKept() {
        assertNull(LoyaltyLedgerRules.note(null))
        assertNull(LoyaltyLedgerRules.note("   "))
        assertEquals("Ajuste", LoyaltyLedgerRules.note("  Ajuste "))
    }

    @Test
    fun redeemingAHundredAtFiveDiscountsFiveHundred() {
        assertEquals(
            RedemptionCheck.Discount(100L, Money(500)),
            LoyaltyLedgerRules.redemption(100L, available = 150L, pointValue = Money(5)),
        )
        // Todo el saldo también se puede canjear.
        assertEquals(
            RedemptionCheck.Discount(150L, Money(750)),
            LoyaltyLedgerRules.redemption(150L, available = 150L, pointValue = Money(5)),
        )
    }

    @Test
    fun redeemingMoreThanTheBalanceSaysWhatIsAvailable() {
        assertEquals(
            RedemptionCheck.NotEnough(150L),
            LoyaltyLedgerRules.redemption(151L, available = 150L, pointValue = Money(5)),
        )
        // Con saldo negativo no se canjea nada.
        assertEquals(
            RedemptionCheck.NotEnough(-12L),
            LoyaltyLedgerRules.redemption(1L, available = -12L, pointValue = Money(5)),
        )
    }

    @Test
    fun zeroOrNegativePointsRedeemNothing() {
        assertEquals(
            RedemptionCheck.NothingToRedeem,
            LoyaltyLedgerRules.redemption(0L, available = 150L, pointValue = Money(5)),
        )
        assertEquals(
            RedemptionCheck.NothingToRedeem,
            LoyaltyLedgerRules.redemption(-1L, available = 150L, pointValue = Money(5)),
        )
    }

    @Test
    fun aDiscountThatDoesNotFitADocumentIsAboveTheSale() {
        // Pasa del tope de un documento.
        assertEquals(
            RedemptionCheck.AboveSale,
            LoyaltyLedgerRules.redemption(MAX, available = MAX, pointValue = Money(1_000_000)),
        )
        // Ni siquiera cabe en un entero de 64 bits.
        assertEquals(
            RedemptionCheck.AboveSale,
            LoyaltyLedgerRules.redemption(
                MAX,
                available = MAX,
                pointValue = Money(Long.MAX_VALUE / 2),
            ),
        )
    }

    @Test
    fun whatIsEarnedNeverPassesTheLimit() {
        assertEquals(12L, LoyaltyLedgerRules.earnable(12L, balance = 50L))
        assertEquals(5L, LoyaltyLedgerRules.earnable(12L, balance = MAX - 5L))
        assertEquals(0L, LoyaltyLedgerRules.earnable(12L, balance = MAX))
        assertEquals(0L, LoyaltyLedgerRules.earnable(0L, balance = 50L))
        // Con saldo negativo hay más lugar, nunca menos.
        assertEquals(12L, LoyaltyLedgerRules.earnable(12L, balance = -MAX))
    }

    @Test
    fun earningOnlyAddsAndRedeemingOnlySubtracts() {
        assertTrue(LoyaltyEntryType.EARN.admits(positive = true))
        assertFalse(LoyaltyEntryType.EARN.admits(positive = false))
        assertTrue(LoyaltyEntryType.REDEEM.admits(positive = false))
        assertFalse(LoyaltyEntryType.REDEEM.admits(positive = true))
        for (type in listOf(LoyaltyEntryType.ADJUSTMENT, LoyaltyEntryType.ENTRY_VOID)) {
            assertTrue(type.admits(positive = true))
            assertTrue(type.admits(positive = false))
        }
    }

    @Test
    fun theProgramIsActiveOnlyWhenOnAndWithBothAmounts() {
        assertTrue(LoyaltySettings(true, Money(1_000), Money(5)).active)
        assertFalse(LoyaltySettings(false, Money(1_000), Money(5)).active)
        assertFalse(LoyaltySettings(true, null, Money(5)).active)
        assertFalse(LoyaltySettings(true, Money(1_000), null).active)
        assertFalse(LoyaltySettings.OFF.active)
    }

    private fun entry(type: LoyaltyEntryType, points: Long) = LoyaltyLedgerEntry(
        id = 7L,
        customerId = CUSTOMER,
        type = type,
        points = points,
        balanceAfter = points,
        source = LoyaltyEntrySource.Sale(SALE),
        note = null,
        occurredAt = Instant.fromEpochMilliseconds(0),
        businessDate = requireNotNull(BusinessDate.parse("2026-11-01")),
    )

    private companion object {
        const val CUSTOMER = 3L
        const val SALE = 9L
        const val MAX = 1_000_000_000_000L
    }
}
