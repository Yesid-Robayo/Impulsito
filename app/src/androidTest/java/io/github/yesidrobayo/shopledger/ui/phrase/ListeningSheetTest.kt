package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La pantalla de escuchar en el teléfono, con un motor falso: nunca usa el micrófono ni el servicio
 * de voz. Cada estado se ve como debe, y cada final llega una sola vez con lo que corresponde. Las
 * frases son inventadas.
 */
@RunWith(AndroidJUnit4::class)
class ListeningSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private var engine = FakeSpeechEngine()
    private val results = mutableListOf<VoiceResult>()

    private val offline = ListenPlan(ListenMode.OFFLINE_FIRST, 0)
    private val online = ListenPlan(ListenMode.ONLINE, 0)

    private fun show(offerTyping: Boolean = true, services: Int = 1) {
        engine = FakeSpeechEngine(ListenPlan.plansFor(onDevice = false, services = services))
        val shown = engine
        compose.setContent {
            PreviewTheme { ListeningSheet(shown, offerTyping) { results += it } }
        }
        compose.waitForIdle()
    }

    /** Lo que avisaría el motor, en el hilo principal como el de verdad. */
    private fun send(vararg events: ListenEvent) {
        compose.runOnIdle { events.forEach(engine::send) }
        compose.waitForIdle()
    }

    @Test
    fun itStartsListeningRightAwayWithTheHint() {
        show()

        assertEquals(listOf(offline), engine.listened)
        compose.onNodeWithText(text(R.string.listening_title)).assertIsDisplayed()
        compose
            .onNodeWithText(
                text(R.string.listening_hint, text(R.string.phrase_example_sale)),
            ).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_done)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_cancel)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.listening_type)).assertIsDisplayed()
    }

    @Test
    fun withoutTypingOfferedOnlyDoneAndCancelShow() {
        show(offerTyping = false)

        compose.onNodeWithText(text(R.string.listening_type)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.phrase_done)).assertIsDisplayed()
    }

    @Test
    fun whatIsUnderstoodAppearsWhileSpeaking() {
        show()

        send(ListenEvent.Ready, ListenEvent.Partial("vendí 3 panes"))

        compose.onNodeWithTag(PARTIAL_TAG).assertIsDisplayed()
        compose.onNodeWithText("vendí 3 panes").assertIsDisplayed()
        assertTrue(results.isEmpty())
    }

    @Test
    fun theFinalTextEndsWithThePhraseAndFreesTheMicrophone() {
        show()

        send(ListenEvent.Ready, ListenEvent.Final("vendí 3 panes en efectivo"))

        assertEquals(listOf(VoiceResult.Heard("vendí 3 panes en efectivo")), results)
        assertTrue(engine.releases > 0)
    }

    @Test
    fun doneStopsTheEngineAndKeepsWhatWasSaid() {
        show()
        send(ListenEvent.Ready, ListenEvent.Partial("pagué la luz 80000"))

        compose.onNodeWithText(text(R.string.phrase_done)).performClick()
        compose.waitForIdle()
        assertEquals(1, engine.stops)

        send(ListenEvent.Final(""))

        assertEquals(listOf(VoiceResult.Heard("pagué la luz 80000")), results)
    }

    @Test
    fun aNetworkErrorRetriesOnlineWithoutAskingAndThenFallsToTheKeyboard() {
        show()

        send(ListenEvent.Error(ListenError.NETWORK))

        assertEquals(listOf(offline, online), engine.listened)
        compose.onNodeWithText(text(R.string.listening_title)).assertIsDisplayed()
        assertTrue(results.isEmpty())

        send(ListenEvent.Error(ListenError.NETWORK))

        assertEquals(listOf(VoiceResult.Keyboard(VoiceNotice.FAILED)), results)
    }

    @Test
    fun aServiceWithoutMicrophoneMovesOnAndTheEndSaysSo() {
        show()

        send(ListenEvent.Error(ListenError.PERMISSION))

        assertEquals(listOf(offline, online), engine.listened)
        assertTrue(results.isEmpty())

        send(ListenEvent.Error(ListenError.LANGUAGE))

        assertEquals(listOf(VoiceResult.Keyboard(VoiceNotice.SERVICE_NO_MIC)), results)
    }

    @Test
    fun theThirdServiceUnderstandsAfterOneWithoutMicrophoneAndOneBroken() {
        show(services = 3)

        // El primero no tiene micrófono, de las dos formas; el segundo falla en el cliente.
        send(ListenEvent.Error(ListenError.PERMISSION))
        send(ListenEvent.Error(ListenError.PERMISSION))
        send(ListenEvent.Error(ListenError.OTHER))
        send(ListenEvent.Error(ListenError.OTHER))
        compose.onNodeWithText(text(R.string.listening_title)).assertIsDisplayed()
        send(ListenEvent.Ready, ListenEvent.Partial("vendí 3 panes"))
        compose.onNodeWithText("vendí 3 panes").assertIsDisplayed()
        send(ListenEvent.Final("vendí 3 panes en efectivo"))

        assertEquals(
            listOf(
                ListenPlan(ListenMode.OFFLINE_FIRST, 0),
                ListenPlan(ListenMode.ONLINE, 0),
                ListenPlan(ListenMode.OFFLINE_FIRST, 1),
                ListenPlan(ListenMode.ONLINE, 1),
                ListenPlan(ListenMode.OFFLINE_FIRST, 2),
            ),
            engine.listened,
        )
        assertEquals(listOf(VoiceResult.Heard("vendí 3 panes en efectivo")), results)
    }

    @Test
    fun aServiceWithoutMicrophoneLeavesTheKeyboardWithItsNotice() {
        compose.setContent {
            PreviewTheme {
                PhraseScreen(PhraseUiState(voiceNotice = VoiceNotice.SERVICE_NO_MIC), onAction = {})
            }
        }

        compose.onNodeWithText(text(R.string.phrase_voice_service_no_mic)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_mic_help)).assertExists()
    }

    @Test
    fun silenceOffersToListenAgain() {
        show()

        send(ListenEvent.Ready, ListenEvent.Error(ListenError.NO_SPEECH))

        compose.onNodeWithText(text(R.string.listening_silent_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.listening_silent_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_done)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.listening_again)).performClick()
        compose.waitForIdle()

        assertEquals(listOf(offline, offline), engine.listened)
        compose.onNodeWithText(text(R.string.listening_title)).assertIsDisplayed()
        assertTrue(results.isEmpty())
    }

    @Test
    fun typingFromSilenceOpensTheKeyboardWithoutNotice() {
        show()
        send(ListenEvent.Error(ListenError.NO_SPEECH))

        compose.onNodeWithText(text(R.string.listening_type)).performClick()
        compose.waitForIdle()

        assertEquals(listOf(VoiceResult.Keyboard(null)), results)
    }

    @Test
    fun cancelFreesTheMicrophoneAndEndsOnce() {
        show()
        send(ListenEvent.Ready, ListenEvent.Partial("vendí"))

        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.waitForIdle()
        send(ListenEvent.Final("vendí 3 panes"))

        assertEquals(listOf(VoiceResult.Cancelled), results)
        assertTrue(engine.releases > 0)
    }

    @Test
    fun thePermissionSheetExplainsBeforeAsking() {
        var allowed = 0
        var typed = 0
        compose.setContent {
            PreviewTheme {
                MicPermissionSheet(onAllow = { allowed++ }, onType = { typed++ }, onDismiss = {})
            }
        }

        compose
            .onNodeWithText(text(R.string.permission_mic_body, text(R.string.app_name)))
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.permission_mic_allow_action)).performClick()
        compose.onNodeWithText(text(R.string.listening_type)).performClick()

        assertEquals(1, allowed)
        assertEquals(1, typed)
    }

    @Test
    fun aBlockedMicrophoneOffersThePhoneSettingsOnlyByATap() {
        val actions = mutableListOf<PhraseAction>()
        compose.setContent {
            PreviewTheme {
                PhraseScreen(
                    PhraseUiState(voiceNotice = VoiceNotice.PERMISSION_BLOCKED),
                    onAction = { actions += it },
                )
            }
        }

        compose.onNodeWithText(text(R.string.phrase_mic_blocked)).assertIsDisplayed()
        assertTrue(PhraseAction.OpenMicSettings !in actions)
        compose.onNodeWithText(text(R.string.phrase_mic_open_settings)).performClick()

        assertTrue(PhraseAction.OpenMicSettings in actions)
    }

    @Test
    fun aFailedListenLeavesTheKeyboardWithAShortNotice() {
        compose.setContent {
            PreviewTheme {
                PhraseScreen(PhraseUiState(voiceNotice = VoiceNotice.FAILED), onAction = {})
            }
        }

        compose.onNodeWithText(text(R.string.phrase_voice_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phrase_mic_help)).assertExists()
    }
}

/** Un motor que no oye nada: la prueba manda lo que el de verdad avisaría. */
private class FakeSpeechEngine(
    override val plans: List<ListenPlan> = ListenPlan.plansFor(onDevice = false, services = 1),
) : SpeechEngine {
    val listened = mutableListOf<ListenPlan>()
    var stops = 0
        private set
    var releases = 0
        private set
    private var onEvent: ((ListenEvent) -> Unit)? = null

    override fun listen(
        plan: ListenPlan,
        onEvent: (ListenEvent) -> Unit,
        onLevel: (Float) -> Unit,
    ) {
        listened += plan
        this.onEvent = onEvent
    }

    override fun stop() {
        stops++
    }

    override fun release() {
        releases++
    }

    fun send(event: ListenEvent) {
        onEvent?.invoke(event)
    }
}
