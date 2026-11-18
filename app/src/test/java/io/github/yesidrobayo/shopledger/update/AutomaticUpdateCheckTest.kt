package io.github.yesidrobayo.shopledger.update

import io.github.yesidrobayo.shopledger.domain.model.update.KnownUpdate
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La búsqueda automática, una vez al día: avisa una sola vez por versión, no se conecta apagada,
 * un fallo termina en silencio y ninguna excepción sale con su mensaje. Versiones inventadas.
 */
class AutomaticUpdateCheckTest {
    private val hash = "ab".repeat(32)
    private val published = Instant.parse("2026-11-18T00:00:00Z")
    private val log = RecordingLog()

    private var enabled = true
    private var outcome: UpdateCheckOutcome = UpdateCheckOutcome.Latest(hash)
    private var lastNotified: Long? = null
    private var notificationAllowed = true
    private var checks = 0
    private val shown = mutableListOf<KnownUpdate>()

    private fun newVersion(code: Long) = UpdateCheckOutcome.NewVersion(
        versionName = "1.0.$code",
        publishedAt = published,
        apkSha256 = hash,
        important = false,
        versionCode = code,
    )

    private fun check(failure: Exception? = null) = AutomaticUpdateCheck(
        enabled = { enabled },
        check = {
            checks++
            if (failure != null) throw failure
            outcome
        },
        lastNotified = { lastNotified },
        notify = { update ->
            if (notificationAllowed) shown += update
            notificationAllowed
        },
        markNotified = { code ->
            lastNotified = code
            true
        },
        log = log,
    )

    @Test
    fun `a new version is notified and marked`() = runTest {
        outcome = newVersion(8)

        assertEquals(AutomaticCheckRun.NOTIFIED, check().run())

        assertEquals(listOf(KnownUpdate(8, "1.0.8")), shown)
        assertEquals(8L, lastNotified)
    }

    @Test
    fun `the same version is notified only once`() = runTest {
        outcome = newVersion(8)
        val daily = check()

        daily.run()
        assertEquals(AutomaticCheckRun.ALREADY_NOTIFIED, daily.run())

        assertEquals(1, shown.size)
        assertEquals(2, checks)
    }

    @Test
    fun `a newer version after a notified one is notified too`() = runTest {
        lastNotified = 8
        outcome = newVersion(9)

        assertEquals(AutomaticCheckRun.NOTIFIED, check().run())

        assertEquals(listOf(KnownUpdate(9, "1.0.9")), shown)
        assertEquals(9L, lastNotified)
    }

    @Test
    fun `a blocked notification is not marked and is tried the next day`() = runTest {
        outcome = newVersion(8)
        notificationAllowed = false

        assertEquals(AutomaticCheckRun.BLOCKED, check().run())
        assertEquals(null, lastNotified)

        notificationAllowed = true
        assertEquals(AutomaticCheckRun.NOTIFIED, check().run())
        assertEquals(8L, lastNotified)
    }

    @Test
    fun `turned off it never connects`() = runTest {
        enabled = false
        outcome = newVersion(8)

        assertEquals(AutomaticCheckRun.OFF, check().run())

        assertEquals(0, checks)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `the latest version notifies nothing`() = runTest {
        assertEquals(AutomaticCheckRun.LATEST, check().run())
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `without network, with a bad signature or a strange answer it ends quietly`() = runTest {
        val cases =
            mapOf(
                UpdateCheckOutcome.Offline to AutomaticCheckRun.OFFLINE,
                UpdateCheckOutcome.BadSignature to AutomaticCheckRun.BAD_SIGNATURE,
                UpdateCheckOutcome.Unreadable to AutomaticCheckRun.UNREADABLE,
            )
        cases.forEach { (failure, expected) ->
            outcome = failure
            assertEquals(expected, check().run())
        }

        assertTrue(shown.isEmpty())
        assertEquals(null, lastNotified)
    }

    @Test
    fun `an unexpected failure is contained and logs only its class`() = runTest {
        val secret = "https://example.com/version.json?token=secreto"

        assertEquals(AutomaticCheckRun.FAILED, check(IOException(secret)).run())

        assertTrue(shown.isEmpty())
        assertTrue(log.texts.any { it.contains("IOException") })
        assertFalse(log.texts.any { it.contains("example.com") || it.contains("secreto") })
    }

    @Test
    fun `each run leaves only its state in the log`() = runTest {
        outcome = newVersion(8)

        check().run()

        assertTrue(log.texts.single().endsWith("estado=NOTIFIED"))
        assertFalse(log.texts.single().contains("1.0.8"))
    }

    private class RecordingLog : EventLog {
        val lines = mutableListOf<LogLine>()
        val texts: List<String> get() = lines.map { it.text }

        override fun write(level: LogLevel, line: LogLine) {
            lines += line
        }
    }
}
