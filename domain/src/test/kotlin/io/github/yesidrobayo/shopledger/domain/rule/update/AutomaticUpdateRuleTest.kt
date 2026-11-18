package io.github.yesidrobayo.shopledger.domain.rule.update

import io.github.yesidrobayo.shopledger.domain.model.update.KnownUpdate
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La búsqueda diaria solo informa: una versión nueva se avisa una sola vez, `Inicio` avisa mientras
 * no esté instalada y un fallo no cambia lo que se sabía. Versiones inventadas.
 */
class AutomaticUpdateRuleTest {
    private val published = Instant.parse("2026-11-18T00:00:00Z")
    private val hash = "ab".repeat(32)
    private val previous = KnownUpdate(7, "1.0.7")

    private fun newVersion(code: Long) = UpdateCheckOutcome.NewVersion(
        versionName = "1.0.$code",
        publishedAt = published,
        apkSha256 = hash,
        important = false,
        versionCode = code,
    )

    @Test
    fun `a new version becomes the known one`() {
        assertEquals(KnownUpdate(8, "1.0.8"), AutomaticUpdateRule.known(newVersion(8), previous))
    }

    @Test
    fun `knowing the installed one is the latest forgets the known one`() {
        assertNull(AutomaticUpdateRule.known(UpdateCheckOutcome.Latest(hash), previous))
    }

    @Test
    fun `a failure keeps what was known`() {
        listOf(
            UpdateCheckOutcome.BadSignature,
            UpdateCheckOutcome.Offline,
            UpdateCheckOutcome.Unreadable,
        ).forEach { failure ->
            assertEquals(previous, AutomaticUpdateRule.known(failure, previous))
            assertNull(AutomaticUpdateRule.known(failure, null))
        }
    }

    @Test
    fun `a new version never notified is notified`() {
        assertEquals(KnownUpdate(8, "1.0.8"), AutomaticUpdateRule.toNotify(newVersion(8), null))
    }

    @Test
    fun `a version higher than the last notified is notified`() {
        assertEquals(KnownUpdate(9, "1.0.9"), AutomaticUpdateRule.toNotify(newVersion(9), 8))
    }

    @Test
    fun `the version already notified is not notified again`() {
        assertNull(AutomaticUpdateRule.toNotify(newVersion(8), 8))
    }

    @Test
    fun `a version lower than the last notified is not notified`() {
        assertNull(AutomaticUpdateRule.toNotify(newVersion(7), 8))
    }

    @Test
    fun `nothing new or a failure notifies nothing`() {
        listOf(
            UpdateCheckOutcome.Latest(hash),
            UpdateCheckOutcome.BadSignature,
            UpdateCheckOutcome.Offline,
            UpdateCheckOutcome.Unreadable,
        ).forEach { outcome -> assertNull(AutomaticUpdateRule.toNotify(outcome, null)) }
    }

    @Test
    fun `home shows a known version newer than the installed one`() {
        assertEquals(previous, AutomaticUpdateRule.homeNotice(previous, installedVersionCode = 6))
    }

    @Test
    fun `home stops once that version is installed`() {
        assertNull(AutomaticUpdateRule.homeNotice(previous, installedVersionCode = 7))
        assertNull(AutomaticUpdateRule.homeNotice(previous, installedVersionCode = 8))
    }

    @Test
    fun `home shows nothing without a known version`() {
        assertNull(AutomaticUpdateRule.homeNotice(null, installedVersionCode = 6))
    }
}
