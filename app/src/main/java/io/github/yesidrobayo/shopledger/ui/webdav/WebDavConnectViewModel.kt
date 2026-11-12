package io.github.yesidrobayo.shopledger.ui.webdav

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.TestedWebDav
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavAddress
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavLogin
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSaveResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestFailure
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestStep
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ConnectWebDavUseCase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `Conectar el servicio`: probar el servicio antes de guardar nada, y guardarlo solo si pasa.
 *
 * - Una dirección escrita a mano pide confirmar el servidor, en ASCII, antes de la primera
 *   petición: un certificado válido no prueba que sea el servicio correcto. Una sugerencia no lo
 *   pide, porque su dirección viene con la app.
 * - `http://` o una dirección mal escrita se rechazan sin abrir ninguna conexión, y sin internet no
 *   se intenta.
 * - La prueba muestra en qué paso va, se puede cancelar y nunca se reintenta sola.
 * - Si pasa y hay otro destino conectado, pregunta antes de cambiar: el actual sigue funcionando
 *   hasta confirmar.
 * - Si algo falla, lo escrito se conserva y nada se guarda.
 * - **La contraseña solo vive en memoria.** La dirección y el usuario van al estado guardado de la
 *   entrada; la contraseña nunca, ni la guardada se precarga al reconectar.
 *
 * @param appName el nombre de la app, que da la carpeta de copias.
 * @param suggestionKey la sugerencia con que se abrió la pantalla, si alguna.
 * @param computation el hilo de la red y del disco, fuera del principal.
 */
