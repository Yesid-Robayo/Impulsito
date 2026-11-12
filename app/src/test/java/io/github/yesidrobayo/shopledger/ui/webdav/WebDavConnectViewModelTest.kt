package io.github.yesidrobayo.shopledger.ui.webdav

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.CurrentDestination
import io.github.yesidrobayo.shopledger.domain.model.backup.TestedWebDav
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavAddress
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavAddressProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavLogin
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSaveResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSuggestion
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestFailure
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestStep
import io.github.yesidrobayo.shopledger.domain.port.WebDavConnectionPort
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ConnectWebDavUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `Conectar el servicio`: confirma el servidor de una dirección escrita a mano, rechaza `http://`
 * sin abrir conexiones, muestra los pasos, cancela, dice cada fallo sin perder lo escrito, pregunta
 * antes de cambiar de destino, guarda solo lo probado y nunca guarda la contraseña en el estado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebDavConnectViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private val suggestion =
        WebDavSuggestion("example", "Ejemplo", "https://dav.example.com/files", false, false)
    private val tested = TestedWebDav("abc123", "App", hasCopies = false)

    private inner class FakePort : WebDavConnectionPort {
        override val suggestions = listOf(suggestion)
        var network = true
        var currentDestination: CurrentDestination? = null
        var result: WebDavTestResult = WebDavTestResult.Passed(tested)
        var saveResult: WebDavSaveResult = WebDavSaveResult.Connected(hasCopies = false)
        var gate: CompletableDeferred<Unit>? = null
        val tests = mutableListOf<WebDavLogin>()
        val saves = mutableListOf<TestedWebDav>()

        override fun inspect(url: String): WebDavAddress = when {
            url.startsWith("http://") -> WebDavAddress.Rejected(WebDavAddressProblem.NOT_HTTPS)
            '?' in url -> WebDavAddress.Rejected(WebDavAddressProblem.INVALID)
            else -> WebDavAddress.Accepted(url.substringAfter("://").substringBefore('/'))
        }

        override fun hasNetwork() = network

        override suspend fun current() = currentDestination

        override suspend fun test(
            login: WebDavLogin,
            appName: String,
            onStep: (WebDavTestStep) -> Unit,
        ): WebDavTestResult {
            tests += login
            onStep(WebDavTestStep.LIST)
            onStep(WebDavTestStep.UPLOAD)
            gate?.await()
            return result
        }

        override suspend fun save(login: WebDavLogin, tested: TestedWebDav): WebDavSaveResult {
            saves += tested
            return saveResult
        }
    }

    private val port = FakePort()
    private val saved = SavedStateHandle()

    private fun model(suggestionKey: String? = null) =
        WebDavConnectViewModel(ConnectWebDavUseCase(port), "App", suggestionKey, saved, dispatcher)

    private fun WebDavConnectViewModel.fill(url: String = "https://mine.example.org/dav") {
        onAction(WebDavConnectAction.UrlChange(url))
        onAction(WebDavConnectAction.UserChange("copias"))
        onAction(WebDavConnectAction.PasswordChange("inventada"))
    }

    @Test
    fun `the button waits for the three fields`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        assertFalse(model.uiState.value.canConnect)
        model.onAction(WebDavConnectAction.UrlChange("https://mine.example.org/dav"))
        model.onAction(WebDavConnectAction.UserChange("copias"))
        assertFalse(model.uiState.value.canConnect)
        model.onAction(WebDavConnectAction.PasswordChange("x"))
        assertTrue(model.uiState.value.canConnect)
        model.onAction(WebDavConnectAction.TogglePassword)
        assertTrue(model.uiState.value.passwordVisible)
    }

    @Test
    fun `http is rejected in the field without any request`() = runTest(dispatcher) {
        val model = model()
        model.fill("http://mine.example.org")

        model.onAction(WebDavConnectAction.Connect)
        advanceUntilIdle()

        assertEquals(WebDavAddressProblem.NOT_HTTPS, model.uiState.value.urlError)
        assertTrue(port.tests.isEmpty())
        model.onAction(WebDavConnectAction.UrlChange("https://mine.example.org?x"))
        assertNull(model.uiState.value.urlError)
        model.onAction(WebDavConnectAction.Connect)
        assertEquals(WebDavAddressProblem.INVALID, model.uiState.value.urlError)
    }

    @Test
    fun `a typed address asks to confirm the host first`() = runTest(dispatcher) {
        val model = model()
        model.fill()

        model.onAction(WebDavConnectAction.Connect)
        assertEquals(WebDavPhase.CONFIRM_HOST, model.uiState.value.phase)
        assertEquals("mine.example.org", model.uiState.value.host)
        assertTrue(port.tests.isEmpty())

        model.onAction(WebDavConnectAction.DismissHost)
        assertEquals(WebDavPhase.EDITING, model.uiState.value.phase)
        assertTrue(port.tests.isEmpty())

        model.onAction(WebDavConnectAction.Connect)
        model.onAction(WebDavConnectAction.ConfirmHost)
        advanceUntilIdle()

        assertEquals(1, port.tests.size)
        // Lo probado espera la verificación del sistema antes de guardarse.
        assertEquals(WebDavPhase.VERIFYING, model.uiState.value.phase)
        assertTrue(model.uiState.value.working)
        assertTrue(port.saves.isEmpty())
        model.onAction(WebDavConnectAction.Verified(true))
        advanceUntilIdle()
        assertEquals(listOf(tested), port.saves)
        assertEquals(WebDavConnectUiState.Connected(false), model.uiState.value.connected)
        // Conectado, la contraseña ya no queda ni en memoria de la pantalla.
        assertEquals("", model.uiState.value.password)
    }

    @Test
    fun `a suggestion fills the address, focuses the user and skips the host question`() =
        runTest(dispatcher) {
            val model = model()
            model.onAction(WebDavConnectAction.PickSuggestion("example"))
            assertEquals(suggestion.url, model.uiState.value.url)
            assertEquals(suggestion, model.uiState.value.suggestion)
            assertEquals(1, model.uiState.value.focusUser)
            model.onAction(WebDavConnectAction.UserChange("copias"))
            model.onAction(WebDavConnectAction.PasswordChange("inventada"))

            model.onAction(WebDavConnectAction.Connect)
            advanceUntilIdle()
            model.onAction(WebDavConnectAction.Verified(true))
            advanceUntilIdle()

            assertEquals(1, port.tests.size)
            assertEquals(WebDavConnectUiState.Connected(false), model.uiState.value.connected)
        }

    @Test
    fun `opening with a suggestion key fills it`() = runTest(dispatcher) {
        val model = model(suggestionKey = "example")
        advanceUntilIdle()
        assertEquals(suggestion.url, model.uiState.value.url)
    }

    @Test
    fun `steps show progress and the test can be cancelled without saving`() = runTest(dispatcher) {
        port.gate = CompletableDeferred()
        val model = model(suggestionKey = "example")
        model.onAction(WebDavConnectAction.UserChange("copias"))
        model.onAction(WebDavConnectAction.PasswordChange("inventada"))

        model.onAction(WebDavConnectAction.Connect)
        advanceUntilIdle()

        val testing = model.uiState.value
        assertEquals(WebDavPhase.TESTING, testing.phase)
        assertTrue(testing.working)
        assertEquals(StepStatus.DONE, testing.steps[0].status)
        assertEquals(StepStatus.DONE, testing.steps[1].status)
        assertEquals(StepStatus.RUNNING, testing.steps[2].status)
        assertEquals(StepStatus.PENDING, testing.steps[6].status)

        model.onAction(WebDavConnectAction.Cancel)
        advanceUntilIdle()

        assertEquals(WebDavPhase.EDITING, model.uiState.value.phase)
        assertTrue(model.uiState.value.steps.isEmpty())
        assertTrue(port.saves.isEmpty())
        assertEquals("inventada", model.uiState.value.password)
    }

    @Test
    fun `a rejected password is said in its field and keeps what was typed`() =
        runTest(dispatcher) {
            port.result =
                WebDavTestResult.Failed(WebDavTestStep.LIST, WebDavTestFailure.CREDENTIAL_REJECTED)
            val model = model(suggestionKey = "example")
            model.onAction(WebDavConnectAction.UserChange("copias"))
            model.onAction(WebDavConnectAction.PasswordChange("mala"))

            model.onAction(WebDavConnectAction.Connect)
            advanceUntilIdle()

            val state = model.uiState.value
            assertTrue(state.passwordRejected)
            assertNull(state.notice)
            assertEquals("mala", state.password)
            assertEquals(StepStatus.FAILED, state.steps[0].status)
            assertTrue(port.saves.isEmpty())
            model.onAction(WebDavConnectAction.PasswordChange("otra"))
            assertFalse(model.uiState.value.passwordRejected)
        }

    @Test
    fun `other failures name the step and are never retried alone`() = runTest(dispatcher) {
        port.result =
            WebDavTestResult.Failed(WebDavTestStep.NO_OVERWRITE, WebDavTestFailure.UNSUPPORTED)
        val model = model(suggestionKey = "example")
        model.onAction(WebDavConnectAction.UserChange("copias"))
        model.onAction(WebDavConnectAction.PasswordChange("inventada"))

        model.onAction(WebDavConnectAction.Connect)
        advanceUntilIdle()

        val state = model.uiState.value
        assertEquals(
            WebDavNotice.Failed(WebDavTestStep.NO_OVERWRITE, WebDavTestFailure.UNSUPPORTED),
            state.notice,
        )
        assertEquals(StepStatus.DONE, state.steps[3].status)
        assertEquals(StepStatus.FAILED, state.steps[4].status)
        assertEquals(StepStatus.PENDING, state.steps[5].status)
        assertEquals(1, port.tests.size)
        assertTrue(port.saves.isEmpty())
    }

    @Test
    fun `without internet nothing is tried and the text is kept`() = runTest(dispatcher) {
        port.network = false
        val model = model(suggestionKey = "example")
        model.onAction(WebDavConnectAction.UserChange("copias"))
        model.onAction(WebDavConnectAction.PasswordChange("inventada"))

        model.onAction(WebDavConnectAction.Connect)
        advanceUntilIdle()

        assertEquals(WebDavNotice.NoInternet, model.uiState.value.notice)
        assertTrue(port.tests.isEmpty())
        assertEquals("inventada", model.uiState.value.password)
    }

    @Test
    fun `another destination asks before changing and keeps working if not`() =
        runTest(dispatcher) {
            port.currentDestination =
                CurrentDestination(
                    BackupDestinationKind.DOCUMENT_SLOTS,
                    "slots",
                    null,
                    null,
                    "Drive",
                )
            val model = model(suggestionKey = "example")
            model.onAction(WebDavConnectAction.UserChange("copias"))
            model.onAction(WebDavConnectAction.PasswordChange("inventada"))

            model.onAction(WebDavConnectAction.Connect)
            advanceUntilIdle()

            assertEquals(WebDavPhase.CONFIRM_CHANGE, model.uiState.value.phase)
            assertEquals("Drive", model.uiState.value.changeFrom)
            assertTrue(port.saves.isEmpty())

            model.onAction(WebDavConnectAction.DismissChange)
            assertEquals(WebDavPhase.EDITING, model.uiState.value.phase)
            assertTrue(port.saves.isEmpty())

            model.onAction(WebDavConnectAction.Connect)
            advanceUntilIdle()
            model.onAction(WebDavConnectAction.ConfirmChange)
            advanceUntilIdle()
            assertEquals(WebDavPhase.VERIFYING, model.uiState.value.phase)
            assertNull(model.uiState.value.changeFrom)
            assertTrue(port.saves.isEmpty())
            model.onAction(WebDavConnectAction.Verified(true))
            advanceUntilIdle()

            assertEquals(listOf(tested), port.saves)
            assertEquals(WebDavConnectUiState.Connected(false), model.uiState.value.connected)
            model.onAction(WebDavConnectAction.ConnectedHandled)
            assertNull(model.uiState.value.connected)
        }

    @Test
    fun `reconnecting fills address and user but never the password`() = runTest(dispatcher) {
        port.currentDestination =
            CurrentDestination(
                BackupDestinationKind.WEBDAV,
                tested.destinationId,
                "https://mine.example.org/dav/",
                "copias",
                "mine.example.org",
            )
        val model = model()
        advanceUntilIdle()

        val state = model.uiState.value
        assertTrue(state.reconnect)
        assertEquals("https://mine.example.org/dav/", state.url)
        assertEquals("copias", state.username)
        assertEquals("", state.password)

        // El mismo destino no pregunta si se cambia.
        model.onAction(WebDavConnectAction.PasswordChange("nueva"))
        model.onAction(WebDavConnectAction.Connect)
        model.onAction(WebDavConnectAction.ConfirmHost)
        advanceUntilIdle()
        assertEquals(WebDavPhase.VERIFYING, model.uiState.value.phase)
        model.onAction(WebDavConnectAction.Verified(true))
        advanceUntilIdle()
        assertEquals(listOf(tested), port.saves)
    }

    @Test
    fun `only address and user survive in the saved state`() = runTest(dispatcher) {
        val model = model()
        model.fill()

        assertEquals("https://mine.example.org/dav", saved.get<String>("url"))
        assertEquals("copias", saved.get<String>("username"))
        assertFalse(saved.keys().any { saved.get<Any>(it) == "inventada" })

        val restored = model()
        advanceUntilIdle()
        assertEquals("https://mine.example.org/dav", restored.uiState.value.url)
        assertEquals("", restored.uiState.value.password)
    }

    @Test
    fun `a failed save says so and nothing is connected`() = runTest(dispatcher) {
        port.saveResult = WebDavSaveResult.SaveFailed
        val model = model(suggestionKey = "example")
        model.onAction(WebDavConnectAction.UserChange("copias"))
        model.onAction(WebDavConnectAction.PasswordChange("inventada"))

        model.onAction(WebDavConnectAction.Connect)
        advanceUntilIdle()
        model.onAction(WebDavConnectAction.Verified(true))
        advanceUntilIdle()

        assertEquals(WebDavNotice.SaveFailed, model.uiState.value.notice)
        assertNull(model.uiState.value.connected)
        assertEquals("inventada", model.uiState.value.password)
    }

    @Test
    fun `an address rejected by the test goes to the field`() = runTest(dispatcher) {
        port.result = WebDavTestResult.AddressRejected(WebDavAddressProblem.INVALID)
        val model = model(suggestionKey = "example")
        model.onAction(WebDavConnectAction.UserChange("copias"))
        model.onAction(WebDavConnectAction.PasswordChange("inventada"))

        model.onAction(WebDavConnectAction.Connect)
        advanceUntilIdle()

        assertEquals(WebDavAddressProblem.INVALID, model.uiState.value.urlError)
        assertTrue(model.uiState.value.steps.isEmpty())
    }

    @Test
    fun `a cancelled verification saves nothing and keeps what was typed`() = runTest(dispatcher) {
        val model = model(suggestionKey = "example")
        model.onAction(WebDavConnectAction.UserChange("copias"))
        model.onAction(WebDavConnectAction.PasswordChange("inventada"))
        // Fuera de la espera, una respuesta no guarda nada.
        model.onAction(WebDavConnectAction.Verified(true))
        assertTrue(port.saves.isEmpty())

        model.onAction(WebDavConnectAction.Connect)
        advanceUntilIdle()
        model.onAction(WebDavConnectAction.Verified(false))
        advanceUntilIdle()

        val state = model.uiState.value
        assertEquals(WebDavPhase.EDITING, state.phase)
        assertTrue(state.steps.isEmpty())
        assertEquals("inventada", state.password)
        assertNull(state.connected)
        assertTrue(port.saves.isEmpty())

        // Una respuesta tardía tampoco guarda lo que ya se soltó.
        model.onAction(WebDavConnectAction.Verified(true))
        advanceUntilIdle()
        assertTrue(port.saves.isEmpty())
    }

    @Test
    fun `a cancelled verification of a change keeps the current destination`() =
        runTest(dispatcher) {
            port.currentDestination =
                CurrentDestination(
                    BackupDestinationKind.DOCUMENT_SLOTS,
                    "slots",
                    null,
                    null,
                    "Drive",
                )
            val model = model(suggestionKey = "example")
            model.onAction(WebDavConnectAction.UserChange("copias"))
            model.onAction(WebDavConnectAction.PasswordChange("inventada"))
            model.onAction(WebDavConnectAction.Connect)
            advanceUntilIdle()
            model.onAction(WebDavConnectAction.ConfirmChange)
            model.onAction(WebDavConnectAction.Verified(false))
            advanceUntilIdle()

            assertEquals(WebDavPhase.EDITING, model.uiState.value.phase)
            assertTrue(port.saves.isEmpty())
            // Sin lo probado, confirmar otra vez no pide nada: hay que probar de nuevo.
            model.onAction(WebDavConnectAction.ConfirmChange)
            assertEquals(WebDavPhase.EDITING, model.uiState.value.phase)
        }
}
