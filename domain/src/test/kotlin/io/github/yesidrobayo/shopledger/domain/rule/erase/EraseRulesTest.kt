package io.github.yesidrobayo.shopledger.domain.rule.erase

import io.github.yesidrobayo.shopledger.domain.model.erase.EraseDestination
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseFacts
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseStop
import io.github.yesidrobayo.shopledger.domain.model.erase.RemoteEraseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Por dónde empieza `Borrar mis datos`: primero las copias, nunca lo local antes, y sin red o sin
 * acceso no empieza. Cada rama, con su caso.
 */
class EraseRulesTest {
    private fun facts(
        destination: EraseDestination,
        hasNetwork: Boolean = true,
        restoreInProgress: Boolean = false,
    ) = EraseFacts(destination, hasNetwork, restoreInProgress)

    private val all = EraseScope.DEVICE_AND_COPIES

    @Test
    fun `a restore in progress stops both scopes`() {
        val restoring = facts(EraseDestination.WEBDAV, restoreInProgress = true)
        val refused = EraseStart.Refused(EraseStop.RESTORE_IN_PROGRESS)

        assertEquals(refused, EraseRules.start(EraseScope.DEVICE_ONLY, restoring))
        assertEquals(refused, EraseRules.start(all, restoring))
        assertEquals(refused, EraseRules.deviceOnly(restoring))
    }

    @Test
    fun `this phone only goes straight to the local phase, even offline`() {
        val offline = facts(EraseDestination.WEBDAV, hasNetwork = false)

        assertEquals(
            EraseStart.Begin(ErasePhase.LOCAL),
            EraseRules.start(EraseScope.DEVICE_ONLY, offline),
        )
        assertEquals(EraseStart.Begin(ErasePhase.LOCAL), EraseRules.deviceOnly(offline))
    }

    @Test
    fun `with copies it starts in the cloud, and with slots it needs no network`() {
        assertEquals(
            EraseStart.Begin(ErasePhase.REMOTE),
            EraseRules.start(all, facts(EraseDestination.WEBDAV)),
        )
        assertEquals(
            EraseStart.Begin(ErasePhase.REMOTE),
            EraseRules.start(all, facts(EraseDestination.SLOTS, hasNetwork = false)),
        )
    }

    @Test
    fun `webdav without network never touches this phone`() {
        assertEquals(
            EraseStart.Refused(EraseStop.OFFLINE),
            EraseRules.start(all, facts(EraseDestination.WEBDAV, hasNetwork = false)),
        )
    }

    @Test
    fun `a lost access asks to reconnect, and without a destination there is nothing to reach`() {
        assertEquals(
            EraseStart.Refused(EraseStop.ACCESS_LOST),
            EraseRules.start(all, facts(EraseDestination.ACCESS_LOST)),
        )
        assertEquals(
            EraseStart.Begin(ErasePhase.LOCAL),
            EraseRules.start(all, facts(EraseDestination.NONE, hasNetwork = false)),
        )
    }

    @Test
    fun `only a cloud with nothing left lets the local phase start`() {
        assertNull(EraseRules.afterRemote(RemoteEraseResult.DELETED))
        assertEquals(EraseStop.PARTIAL, EraseRules.afterRemote(RemoteEraseResult.PARTIAL))
        assertEquals(EraseStop.UNREACHABLE, EraseRules.afterRemote(RemoteEraseResult.UNREACHABLE))
        assertEquals(EraseStop.REFUSED, EraseRules.afterRemote(RemoteEraseResult.REFUSED))
        assertEquals(EraseStop.ACCESS_LOST, EraseRules.afterRemote(RemoteEraseResult.ACCESS_LOST))
    }
}
