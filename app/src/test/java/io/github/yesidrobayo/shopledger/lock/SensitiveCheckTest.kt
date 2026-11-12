package io.github.yesidrobayo.shopledger.lock

import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Una operación sensible con el bloqueo encendido pide la verificación más fuerte aunque la app esté
 * abierta; sin el bloqueo sigue con la confirmación normal, y un teléfono sin bloqueo de pantalla
 * nunca queda fuera.
 */
class SensitiveCheckTest {
    private val lock = AppLock { 0L }

    private class Answering(private val outcome: AuthOutcome) : DeviceAuthenticator {
        val purposes = mutableListOf<AuthPurpose>()

        override fun hasDeviceCredential(): Boolean = true

        override suspend fun authenticate(
            purpose: AuthPurpose,
            title: String,
            subtitle: String?,
        ): AuthOutcome {
            purposes += purpose
            return outcome
        }
    }

    @Test
    fun `without the lock it goes on without asking`() = runTest {
        lock.apply(enabled = false, AppLockTimeout.ONE_MINUTE)
        val authenticator = Answering(AuthOutcome.Canceled)

        assertTrue(SensitiveCheck(lock, authenticator).confirm(TITLE))
        assertEquals(emptyList<AuthPurpose>(), authenticator.purposes)
    }

    @Test
    fun `before the setting is read it does not ask`() = runTest {
        assertTrue(SensitiveCheck(lock, Answering(AuthOutcome.Canceled)).confirm(TITLE))
    }

    @Test
    fun `with the lock on it asks again for the sensitive check`() = runTest {
        lock.apply(enabled = true, AppLockTimeout.ONE_MINUTE)
        lock.unlocked()
        val authenticator = Answering(AuthOutcome.Success)

        assertTrue(SensitiveCheck(lock, authenticator).confirm(TITLE))
        assertEquals(listOf(AuthPurpose.SENSITIVE), authenticator.purposes)
    }

    @Test
    fun `a cancelled or failed check stops the operation`() = runTest {
        lock.apply(enabled = true, AppLockTimeout.ONE_MINUTE)

        assertFalse(SensitiveCheck(lock, Answering(AuthOutcome.Canceled)).confirm(TITLE))
        assertFalse(SensitiveCheck(lock, Answering(AuthOutcome.Failed("x"))).confirm(TITLE))
    }

    @Test
    fun `a phone without screen lock is never kept out`() = runTest {
        lock.apply(enabled = true, AppLockTimeout.ONE_MINUTE)
        lock.unlocked()

        assertTrue(SensitiveCheck(lock, Answering(AuthOutcome.NoDeviceCredential)).confirm(TITLE))
    }

    private companion object {
        const val TITLE = "Restaurar"
    }
}
