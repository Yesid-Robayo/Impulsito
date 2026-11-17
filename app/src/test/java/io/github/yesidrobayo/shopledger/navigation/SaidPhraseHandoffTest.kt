package io.github.yesidrobayo.shopledger.navigation

import io.github.yesidrobayo.shopledger.ui.phrase.VoiceNotice
import io.github.yesidrobayo.shopledger.ui.phrase.VoiceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lo que `Decir` escuchó llega a la hoja una sola vez, y lo último reemplaza lo que no se tomó. La
 * frase es inventada.
 */
class SaidPhraseHandoffTest {
    @Test
    fun theHeardPhraseArrivesOnlyOnce() {
        val handoff = SaidPhraseHandoff()

        handoff.deliver(VoiceResult.Heard("vendí 3 panes"))

        assertEquals(VoiceResult.Heard("vendí 3 panes"), handoff.take())
        assertNull(handoff.take())
        assertNull(handoff.toTake.value)
    }

    @Test
    fun theLatestResultReplacesOneNotTaken() {
        val handoff = SaidPhraseHandoff()

        handoff.deliver(VoiceResult.Heard("vendí 3 panes"))
        handoff.deliver(VoiceResult.Keyboard(VoiceNotice.FAILED))

        assertEquals(VoiceResult.Keyboard(VoiceNotice.FAILED), handoff.take())
    }

    @Test
    fun theNavigatorKeepsOneHandoffForTheWholeStack() {
        val navigator = AppNavigator(mutableListOf(AppNavKey.Home))

        navigator.said.deliver(VoiceResult.Keyboard(null))
        navigator.open(AppNavKey.Phrase())

        assertEquals(VoiceResult.Keyboard(null), navigator.said.take())
    }
}
