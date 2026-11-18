package io.github.yesidrobayo.shopledger.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.update.ApkDownloadResult
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.ui.help.HelpBlock
import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * `Actualizaciones y verificar APK`: muestra la versión y la huella de la app instalada, busca una
 * versión nueva solo cuando la persona toca el botón, y compara un archivo elegido con la app.
 * Nada de esto bloquea la app ni los datos, y nada se instala sin que Android pida confirmarlo.
 *
 * - **La búsqueda** solo corre con `Permitir buscar actualizaciones` encendido, una a la vez. Sin
 *   red o con un aviso que no se pudo leer o comprobar, lo dice y se puede reintentar. Con
 *   [searchOnOpen], desde el aviso de una versión nueva, busca sola al saber que está permitida.
 * - **`Buscar actualizaciones automáticamente`** enciende o apaga la búsqueda de una vez al día, que
 *   solo corre con la búsqueda permitida.
 * - **Un archivo a la vez:** mientras se comprueba uno, elegir otro no hace nada. Si la búsqueda
 *   de esta visita leyó un aviso, dice también si el SHA-256 del archivo coincide con el suyo.
 * - Una falla inesperada en cualquiera de las dos dice que no se pudo leer, sin cerrar la
 *   pantalla.
 * - **`Actualizar`** solo existe tras una búsqueda de esta visita que encontró una versión nueva,
 *   y solo corre al tocarlo. Baja el APK de esa versión con su progreso, y se puede cancelar.
 *   Antes de entregarlo al instalador exige, en este orden: el SHA-256 exacto del aviso firmado,
 *   la firma de la app instalada en el mismo paquete, y una versión más nueva que la instalada.
 *   Cualquier falla borra el archivo y dice cuál fue; nunca se instala nada sin pasar todo. Si
 *   Android todavía no permite instalar desde la app, se explica y se sigue al volver con el
 *   permiso. Android muestra su propia confirmación.
 * - El archivo bajado se borra al fallar, al cancelar, al apagar la búsqueda o al salir de la
 *   pantalla sin entregarlo, pero nunca mientras el instalador lo tiene.
 * - **`Novedades`** se lee de los recursos de la app, sin red.
 *
 * @param installedApp la versión y la huella de la app instalada, o nulo si no se pudieron leer.
 * @param verifyApk compara el archivo de una dirección del selector con la app instalada.
 * @param searchEnabled si la búsqueda está permitida, de nuevo cada vez que cambia.
 * @param setSearchEnabled guarda el interruptor; `false` si no quedó guardado.
 * @param automatic si la búsqueda automática está encendida.
 * @param setAutomatic guarda el interruptor de la automática y la programa o la cancela; `false` si
 *   no quedó guardado.
 * @param searchOnOpen si se abrió desde el aviso de una versión nueva: busca sola una vez.
 * @param checkForUpdate lee el aviso de versión y lo compara con la app instalada.
 * @param lastSearchAt la última búsqueda en este teléfono, o nula si nunca.
 * @param zone la zona del negocio, o nula si todavía no se eligió: entonces las fechas van en UTC.
 * @param now el reloj del teléfono.
 * @param downloadApk baja el APK de la versión con ese nombre, con lo bajado y el total declarado.
 * @param checkDownloadedApk compara el archivo bajado, con esa huella, con la app instalada.
 * @param canInstallPackages si Android ya permite que la app instale actualizaciones.
 * @param discardDownload borra el archivo bajado y sus restos.
 * @param leaveScope donde se borra el archivo al salir de la pantalla, cuando la pantalla ya no
 *   corre nada.
 * @param whatsNew el texto de `Novedades`, o nulo si no se pudo leer.
 */
