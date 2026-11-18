package io.github.yesidrobayo.shopledger.ui.permission

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El permiso de avisos tiene una sola forma de pedirse: una hoja que explica y, después, el
 * sistema. Sola, se ofrece una vez en la vida del teléfono, al terminar la configuración inicial o
 * al abrir la app ya configurada; a pedido, al encender los avisos de stock. Nunca se ofrece si no
 * hace falta, ni a quien ya lo negó.
 */
class NotificationPermissionPromptTest {
    private var asked = false
    private var marks = 0

    @Test
    fun theFirstTimeItIsOfferedAndMarked() = runTest {
        val prompt = prompt(needsAsking = true)

        prompt.offerOnce()
        advanceUntilIdle()

        assertTrue(prompt.offered)
        assertTrue(asked)
    }

    @Test
    fun onceAskedItIsNeverOfferedAgainOnItsOwn() = runTest {
        asked = true
        val prompt = prompt(needsAsking = true)

        prompt.offerOnce()
        advanceUntilIdle()

        assertFalse(prompt.offered)
        assertEquals(0, marks)
    }

    @Test
    fun closingTheSheetStillCountsAsAsked() = runTest {
        val first = prompt(needsAsking = true)
        first.offerOnce()
        advanceUntilIdle()
        first.dismiss()

        // La siguiente apertura de la app, con un proceso nuevo.
        val next = prompt(needsAsking = true)
        next.offerOnce()
        advanceUntilIdle()

        assertFalse(first.offered)
        assertFalse(next.offered)
    }

    @Test
    fun someoneWhoAlreadyDeclinedIsNotAskedAndItIsMarked() = runTest {
        val prompt = prompt(needsAsking = true, answeredBefore = true)

        prompt.offerOnce()
        advanceUntilIdle()

        assertFalse(prompt.offered)
        assertTrue(asked)
    }

    @Test
    fun withThePermissionGrantedOrBeforeAndroid13NothingIsOffered() = runTest {
        val prompt = prompt(needsAsking = false)

        prompt.offerOnce()
        prompt.offer()
        advanceUntilIdle()

        assertFalse(prompt.offered)
        assertTrue(asked)
    }

    @Test
    fun theSameProcessDecidesOnlyOnce() = runTest {
        val prompt = prompt(needsAsking = true)

        prompt.offerOnce()
        advanceUntilIdle()
        prompt.dismiss()
        asked = false
        prompt.offerOnce()
        advanceUntilIdle()

        assertFalse(prompt.offered)
        assertEquals(1, marks)
    }

    @Test
    fun turningOnStockAlertsOffersItEvenAfterAskingOnce() = runTest {
        asked = true
        val prompt = prompt(needsAsking = true)

        prompt.offer()

        assertTrue(prompt.offered)
    }

    @Test
    fun anOfferCanBeDismissed() = runTest {
        val prompt = prompt(needsAsking = true)

        prompt.offer()
        assertTrue(prompt.offered)
        prompt.dismiss()

        assertFalse(prompt.offered)
    }

    private fun TestScope.prompt(needsAsking: Boolean, answeredBefore: Boolean = false) =
        NotificationPermissionPrompt(
            needsAsking = { needsAsking },
            answeredBefore = { answeredBefore },
            wasAsked = { asked },
            markAsked = {
                marks++
                asked = true
                true
            },
            scope = this,
        )
}
