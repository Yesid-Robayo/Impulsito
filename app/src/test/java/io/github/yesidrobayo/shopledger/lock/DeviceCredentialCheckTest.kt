package io.github.yesidrobayo.shopledger.lock

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restaurar desde el rescate: con bloqueo de pantalla en el teléfono pide siempre la verificación
 * sensible; sin él sigue sin pedir nada; cancelada o fallida, no restaura.
 */
class DeviceCredentialCheckTest {
    private class Phone(private val secure: Boolean, private val outcome: AuthOutcome) :
        DeviceAuthenticator {
        val purposes = mutableListOf<AuthPurpose>()

        override fun hasDeviceCredential(): Boolean = secure

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
    fun `with a screen lock it always asks the sensitive check`() = runTest {
        val phone = Phone(secure = true, outcome = AuthOutcome.Success)

        assertTrue(DeviceCredentialCheck(phone).allows(TITLE))
        assertEquals(listOf(AuthPurpose.SENSITIVE), phone.purposes)
    }

    @Test
    fun `without a screen lock it restores without asking`() = runTest {
        val phone = Phone(secure = false, outcome = AuthOutcome.Canceled)

        assertTrue(DeviceCredentialCheck(phone).allows(TITLE))
        assertEquals(emptyList<AuthPurpose>(), phone.purposes)
    }

    @Test
    fun `a cancelled or failed check does not restore`() = runTest {
        assertFalse(DeviceCredentialCheck(Phone(true, AuthOutcome.Canceled)).allows(TITLE))
        assertFalse(DeviceCredentialCheck(Phone(true, AuthOutcome.Failed("x"))).allows(TITLE))
    }

    @Test
    fun `a phone that loses its lock while asking still restores`() = runTest {
        assertTrue(DeviceCredentialCheck(Phone(true, AuthOutcome.NoDeviceCredential)).allows(TITLE))
    }

    private companion object {
        const val TITLE = "Restaurar"
    }
}
