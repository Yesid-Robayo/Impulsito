package io.github.yesidrobayo.shopledger.domain.usecase.erase

import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseDestination
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseFacts
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseOutcome
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseStop
import io.github.yesidrobayo.shopledger.domain.model.erase.RemoteEraseResult
import io.github.yesidrobayo.shopledger.domain.port.AuditRecorder
import io.github.yesidrobayo.shopledger.domain.port.EraseMyDataPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Borrar mis datos` como máquina de estados guardada: la fase se guarda antes de cada paso, lo
 * local nunca va antes que la nube, una muerte a mitad se retoma desde lo guardado, y borrar solo
 * este teléfono pide su propia confirmación.
 */
class EraseMyDataUseCaseTest {
    /** La muerte del proceso en un paso: nada la atrapa, como en un teléfono. */
    private class ProcessDeath : Error()

    /** El teléfono y la nube, en memoria. Lo guardado sobrevive a la muerte del proceso. */
    private class Phone : EraseMyDataPort {
        var facts =
            EraseFacts(EraseDestination.WEBDAV, hasNetwork = true, restoreInProgress = false)
        var saved: ErasePhase? = null
        var cloudCopies = 3
        var localData = true
        var remoteAnswers = ArrayDeque<RemoteEraseResult>()
        var localWorks = true
        var dieAt: String? = null
        val steps = mutableListOf<String>()

        private fun step(name: String) {
            steps += name
            if (dieAt == name) {
                dieAt = null
                throw ProcessDeath()
            }
        }

        override suspend fun facts(): EraseFacts = facts

        override suspend fun pending(): ErasePhase? = saved

        override suspend fun save(phase: ErasePhase) {
            saved = phase
            step("save:$phase")
        }

        override suspend fun deleteRemote(): RemoteEraseResult {
            // Lo local nunca se borra antes que la nube.
            assertTrue(localData)
            val answer = remoteAnswers.removeFirstOrNull() ?: RemoteEraseResult.DELETED
            if (answer == RemoteEraseResult.DELETED) cloudCopies = 0
            if (answer == RemoteEraseResult.PARTIAL) cloudCopies = 1
            step("remote:$answer")
            return answer
        }

        override suspend fun deleteLocal(): Boolean {
            step("local")
            if (!localWorks) return false
            localData = false
            saved = null
            return true
        }
    }

    private val phone = Phone()
    private val audits = mutableListOf<Pair<AuditEventType, AuditResult>>()
    private val audit = AuditRecorder { type, result -> audits += type to result }

    private fun erase() = EraseMyDataUseCase(phone, audit)

    @Test
    fun `with copies the cloud goes first and only then this phone`() = runTest {
        val outcome = erase()(EraseScope.DEVICE_AND_COPIES)

        assertEquals(EraseOutcome.Finished, outcome)
        assertEquals(listOf("save:REMOTE", "remote:DELETED", "save:LOCAL", "local"), phone.steps)
        assertEquals(0, phone.cloudCopies)
        assertFalse(phone.localData)
        assertNull(phone.saved)
        assertEquals(listOf(AuditEventType.DELETE_MY_DATA to AuditResult.SUCCESS), audits)
    }

    @Test
    fun `this phone only never asks the cloud`() = runTest {
        val outcome = erase()(EraseScope.DEVICE_ONLY)

        assertEquals(EraseOutcome.Finished, outcome)
        assertEquals(listOf("save:LOCAL", "local"), phone.steps)
        assertEquals(3, phone.cloudCopies)
    }

    @Test
    fun `without network nothing is saved or touched`() = runTest {
        phone.facts = phone.facts.copy(hasNetwork = false)

        val outcome = erase()(EraseScope.DEVICE_AND_COPIES)

        assertEquals(EraseOutcome.Stopped(EraseStop.OFFLINE), outcome)
        assertTrue(phone.steps.isEmpty())
        assertNull(phone.saved)
        assertTrue(phone.localData)
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `a cloud that refuses stops before this phone, and retrying waits for it`() = runTest {
        phone.remoteAnswers += RemoteEraseResult.REFUSED
        phone.remoteAnswers += RemoteEraseResult.REFUSED

        val first = erase()(EraseScope.DEVICE_AND_COPIES)
        val retried = erase().resume()

        assertEquals(EraseOutcome.Stopped(EraseStop.REFUSED), first)
        assertEquals(EraseOutcome.Stopped(EraseStop.REFUSED), retried)
        assertEquals(ErasePhase.REMOTE, phone.saved)
        assertTrue(phone.localData)
        assertEquals(
            listOf(
                AuditEventType.DELETE_MY_DATA to AuditResult.FAILURE,
                AuditEventType.DELETE_MY_DATA to AuditResult.FAILURE,
            ),
            audits,
        )
    }

    @Test
    fun `some copies left in the cloud are retried until none is left`() = runTest {
        phone.remoteAnswers += RemoteEraseResult.PARTIAL

        assertEquals(
            EraseOutcome.Stopped(EraseStop.PARTIAL),
            erase()(EraseScope.DEVICE_AND_COPIES),
        )
        assertEquals(1, phone.cloudCopies)
        assertTrue(phone.localData)

        assertEquals(EraseOutcome.Finished, erase().resume())
        assertEquals(0, phone.cloudCopies)
        assertFalse(phone.localData)
    }

    @Test
    fun `after a stop, this phone only needs its own confirmation and leaves the copies`() =
        runTest {
            phone.remoteAnswers += RemoteEraseResult.UNREACHABLE
            erase()(EraseScope.DEVICE_AND_COPIES)
            assertEquals(ErasePhase.REMOTE, phone.saved)

            val outcome = erase().deviceOnly()

            assertEquals(EraseOutcome.Finished, outcome)
            assertEquals(3, phone.cloudCopies)
            assertFalse(phone.localData)
            assertEquals(
                listOf("save:REMOTE", "remote:UNREACHABLE", "save:LOCAL", "local"),
                phone.steps,
            )
        }

    @Test
    fun `this phone only is refused during a restore`() = runTest {
        phone.facts = phone.facts.copy(restoreInProgress = true)

        assertEquals(EraseOutcome.Stopped(EraseStop.RESTORE_IN_PROGRESS), erase().deviceOnly())
        assertTrue(phone.steps.isEmpty())
    }

    @Test
    fun `a death after each step is resumed from what was saved`() = runTest {
        for (death in listOf("save:REMOTE", "remote:DELETED", "save:LOCAL", "local")) {
            val run = Phone().also { it.dieAt = death }
            val first =
                try {
                    EraseMyDataUseCase(run, audit)(EraseScope.DEVICE_AND_COPIES)
                } catch (_: ProcessDeath) {
                    null
                }
            assertNull(death, first)
            // Hasta que se guarda lo local, este teléfono sigue entero.
            if (death != "local") assertTrue(death, run.localData)

            val resumed = EraseMyDataUseCase(run, audit).resume()

            assertEquals(death, EraseOutcome.Finished, resumed)
            assertEquals(death, 0, run.cloudCopies)
            assertFalse(death, run.localData)
            assertNull(death, run.saved)
        }
    }

    @Test
    fun `a local deletion that fails stays saved and is resumed`() = runTest {
        phone.localWorks = false

        assertEquals(
            EraseOutcome.Stopped(EraseStop.LOCAL_FAILED),
            erase()(EraseScope.DEVICE_ONLY),
        )
        assertEquals(ErasePhase.LOCAL, erase().pending())

        phone.localWorks = true
        assertEquals(EraseOutcome.Finished, erase().resume())
        assertNull(erase().pending())
        assertNull(erase().resume())
    }

    @Test
    fun `slots need no network, and a lost access asks to reconnect`() = runTest {
        phone.facts =
            EraseFacts(EraseDestination.SLOTS, hasNetwork = false, restoreInProgress = false)
        phone.remoteAnswers += RemoteEraseResult.ACCESS_LOST

        assertEquals(
            EraseOutcome.Stopped(EraseStop.ACCESS_LOST),
            erase()(EraseScope.DEVICE_AND_COPIES),
        )
        assertEquals(listOf("save:REMOTE", "remote:ACCESS_LOST"), phone.steps)
        assertTrue(phone.localData)
    }
}
