package io.github.yesidrobayo.shopledger.ui.phrase

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `Escuchar al tocar Decir`, guardado como marcas fuera de la base: encendido de inicio, sin perder
 * lo que la persona ya eligió, y la marca de que el micrófono ya se pidió.
 */
class VoiceInputPreferenceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun preference() = VoiceInputPreference({ folder.root }, Dispatchers.Unconfined)

    @Test
    fun withoutMarksListeningIsOn() = runBlocking {
        assertTrue(preference().load())
    }

    @Test
    fun whoTurnedItOnBeforeKeepsItOn() = runBlocking {
        File(folder.root, VoiceInputPreference.LEGACY_ON_FILE_NAME).createNewFile()

        assertTrue(preference().load())
    }

    @Test
    fun turningItOffIsKeptAndTurningItOnAgainClearsTheMarks() = runBlocking {
        File(folder.root, VoiceInputPreference.LEGACY_ON_FILE_NAME).createNewFile()

        assertTrue(preference().set(false))
        assertFalse(preference().load())
        assertFalse(File(folder.root, VoiceInputPreference.LEGACY_ON_FILE_NAME).exists())

        assertTrue(preference().set(true))
        assertTrue(preference().load())
        assertFalse(File(folder.root, VoiceInputPreference.OFF_FILE_NAME).exists())
    }

    @Test
    fun theChangeIsSeenWithoutReadingAgain() = runBlocking {
        val preference = preference()
        preference.load()

        preference.set(false)

        assertFalse(requireNotNull(preference.enabled.value) { "Ya se leyó" })
    }

    @Test
    fun theMicrophoneIsMarkedAsAskedOnce() = runBlocking {
        val preference = preference()
        assertFalse(preference.micAsked())

        preference.markMicAsked()
        preference.markMicAsked()

        assertTrue(preference.micAsked())
        assertTrue(preference().micAsked())
    }
}