class UpdatesViewModel(
    private val installedApp: suspend () -> InstalledApp?,
    private val verifyApk: suspend (String) -> ApkCheckResult,
    searchEnabled: Flow<Boolean>,
    private val setSearchEnabled: suspend (Boolean) -> Boolean,
    private val automatic: suspend () -> Boolean = { true },
    private val setAutomatic: suspend (Boolean) -> Boolean = { true },
    private var searchOnOpen: Boolean = false,
    private val checkForUpdate: suspend () -> UpdateCheckOutcome,
    private val lastSearchAt: suspend () -> Instant?,
    zone: Flow<BusinessTimeZone?>,
    private val now: () -> Instant,
    private val downloadApk:
    suspend (versionName: String, onProgress: (Long, Long?) -> Unit) -> ApkDownloadResult,
    private val checkDownloadedApk: suspend (sha256: String) -> ApkCheckResult,
    private val canInstallPackages: () -> Boolean,
    private val discardDownload: suspend () -> Unit,
    private val leaveScope: CoroutineScope,
    private val whatsNew: suspend () -> List<HelpBlock>?,
) : ViewModel() {
    private val state = MutableStateFlow(UpdatesUiState(now = now()))

    /** La huella del APK del último aviso leído en esta visita, o nula si no se leyó ninguno. */
    private var manifestSha256: String? = null

    /** La descarga y la comprobación en curso, o su limpieza tras cancelar. */
    private var installJob: Job? = null

    /**
     * Cuenta cada descarga: el progreso de una que se canceló, que todavía puede llegar desde su
     * hilo, no pisa el de la siguiente. Ese hilo la lee: por eso es volátil.
     */
    @Volatile
    private var installRound = 0

    val uiState: StateFlow<UpdatesUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val read = attempt(null) { installedApp() }
            state.update { it.copy(installedLoading = false, installed = read) }
        }
        viewModelScope.launch {
            val last = attempt(null) { lastSearchAt() }
            state.update { it.copy(lastSearchAt = last) }
        }
        viewModelScope.launch {
            searchEnabled
                .map<Boolean, Boolean?> { it }
                .catch { emit(null) }
                .collect { enabled ->
                    state.update { it.copy(searchEnabled = enabled) }
                    // Desde el aviso, tocarlo es pedir la búsqueda: corre una sola vez.
                    if (enabled == true && searchOnOpen) {
                        searchOnOpen = false
                        search()
                    }
                }
        }
        viewModelScope.launch {
            val read = attempt<Boolean?>(null) { automatic() }
            state.update { it.copy(automatic = read) }
        }
        viewModelScope.launch {
            zone
                .catch { emit(null) }
                .collect { read ->
                    state.update { it.copy(zone = read ?: UpdatesUiState.UTC) }
                }
        }
    }

    fun onAction(action: UpdatesAction) {
        when (action) {
            UpdatesAction.Search -> search()

            is UpdatesAction.SetSearchEnabled -> saveSearchEnabled(action.enabled)

            is UpdatesAction.SetAutomatic -> saveAutomatic(action.enabled)

            UpdatesAction.MessageShown -> state.update { it.copy(saveFailed = false) }

            is UpdatesAction.FileChosen -> action.uri?.let(::verify)

            UpdatesAction.StartUpdate -> startUpdate()

            UpdatesAction.CancelUpdate -> cancelUpdate()

            UpdatesAction.Resumed -> continueIfAllowed(InstallUi.NeedsPermission)

            UpdatesAction.OpenInstallerAgain -> continueIfAllowed(InstallUi.HandedToInstaller)

            is UpdatesAction.InstallerOpened -> installerOpened(action.opened)

            UpdatesAction.OpenWhatsNew -> openWhatsNew()

            UpdatesAction.CloseWhatsNew -> state.update { it.copy(whatsNew = WhatsNewUi.Closed) }

            // Las resuelven la pantalla y la navegación.
            UpdatesAction.Back,
            UpdatesAction.ChooseFile,
            UpdatesAction.AllowInstalls,
            -> Unit
        }
    }

    override fun onCleared() {
        // Salir sin entregarlo al instalador no deja el archivo. Con el instalador abierto se
        // queda: lo borra la próxima vez que se abre la app.
        if (state.value.install != InstallUi.HandedToInstaller) {
            installJob?.cancel()
            leaveScope.launch { attempt(Unit) { discardDownload() } }
        }
        super.onCleared()
    }

    private fun search() {
        val current = state.value
        if (current.searchEnabled != true || current.search == SearchUi.Searching) return
        // Mientras se baja o se compara el archivo, el resultado que lo pidió sigue a la vista.
        if (current.install.isBusy()) return
        resetInstall()
        state.update { it.copy(search = SearchUi.Searching) }
        viewModelScope.launch {
            val outcome = attempt(UpdateCheckOutcome.Unreadable) { checkForUpdate() }
            when (outcome) {
                is UpdateCheckOutcome.Latest -> manifestSha256 = outcome.apkSha256
                is UpdateCheckOutcome.NewVersion -> manifestSha256 = outcome.apkSha256
                else -> Unit
            }
            val last = attempt(null) { lastSearchAt() }
            state.update {
                it.copy(
                    search = SearchUi.Done(outcome),
                    lastSearchAt = last ?: it.lastSearchAt,
                    now = now(),
                )
            }
        }
    }

    private fun saveSearchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val saved = attempt(false) { setSearchEnabled(enabled) }
            // Apagada, la búsqueda tampoco deja un archivo a medio bajar.
            if (saved && !enabled) resetInstall()
            state.update {
                when {
                    !saved -> it.copy(saveFailed = true)

                    // Apagada, la búsqueda no deja nada a la vista: ni su resultado.
                    !enabled -> it.copy(search = SearchUi.Idle)

                    else -> it
                }
            }
        }
    }

    private fun saveAutomatic(enabled: Boolean) {
        val before = state.value.automatic
        state.update { it.copy(automatic = enabled) }
        viewModelScope.launch {
            val saved = attempt(false) { setAutomatic(enabled) }
            // Si no se guardó, el interruptor vuelve a lo de antes y la pantalla lo dice.
            if (!saved) state.update { it.copy(automatic = before, saveFailed = true) }
        }
    }

    private fun verify(uri: String) {
        if (state.value.verify == VerifyUi.Checking) return
        state.update { it.copy(verify = VerifyUi.Checking) }
        viewModelScope.launch {
            val result = attempt<ApkCheckResult>(ApkCheckResult.ReadFailed) { verifyApk(uri) }
            val expected = manifestSha256
            val matches =
                result is ApkCheckResult.Match &&
                    expected != null &&
                    result.sha256.equals(expected, ignoreCase = true)
            state.update { it.copy(verify = VerifyUi.Done(result, manifestMatch = matches)) }
        }
    }

    private fun startUpdate() {
        val current = state.value
        val announced =
            (current.search as? SearchUi.Done)?.outcome as? UpdateCheckOutcome.NewVersion ?: return
        if (current.install.isBusy() || current.install == InstallUi.HandedToInstaller) return
        val round = ++installRound
        val previous = installJob
        state.update { it.copy(install = InstallUi.Downloading(0, null)) }
        installJob =
            viewModelScope.launch {
                // Una limpieza anterior termina antes: nunca borra el archivo de esta descarga.
                previous?.cancelAndJoin()
                val next = prepare(announced, round)
                state.update { it.copy(install = next) }
            }
    }

    /** Baja y compara el APK de [announced], y dice si va al instalador o por qué no. */
    private suspend fun prepare(announced: UpdateCheckOutcome.NewVersion, round: Int): InstallUi {
        val download =
            attempt<ApkDownloadResult>(ApkDownloadResult.Failed) {
                downloadApk(announced.versionName) { done, total ->
                    state.update {
                        if (round == installRound && it.install is InstallUi.Downloading) {
                            it.copy(install = InstallUi.Downloading(done, total))
                        } else {
                            it
                        }
                    }
                }
            }
        val downloaded =
            when (download) {
                is ApkDownloadResult.Downloaded -> download

                ApkDownloadResult.Offline -> return failed(InstallFailure.NO_CONNECTION)

                ApkDownloadResult.TooLarge -> return failed(InstallFailure.TOO_LARGE)

                ApkDownloadResult.Failed,
                ApkDownloadResult.Refused,
                -> return failed(InstallFailure.DOWNLOAD_FAILED)
            }
        // La huella va primero, y exacta: el aviso firmado la guarda en minúsculas.
        if (downloaded.sha256 != announced.apkSha256) return failed(InstallFailure.HASH_MISMATCH)
        state.update { it.copy(install = InstallUi.Verifying) }
        val check =
            attempt<ApkCheckResult>(ApkCheckResult.ReadFailed) {
                checkDownloadedApk(downloaded.sha256)
            }
        val installedCode = state.value.installed?.versionCode
        return when (check) {
            is ApkCheckResult.Match -> when {
                installedCode == null || check.versionCode <= installedCode ->
                    failed(InstallFailure.NOT_NEWER)

                allowedToInstall() -> InstallUi.Opening

                else -> InstallUi.NeedsPermission
            }

            is ApkCheckResult.Mismatch -> failed(InstallFailure.WRONG_SIGNATURE)

            ApkCheckResult.NotApk,
            ApkCheckResult.ReadFailed,
            -> failed(InstallFailure.DOWNLOAD_FAILED)
        }
    }

    /** Borra el archivo y da la falla [reason]. */
    private suspend fun failed(reason: InstallFailure): InstallUi {
        attempt(Unit) { discardDownload() }
        return InstallUi.Failed(reason)
    }

    private fun cancelUpdate() {
        if (state.value.install !is InstallUi.Downloading) return
        val running = installJob
        installRound++
        state.update { it.copy(install = InstallUi.Idle) }
        installJob =
            viewModelScope.launch {
                running?.cancelAndJoin()
                attempt(Unit) { discardDownload() }
            }
    }

    /**
     * Desde [from], abre el instalador si Android ya lo permite, o pide el permiso. Al volver del
     * ajuste sin permitirlo, todo sigue igual.
     */
    private fun continueIfAllowed(from: InstallUi) {
        if (state.value.install != from) return
        val next =
            when {
                allowedToInstall() -> InstallUi.Opening
                from == InstallUi.NeedsPermission -> return
                else -> InstallUi.NeedsPermission
            }
        state.update { it.copy(install = next) }
    }

    private fun installerOpened(opened: Boolean) {
        if (state.value.install != InstallUi.Opening) return
        if (opened) {
            state.update { it.copy(install = InstallUi.HandedToInstaller) }
            return
        }
        installJob =
            viewModelScope.launch {
                state.update { it.copy(install = failed(InstallFailure.NO_INSTALLER)) }
            }
    }

    /**
     * Deja `Actualizar` como al principio, tras una búsqueda nueva o al apagarla, y borra lo que
     * se haya bajado. Con el instalador abierto, el archivo se queda.
     */
    private fun resetInstall() {
        val install = state.value.install
        if (install == InstallUi.Idle) return
        state.update { it.copy(install = InstallUi.Idle) }
        if (install == InstallUi.HandedToInstaller) return
        val running = installJob
        installRound++
        installJob =
            viewModelScope.launch {
                running?.cancelAndJoin()
                attempt(Unit) { discardDownload() }
            }
    }

    private fun allowedToInstall(): Boolean = try {
        canInstallPackages()
    } catch (_: RuntimeException) {
        false
    }

    private fun openWhatsNew() {
        if (state.value.whatsNew != WhatsNewUi.Closed) return
        state.update { it.copy(whatsNew = WhatsNewUi.Loading) }
        viewModelScope.launch {
            val blocks = attempt(null) { whatsNew() }
            state.update {
                // Si se cerró mientras se leía, sigue cerrada.
                if (it.whatsNew != WhatsNewUi.Loading) {
                    it
                } else {
                    it.copy(whatsNew = blocks?.let(WhatsNewUi::Ready) ?: WhatsNewUi.Failed)
                }
            }
        }
    }

    /** [block], o [fallback] si falla. Una cancelación sigue su curso. */
    private suspend fun <T> attempt(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback
    }
}

/** Si `Actualizar` está trabajando: no atiende otro toque ni deja buscar. */
private fun InstallUi.isBusy(): Boolean = when (this) {
    is InstallUi.Downloading,
    InstallUi.Verifying,
    InstallUi.Opening,
    -> true

    InstallUi.Idle,
    InstallUi.NeedsPermission,
    InstallUi.HandedToInstaller,
    is InstallUi.Failed,
    -> false
}