class WebDavConnectViewModel(
    private val port: ConnectWebDavUseCase,
    private val appName: String,
    suggestionKey: String?,
    private val savedState: SavedStateHandle,
    private val computation: CoroutineDispatcher,
) : ViewModel() {
    private val state =
        MutableStateFlow(
            WebDavConnectUiState(
                suggestions = port.suggestions,
                url = savedState[URL_KEY] ?: "",
                username = savedState[USER_KEY] ?: "",
            ).withSuggestion(),
        )
    val uiState: StateFlow<WebDavConnectUiState> = state.asStateFlow()

    private var testing: Job? = null
    private var tested: TestedWebDav? = null
    private var confirmedUrl: String? = null

    init {
        val restored = savedState.contains(URL_KEY)
        if (!restored && suggestionKey != null) pick(suggestionKey)
        viewModelScope.launch {
            val current = read { port.current() }
            // Reconectar: la dirección y el usuario del servicio conectado; la contraseña, nunca.
            if (!restored && suggestionKey == null &&
                current?.kind == BackupDestinationKind.WEBDAV
            ) {
                setUrl(current.baseUrl.orEmpty())
                setUser(current.account.orEmpty())
                state.update { it.copy(reconnect = true) }
            }
        }
    }

    fun onAction(action: WebDavConnectAction) {
        when (action) {
            is WebDavConnectAction.UrlChange -> setUrl(action.value)

            is WebDavConnectAction.UserChange -> setUser(action.value)

            is WebDavConnectAction.PasswordChange ->
                state.update {
                    it.copy(password = action.value, passwordRejected = false, notice = null)
                }

            WebDavConnectAction.TogglePassword ->
                state.update { it.copy(passwordVisible = !it.passwordVisible) }

            is WebDavConnectAction.PickSuggestion -> pick(action.key)

            WebDavConnectAction.Connect -> connect()

            WebDavConnectAction.ConfirmHost -> {
                confirmedUrl = state.value.url.trim()
                runTest()
            }

            WebDavConnectAction.DismissHost ->
                state.update { it.copy(phase = WebDavPhase.EDITING, host = null) }

            WebDavConnectAction.Cancel -> cancel()

            WebDavConnectAction.ConfirmChange -> if (tested != null) askVerification()

            is WebDavConnectAction.Verified -> verified(action.allowed)

            WebDavConnectAction.DismissChange -> {
                tested = null
                state.update {
                    it.copy(phase = WebDavPhase.EDITING, changeFrom = null, steps = emptyList())
                }
            }

            // Lo resuelve la navegación.
            WebDavConnectAction.Back -> Unit

            WebDavConnectAction.ConnectedHandled -> state.update { it.copy(connected = null) }
        }
    }

    override fun onCleared() {
        // Sin pantalla, la prueba no sigue: nada se guarda.
        testing?.cancel()
    }

    private fun setUrl(value: String) {
        savedState[URL_KEY] = value
        state.update {
            it.copy(url = value, urlError = null, notice = null).withSuggestion()
        }
    }

    private fun setUser(value: String) {
        savedState[USER_KEY] = value
        state.update { it.copy(username = value, passwordRejected = false, notice = null) }
    }

    private fun pick(key: String) {
        val suggestion = port.suggestions.firstOrNull { it.key == key } ?: return
        setUrl(suggestion.url)
        state.update { it.copy(focusUser = it.focusUser + 1) }
    }

    private fun connect() {
        val current = state.value
        if (!current.canConnect) return
        val url = current.url.trim()
        val address =
            when (val inspected = port.inspect(url)) {
                is WebDavAddress.Rejected -> {
                    state.update { it.copy(urlError = inspected.problem) }
                    return
                }

                is WebDavAddress.Accepted -> inspected
            }
        if (!port.hasNetwork()) {
            state.update { it.copy(notice = WebDavNotice.NoInternet) }
            return
        }
        if (current.suggestion == null && confirmedUrl != url) {
            state.update {
                it.copy(phase = WebDavPhase.CONFIRM_HOST, host = address.asciiHost, notice = null)
            }
        } else {
            runTest()
        }
    }

    private fun runTest() {
        val current = state.value
        val login = WebDavLogin(current.url.trim(), current.username.trim(), current.password)
        state.update {
            it.copy(
                phase = WebDavPhase.TESTING,
                host = null,
                steps = WebDavTestStep.entries.map { step ->
                    StepProgress(step, StepStatus.PENDING)
                },
                urlError = null,
                passwordRejected = false,
                notice = null,
            )
        }
        testing = viewModelScope.launch {
            val result =
                try {
                    withContext(computation) { port.test(login, appName, ::markRunning) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Un fallo inesperado no guarda nada y se dice como uno de red.
                    WebDavTestResult.Failed(WebDavTestStep.LIST, WebDavTestFailure.UNREACHABLE)
                }
            onTested(login, result)
        }
    }

    private fun markRunning(step: WebDavTestStep) = state.update { current ->
        current.copy(
            steps = current.steps.map {
                when {
                    it.step.ordinal < step.ordinal -> it.copy(status = StepStatus.DONE)
                    it.step == step -> it.copy(status = StepStatus.RUNNING)
                    else -> it
                }
            },
        )
    }

    private suspend fun onTested(login: WebDavLogin, result: WebDavTestResult) {
        when (result) {
            is WebDavTestResult.Passed -> {
                tested = result.tested
                state.update {
                    it.copy(steps = it.steps.map { step -> step.copy(status = StepStatus.DONE) })
                }
                val current = read { port.current() }
                if (ConnectWebDavUseCase.changesDestination(current, result.tested)) {
                    state.update {
                        it.copy(phase = WebDavPhase.CONFIRM_CHANGE, changeFrom = current?.label)
                    }
                } else {
                    askVerification()
                }
            }

            is WebDavTestResult.AddressRejected ->
                state.update {
                    it.copy(
                        phase = WebDavPhase.EDITING,
                        steps = emptyList(),
                        urlError = result.problem,
                    )
                }

            WebDavTestResult.MissingField ->
                state.update { it.copy(phase = WebDavPhase.EDITING, steps = emptyList()) }

            is WebDavTestResult.Failed -> failed(result.step, result.failure)
        }
    }

    private fun failed(step: WebDavTestStep, failure: WebDavTestFailure) = state.update { current ->
        val steps = current.steps.map {
            when {
                it.step.ordinal < step.ordinal -> it.copy(status = StepStatus.DONE)
                it.step == step -> it.copy(status = StepStatus.FAILED)
                else -> it.copy(status = StepStatus.PENDING)
            }
        }
        when (failure) {
            // Un usuario o una contraseña que no sirven se dicen en el campo.
            WebDavTestFailure.CREDENTIAL_REJECTED ->
                current.copy(phase = WebDavPhase.EDITING, steps = steps, passwordRejected = true)

            WebDavTestFailure.FORBIDDEN,
            WebDavTestFailure.RATE_LIMITED,
            WebDavTestFailure.UNREACHABLE,
            WebDavTestFailure.INSECURE,
            WebDavTestFailure.NO_SPACE,
            WebDavTestFailure.BASE_URL,
            WebDavTestFailure.REDIRECT,
            WebDavTestFailure.UNSUPPORTED,
            ->
                current.copy(
                    phase = WebDavPhase.EDITING,
                    steps = steps,
                    notice = WebDavNotice.Failed(step, failure),
                )
        }
    }

    /**
     * Conectar, reconectar o cambiar de destino es sensible: antes de guardar, la pantalla pide la
     * verificación del sistema y la contesta con [WebDavConnectAction.Verified].
     */
    private fun askVerification() =
        state.update { it.copy(phase = WebDavPhase.VERIFYING, changeFrom = null) }

    private fun verified(allowed: Boolean) {
        if (state.value.phase != WebDavPhase.VERIFYING) return
        val candidate = tested
        if (allowed && candidate != null) {
            save(candidate)
        } else {
            // Cancelada, nada se guarda: lo escrito sigue para intentarlo otra vez.
            tested = null
            state.update { it.copy(phase = WebDavPhase.EDITING, steps = emptyList()) }
        }
    }

    private fun save(tested: TestedWebDav) {
        val current = state.value
        save(tested, WebDavLogin(current.url.trim(), current.username.trim(), current.password))
    }

    private fun save(candidate: TestedWebDav, login: WebDavLogin) {
        state.update { it.copy(phase = WebDavPhase.SAVING, changeFrom = null) }
        viewModelScope.launch {
            val saved = read { port.save(login, candidate) } ?: WebDavSaveResult.SaveFailed
            tested = null
            when (saved) {
                is WebDavSaveResult.Connected ->
                    state.update {
                        it.copy(
                            phase = WebDavPhase.EDITING,
                            password = "",
                            connected = WebDavConnectUiState.Connected(saved.hasCopies),
                        )
                    }

                WebDavSaveResult.SaveFailed ->
                    state.update {
                        it.copy(
                            phase = WebDavPhase.EDITING,
                            steps = emptyList(),
                            notice = WebDavNotice.SaveFailed,
                        )
                    }
            }
        }
    }

    private fun cancel() {
        if (state.value.phase != WebDavPhase.TESTING) return
        testing?.cancel()
        testing = null
        state.update { it.copy(phase = WebDavPhase.EDITING, steps = emptyList()) }
    }

    private suspend fun <T> read(block: suspend () -> T): T? = try {
        withContext(computation) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun WebDavConnectUiState.withSuggestion(): WebDavConnectUiState =
        copy(suggestion = suggestions.firstOrNull { it.url == url.trim() })

    private companion object {
        const val URL_KEY = "url"
        const val USER_KEY = "username"
    }
}
