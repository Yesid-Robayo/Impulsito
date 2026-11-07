package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.VerifyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La tabla de la verificación con WebDAV: cuándo se lee la copia entera y cuándo una muestra. */
class VerifyPolicyTest {
    private val day = FULL_VERIFY_HOURS.inWholeMilliseconds
    private val now = 10 * day

    private fun mode(
        metered: Boolean = false,
        backupNow: Boolean = false,
        resumed: Boolean = false,
        meteredAllowed: Boolean = false,
        last: Long? = now - 1,
    ) = verifyModeFor(VerifyRequest(metered, backupNow, resumed, meteredAllowed, last, now))

    @Test
    fun `an unmetered network reads it whole when the period expired or never started`() {
        assertEquals(VerifyMode.FULL, mode(last = null))
        assertEquals(VerifyMode.FULL, mode(last = now - day))
        assertEquals(VerifyMode.SAMPLE, mode(last = now - day + 1))
    }

    @Test
    fun `backing up now on an unmetered network reads it whole even within the period`() {
        assertEquals(VerifyMode.FULL, mode(backupNow = true))
    }

    @Test
    fun `a metered network only samples, even when backing up now or with the period expired`() {
        assertEquals(VerifyMode.SAMPLE, mode(metered = true))
        assertEquals(VerifyMode.SAMPLE, mode(metered = true, backupNow = true, last = null))
    }

    @Test
    fun `a resumed copy is always read whole, or waits when mobile data is not allowed`() {
        assertEquals(VerifyMode.FULL, mode(resumed = true))
        assertEquals(VerifyMode.FULL, mode(resumed = true, metered = true, meteredAllowed = true))
        assertEquals(VerifyMode.FULL, mode(resumed = true, metered = true, backupNow = true))
        assertNull(mode(resumed = true, metered = true))
    }

    @Test
    fun `a clock that went back counts as an expired period`() {
        assertTrue(isFullVerifyDue(now + 1, now))
        assertFalse(isFullVerifyDue(now, now))
        assertEquals(VerifyMode.FULL, mode(last = now + day))
    }
}
