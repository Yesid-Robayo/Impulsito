package io.github.yesidrobayo.shopledger.domain.usecase.safety

import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.port.PolicyAcceptance
import io.github.yesidrobayo.shopledger.domain.port.PolicyConsentPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Antes de conectar se pregunta sin la versión vigente; al abrir, solo si cambió una versión ya
 * aceptada; y aceptar devuelve si quedó guardado.
 */
class PolicyConsentUseCaseTest {
    private var status = PolicyConsentStatus.NEVER_ACCEPTED
    private var saves = PolicyAcceptance.SAVED

    private val port =
        object : PolicyConsentPort {
            override suspend fun status(): PolicyConsentStatus = status

            override suspend fun acceptCurrent(): PolicyAcceptance {
                if (saves == PolicyAcceptance.SAVED) status = PolicyConsentStatus.ACCEPTED
                return saves
            }
        }

    private val consent = PolicyConsentUseCase(port)

    @Test
    fun `never accepted asks before connecting, not when opening`() = runTest {
        assertTrue(consent.needsAcceptance())
        assertFalse(consent.changedSinceAccepted())
        assertEquals(PolicyConsentStatus.NEVER_ACCEPTED, consent.status())
    }

    @Test
    fun `an older accepted version asks both when opening and before connecting`() = runTest {
        status = PolicyConsentStatus.OUTDATED

        assertTrue(consent.needsAcceptance())
        assertTrue(consent.changedSinceAccepted())
    }

    @Test
    fun `accepting saves the current versions and stops asking`() = runTest {
        assertTrue(consent.accept())

        assertFalse(consent.needsAcceptance())
        assertFalse(consent.changedSinceAccepted())
    }

    @Test
    fun `a failed save changes nothing`() = runTest {
        saves = PolicyAcceptance.NOT_SAVED

        assertFalse(consent.accept())
        assertTrue(consent.needsAcceptance())
    }
}
