package io.github.yesidrobayo.shopledger.domain.rule.safety

import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyVersions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sin aceptar la versión vigente de la política y los términos, las copias en la nube quedan en
 * pausa; una versión mayor vuelve a pausarlas hasta aceptarla.
 */
class PolicyConsentRuleTest {
    private val current = PolicyVersions(privacyPolicy = 3, termsOfUse = 2)

    private fun status(accepted: PolicyVersions?) = PolicyConsentRule.status(accepted, current)

    @Test
    fun `nothing accepted on this phone is never accepted`() {
        assertEquals(PolicyConsentStatus.NEVER_ACCEPTED, status(null))
    }

    @Test
    fun `the current versions are accepted`() {
        assertEquals(PolicyConsentStatus.ACCEPTED, status(current))
    }

    @Test
    fun `a newer policy pauses again even with the same terms`() {
        assertEquals(PolicyConsentStatus.OUTDATED, status(PolicyVersions(2, 2)))
    }

    @Test
    fun `newer terms pause again even with the same policy`() {
        assertEquals(PolicyConsentStatus.OUTDATED, status(PolicyVersions(3, 1)))
    }

    @Test
    fun `versions accepted on a newer build still count after going back to an older one`() {
        assertEquals(PolicyConsentStatus.ACCEPTED, status(PolicyVersions(4, 2)))
        assertEquals(PolicyConsentStatus.ACCEPTED, status(PolicyVersions(3, 5)))
        // Una más nueva no tapa la otra, que sigue atrasada.
        assertEquals(PolicyConsentStatus.OUTDATED, status(PolicyVersions(9, 1)))
    }
}
