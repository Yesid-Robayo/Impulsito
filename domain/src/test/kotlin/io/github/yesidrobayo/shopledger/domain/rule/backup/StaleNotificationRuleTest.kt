package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La notificación de un día sin copia: solo con ese estado, como mucho una cada 24 horas, y con el
 * reloj atrasado sale igual.
 */
class StaleNotificationRuleTest {
    private val now = Instant.parse("2026-11-07T15:00:00Z")

    @Test
    fun theFirstOneGoesOutWithTheStatus() {
        assertTrue(StaleNotificationRule.isDue(BackupStatus.STALE, lastNoticeAt = null, now))
    }

    @Test
    fun theNextOneWaitsTwentyFourHours() {
        val last = now - 24.hours

        assertFalse(StaleNotificationRule.isDue(BackupStatus.STALE, last + 1.milliseconds, now))
        assertTrue(StaleNotificationRule.isDue(BackupStatus.STALE, last, now))
    }

    @Test
    fun aClockSetBackNeverHidesIt() {
        assertTrue(StaleNotificationRule.isDue(BackupStatus.STALE, now + 1.hours, now))
    }

    @Test
    fun noOtherStatusNotifiesNotEvenWithoutADestination() {
        for (status in BackupStatus.entries - BackupStatus.STALE) {
            assertFalse(StaleNotificationRule.isDue(status, lastNoticeAt = null, now))
        }
    }
}
