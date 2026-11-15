package io.github.yesidrobayo.shopledger.domain.rule.surface

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.surface.AmountDecision
import io.github.yesidrobayo.shopledger.domain.model.surface.DeviceLockState
import io.github.yesidrobayo.shopledger.domain.model.surface.WidgetContent
import io.github.yesidrobayo.shopledger.domain.model.surface.WidgetFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El widget fuera de la app: sin montos por defecto, nunca con el teléfono bloqueado ni en modo
 * discreto, y un estado neutro cuando la base no está lista. Montos inventados.
 */
class SurfaceRulesTest {
    private val open =
        DeviceLockState(screenOn = true, keyguardShowing = false, deviceLocked = false)

    private val facts =
        WidgetFacts(
            hasProducts = true,
            salesCount = 12,
            netSales = Money(1_284_500),
            lowStockCount = 2,
            backupFailed = false,
        )

    @Test
    fun `an amount stays hidden when the owner did not ask for it`() {
        assertEquals(
            AmountDecision.HIDDEN_BY_DEFAULT,
            AmountVisibilityPolicy.decide(false, open, discreetMode = false),
        )
    }

    @Test
    fun `an amount shows when asked for and the phone is open`() {
        val decision = AmountVisibilityPolicy.decide(true, open, discreetMode = false)

        assertEquals(AmountDecision.VISIBLE, decision)
        assertTrue(decision.isVisible)
    }

    @Test
    fun `an amount hides while the keyguard shows even without a secure method`() {
        val decision =
            AmountVisibilityPolicy.decide(true, open.copy(keyguardShowing = true), false)

        assertEquals(AmountDecision.HIDDEN_LOCKED, decision)
    }

    @Test
    fun `an amount hides while the phone asks for its secure method`() {
        val decision = AmountVisibilityPolicy.decide(true, open.copy(deviceLocked = true), false)

        assertEquals(AmountDecision.HIDDEN_LOCKED, decision)
    }

    @Test
    fun `an amount hides when the screen turns off before the keyguard appears`() {
        val decision = AmountVisibilityPolicy.decide(true, open.copy(screenOn = false), false)

        assertEquals(AmountDecision.HIDDEN_LOCKED, decision)
    }

    @Test
    fun `an amount hides in discreet mode`() {
        val decision = AmountVisibilityPolicy.decide(true, open, discreetMode = true)

        assertEquals(AmountDecision.HIDDEN_DISCREET, decision)
        assertFalse(decision.isVisible)
    }

    @Test
    fun `the starting reading never shows an amount`() {
        val decision =
            AmountVisibilityPolicy.decide(true, DeviceLockState.fullyLocked, discreetMode = false)

        assertEquals(AmountDecision.HIDDEN_LOCKED, decision)
        assertFalse(DeviceLockState.fullyLocked.isOpenToOwner)
    }

    @Test
    fun `the strictest reading keeps every sign of a lock from either side`() {
        val keyguard = open.copy(keyguardShowing = true)
        val off = open.copy(screenOn = false)
        val locked = open.copy(deviceLocked = true)

        assertEquals(
            DeviceLockState(screenOn = false, keyguardShowing = true, deviceLocked = false),
            keyguard.strictest(off),
        )
        assertEquals(
            DeviceLockState(screenOn = false, keyguardShowing = false, deviceLocked = true),
            off.strictest(locked),
        )
        assertEquals(
            DeviceLockState(screenOn = true, keyguardShowing = true, deviceLocked = true),
            open.strictest(keyguard).strictest(locked),
        )
        assertEquals(
            DeviceLockState(screenOn = true, keyguardShowing = false, deviceLocked = true),
            locked.strictest(open),
        )
        assertEquals(open, open.strictest(open))
        assertTrue(open.strictest(open).isOpenToOwner)
    }

    @Test
    fun `without a ready database the widget only offers to sell`() {
        assertEquals(
            WidgetContent.Neutral,
            QuickSaleWidgetRules.contentOf(null, true, open, false),
        )
    }

    @Test
    fun `without a known day the widget only offers to sell`() {
        val unknownDay = facts.copy(salesCount = null, netSales = null)

        assertEquals(
            WidgetContent.Neutral,
            QuickSaleWidgetRules.contentOf(unknownDay, true, open, false),
        )
    }

    @Test
    fun `without products the widget invites to create the first one`() {
        val empty = facts.copy(hasProducts = false, salesCount = 0, netSales = Money.ZERO)

        assertEquals(
            WidgetContent.NoProducts,
            QuickSaleWidgetRules.contentOf(empty, true, open, false),
        )
    }

    @Test
    fun `a new widget shows counts without amounts`() {
        assertEquals(
            WidgetContent.Summary(
                salesCount = 12,
                lowStockCount = 2,
                amountsChosen = false,
                amount = null,
                backupFailed = false,
            ),
            QuickSaleWidgetRules.contentOf(facts, false, open, false),
        )
    }

    @Test
    fun `asked for amounts the widget shows today's sales only while the phone is open`() {
        val shown = QuickSaleWidgetRules.contentOf(facts, true, open, false)
        val locked =
            QuickSaleWidgetRules.contentOf(facts, true, DeviceLockState.fullyLocked, false)
        val discreet = QuickSaleWidgetRules.contentOf(facts, true, open, true)

        assertEquals(Money(1_284_500), (shown as WidgetContent.Summary).amount)
        assertTrue(shown.amountsChosen)
        assertEquals(null, (locked as WidgetContent.Summary).amount)
        assertTrue(locked.amountsChosen)
        assertEquals(null, (discreet as WidgetContent.Summary).amount)
    }

    @Test
    fun `a failed backup is said in the widget`() {
        val failed = facts.copy(backupFailed = true)

        val content = QuickSaleWidgetRules.contentOf(failed, false, open, false)

        assertTrue((content as WidgetContent.Summary).backupFailed)
    }

    @Test
    fun `only a failure, a lost permission or a conflict counts as a failed backup`() {
        val failing =
            setOf(BackupStatus.FAILED, BackupStatus.PERMISSION_LOST, BackupStatus.CONFLICT)

        for (status in BackupStatus.entries) {
            assertEquals(status.name, status in failing, QuickSaleWidgetRules.backupFailed(status))
        }
    }
}
