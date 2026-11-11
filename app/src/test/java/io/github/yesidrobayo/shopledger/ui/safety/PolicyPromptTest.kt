package io.github.yesidrobayo.shopledger.ui.safety

import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.port.PolicyAcceptance
import io.github.yesidrobayo.shopledger.domain.port.PolicyConsentPort
import io.github.yesidrobayo.shopledger.domain.usecase.safety.PolicyConsentUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La aceptación de la política sin pantalla: la compuerta pregunta antes de conectar y vuelve atrás
 * si no se acepta, el diálogo de cambios aparece al abrir solo con una versión vieja aceptada, un
 * guardado fallido no cambia nada, y decir que no nunca registra nada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PolicyPromptTest {
    private var status = PolicyConsentStatus.NEVER_ACCEPTED
    private var saves = PolicyAcceptance.SAVED
    private var accepts = 0

    private val port =
        object : PolicyConsentPort {
            override suspend fun status(): PolicyConsentStatus = status

            override suspend fun acceptCurrent(): PolicyAcceptance {
                accepts += 1
                if (saves == PolicyAcceptance.SAVED) status = PolicyConsentStatus.ACCEPTED
                return saves
            }
        }

    private fun TestScope.prompt() =
        PolicyPrompt(PolicyConsentUseCase(port), TestScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `without accepting, the gate asks and accepting lets the screen through`() = runTest {
        val prompt = prompt()

        prompt.enterGate()
        assertEquals(PolicyGate.WAITING, prompt.state.value.gate)
        assertEquals(PolicyQuestion.ACCEPT_TO_CONNECT, prompt.state.value.question)

        prompt.accept()
        assertEquals(PolicyGate.ALLOWED, prompt.state.value.gate)
        assertNull(prompt.state.value.question)
        assertEquals(PolicyConsentStatus.ACCEPTED, status)
    }

    @Test
    fun `saying no goes back without saving anything`() = runTest {
        val prompt = prompt()
        prompt.enterGate()

        prompt.later()

        assertEquals(PolicyGate.DECLINED, prompt.state.value.gate)
        assertNull(prompt.state.value.question)
        assertEquals(0, accepts)
        prompt.leaveGate()
        assertNull(prompt.state.value.gate)
    }

    @Test
    fun `with the current version accepted the gate does not ask`() = runTest {
        status = PolicyConsentStatus.ACCEPTED
        val prompt = prompt()

        prompt.enterGate()

        assertEquals(PolicyGate.ALLOWED, prompt.state.value.gate)
        assertNull(prompt.state.value.question)
    }

    @Test
    fun `going from one gated screen to another keeps the gate open until the last one leaves`() =
        runTest {
            status = PolicyConsentStatus.ACCEPTED
            val prompt = prompt()
            prompt.enterGate()

            // La pantalla siguiente entra antes de que salga la anterior, como en la navegación.
            prompt.enterGate()
            prompt.leaveGate()

            assertEquals(PolicyGate.ALLOWED, prompt.state.value.gate)
            prompt.leaveGate()
            assertNull(prompt.state.value.gate)
            // Sin pantallas abiertas, la siguiente vuelve a leer la aceptación.
            status = PolicyConsentStatus.NEVER_ACCEPTED
            prompt.enterGate()
            assertEquals(PolicyGate.WAITING, prompt.state.value.gate)
        }

    @Test
    fun `a failed save changes nothing and can be tried again`() = runTest {
        saves = PolicyAcceptance.NOT_SAVED
        val prompt = prompt()
        prompt.enterGate()

        prompt.accept()
        assertTrue(prompt.state.value.notSaved)
        assertEquals(PolicyGate.WAITING, prompt.state.value.gate)

        saves = PolicyAcceptance.SAVED
        prompt.accept()
        assertFalse(prompt.state.value.notSaved)
        assertEquals(PolicyGate.ALLOWED, prompt.state.value.gate)
    }

    @Test
    fun `opening asks only when an older accepted version changed`() = runTest {
        val prompt = prompt()
        prompt.checkOnOpen()
        assertNull(prompt.state.value.question)

        status = PolicyConsentStatus.OUTDATED
        prompt.checkOnOpen()
        assertEquals(PolicyQuestion.CHANGED, prompt.state.value.question)

        // Ahora no: se cierra, y vuelve en la próxima apertura.
        prompt.later()
        assertNull(prompt.state.value.question)
        prompt.checkOnOpen()
        assertEquals(PolicyQuestion.CHANGED, prompt.state.value.question)
    }

    @Test
    fun `reviewing the policy shows the right question for each status`() = runTest {
        val prompt = prompt()
        prompt.review()
        assertEquals(PolicyQuestion.ACCEPT_TO_CONNECT, prompt.state.value.question)

        status = PolicyConsentStatus.OUTDATED
        prompt.review()
        assertEquals(PolicyQuestion.CHANGED, prompt.state.value.question)

        status = PolicyConsentStatus.ACCEPTED
        prompt.review()
        assertNull(prompt.state.value.question)
    }

    @Test
    fun `the full terms open and close over the question`() = runTest {
        val prompt = prompt()
        prompt.review()

        prompt.openTerms()
        assertTrue(prompt.state.value.terms)
        prompt.closeTerms()

        assertFalse(prompt.state.value.terms)
        assertEquals(PolicyQuestion.ACCEPT_TO_CONNECT, prompt.state.value.question)
    }
}
