package io.github.yesidrobayo.shopledger.ui.permission

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El permiso de avisos se ofrece en contexto: al guardar el primer producto, nunca con otro, y
 * nunca si no hace falta pedirlo, como antes de Android 13 o con el permiso ya dado.
 */
class NotificationPermissionPromptTest {
    @Test
    fun theFirstProductOffersIt() = runTest {
        val prompt = prompt(needsAsking = true, firstProduct = true)

        prompt.offerAfterNewProduct()
        advanceUntilIdle()

        assertTrue(prompt.offered)
    }

    @Test
    fun anotherProductDoesNotInsist() = runTest {
        val prompt = prompt(needsAsking = true, firstProduct = false)

        prompt.offerAfterNewProduct()
        advanceUntilIdle()

        assertFalse(prompt.offered)
    }

    @Test
    fun withoutNeedingItNothingIsOfferedNorRead() = runTest {
        var reads = 0
        val prompt =
            NotificationPermissionPrompt(
                needsAsking = { false },
                isFirstProduct = {
                    reads++
                    true
                },
                scope = this,
            )

        prompt.offerAfterNewProduct()
        prompt.offer()
        advanceUntilIdle()

        assertFalse(prompt.offered)
        assertEquals(0, reads)
    }

    @Test
    fun anOfferCanBeDismissed() = runTest {
        val prompt = prompt(needsAsking = true, firstProduct = false)

        prompt.offer()
        assertTrue(prompt.offered)
        prompt.dismiss()

        assertFalse(prompt.offered)
    }

    private fun TestScope.prompt(needsAsking: Boolean, firstProduct: Boolean) =
        NotificationPermissionPrompt(
            needsAsking = { needsAsking },
            isFirstProduct = { firstProduct },
            scope = this,
        )
}
