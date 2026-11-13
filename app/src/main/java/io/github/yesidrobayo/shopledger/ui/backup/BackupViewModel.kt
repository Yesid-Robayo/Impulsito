package io.github.yesidrobayo.shopledger.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictLookup
import io.github.yesidrobayo.shopledger.domain.model.backup.DisconnectResult
import io.github.yesidrobayo.shopledger.domain.model.backup.DrillOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.FullVerifyOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthRow
import io.github.yesidrobayo.shopledger.domain.model.backup.KeepPhoneResult
import io.github.yesidrobayo.shopledger.domain.model.backup.PinRelease
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.rule.backup.DataHealthRule
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupLinkUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ReadDataHealthUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RunFullVerifyUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RunRestoreDrillUseCase
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La pantalla del respaldo: el estado con sus hechos, el destino, sus copias y `Respaldar ahora`.
 *
 * - El estado sale del disco y del teléfono, sin red, y se recalcula al volver a primer plano.
 * - El destino y la lista se vuelven a leer cuando cambia el destino o llega una copia nueva: así
 *   la lista que se ve es la de después de la copia.
 * - Leer nunca escribe: la lista no sube, poda ni fija nada.
 *
 * @param overview el estado; recibe las veces que la pantalla vuelve a primer plano.
 * @param destination el destino conectado y sus copias.
 * @param calendar la zona del negocio, para decir `ayer, 21:14`.
 * @param requestBackup pide la copia con `Respaldar ahora`.
 * @param recordCheck anota `Ya la vi en {provider}` con la generación en pantalla; devuelve si quedó.
 * @param computation el hilo donde se leen el destino y la lista, fuera del principal.
 * @param restore restaurar una copia de la lista o un archivo elegido; nulo donde no se ofrece.
 * @param changePolicy guarda la política con datos móviles y vuelve a programar la subida.
 * @param releaseCopy quita la marca de fijada a una copia, por su clave en la lista.
 * @param uploadedThisMonth con WebDAV, los bytes subidos en el mes; nulo si no se pudo leer.
 * @param copiesEncrypted si las copias salen cifradas; nulo si no se pudo leer.
 * @param link desconectar, volver tras iniciar sesión y decidir un conflicto; nulo donde no se
 *   ofrece.
 * @param drill `Probar restauración`; nulo donde no se ofrece.
 * @param health las filas de `Salud de mis datos`, solo de lectura; nulo donde no se ofrece.
 * @param fullVerify `Comprobar ahora`, la única escritura del panel; nulo donde no se ofrece.
 */
class BackupViewModel(
    overview: (Flow<Unit>) -> Flow<BackupOverview>,
    private val destination: BackupDestinationReader,
    calendar: Flow<BusinessCalendarResult>,
    private val requestBackup: suspend () -> Unit,
    private val recordCheck: suspend (Long) -> Boolean,
    private val computation: CoroutineDispatcher,
    private val restore: RestoreBackupUseCase? = null,
    private val changePolicy: suspend (MeteredBackupPolicy) -> Unit = {},
    private val releaseCopy: suspend (String) -> PinRelease = { PinRelease.FAILED },
    private val uploadedThisMonth: suspend () -> Long? = { null },
    private val copiesEncrypted: suspend () -> Boolean? = { null },
    private val link: BackupLinkUseCase? = null,
    private val drill: RunRestoreDrillUseCase? = null,
    private val health: ReadDataHealthUseCase? = null,
    private val fullVerify: RunFullVerifyUseCase? = null,
) : ViewModel() {
    private val state = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = state.asStateFlow()

    // Cada vuelta a primer plano recalcula el estado con la hora nueva.
    private val refreshes = MutableStateFlow(0L)
    private var listing: Job? = null
    private val restoring: RestoreFlow? =
        restore?.let { use ->
            RestoreFlow(
                use = use,
                scope = viewModelScope,
                computation = computation,
                get = { state.value.restore },
                set = { next -> state.update { it.copy(restore = next) } },
                needsNetwork = { state.value.destination is BackupDestinationInfo.WebDav },
                hasNetwork = destination::hasNetwork,
            )
        }

    /** El estado del respaldo y la zona: una falla se dice con `Reintentar`, sin cerrar nada. */
    private val reads = ScreenReads()

    /** El estado del respaldo, que se vuelve a calcular con cada refresco, junto a la zona del negocio. */
    private val status =
        combine(
            overview(refreshes.map {}),
            calendar.map { (it as? BusinessCalendarResult.Ready)?.calendar?.zone },
        ) { current, zone -> current to zone }

    init {
        observeStatus()
        viewModelScope.launch {
            reads.failed.collect { failed -> state.update { it.copy(statusReadFailed = failed) } }
        }
        // El destino y la lista se leen de nuevo con otro destino o con una copia nueva.
        viewModelScope.launch {
            uiState
                .map { it.wording?.overview?.let { o -> o.destinationKind to o.lastSuccessAt } }
                .distinctUntilChanged()
                .collect { if (it != null) reload() }
        }
    }

    /**
     * Lee el estado del respaldo y la zona del negocio. Una falla de lectura no cierra la pantalla:
     * el bloque del estado la dice, con `Reintentar`.
     */
    private fun observeStatus() {
        viewModelScope.launch {
            reads.guard { status }.collect { (current, zone) ->
                state.update {
                    val slots = it.destination as? BackupDestinationInfo.Slots
                    val provider = slots?.providerLabel
                    val session = slots?.sessionRequired == true
                    it.copy(wording = BackupWording(current, provider, zone, session))
                }
                refreshHealth()
            }
        }
    }

    fun onAction(action: BackupAction) {
        when (action) {
            BackupAction.BackUpNow -> backUpNow()

            BackupAction.RetryCopies -> reload()

            BackupAction.RetryStatus -> reads.retry()

            BackupAction.MessageShown -> state.update { it.copy(message = null) }

            BackupAction.MarkSeen -> markSeen()

            BackupAction.ShowMissingHelp -> state.update { it.copy(missingHelp = true) }

            BackupAction.CloseMissingHelp -> state.update { it.copy(missingHelp = false) }

            is BackupAction.OpenCopy -> copyOf(action.key)?.let { restoring?.open(it) }

            is BackupAction.ConfirmCopy -> copyOf(action.key)?.let { restoring?.confirm(it) }

            is BackupAction.FilePicked -> restoring?.picked(action.uri)

            BackupAction.StartRestore -> restoring?.start()

            is BackupAction.Answer -> restoring?.answer(action.answer)

            BackupAction.CancelRestore -> restoring?.cancel()

            is BackupAction.RestoreCode -> restoring?.code(action.event)

            BackupAction.CloseRestore -> state.update { it.copy(restore = RestoreUi.Idle) }

            BackupAction.OpenMeteredPolicy -> openMetered()

            is BackupAction.PickMeteredPolicy -> pickPolicy(action.policy)

            BackupAction.ConfirmWifiOnly -> {
                state.update { it.copy(metered = MeteredUi.Closed) }
                applyPolicy(MeteredBackupPolicy.WIFI_ONLY)
            }

            BackupAction.CloseMeteredPolicy -> state.update { it.copy(metered = MeteredUi.Closed) }

            is BackupAction.ReleaseCopy -> askRelease(action.key)

            BackupAction.ConfirmRelease -> release()

            BackupAction.CloseRelease ->
                state.update {
                    if (it.release is ReleaseUi.Working) it else it.copy(release = ReleaseUi.Idle)
                }

            BackupAction.UndoUpdate ->
                state.value.beforeUpdate?.let { restoring?.confirmBeforeUpdate(it) }

            BackupAction.Disconnect ->
                if (link != null) state.update { it.copy(disconnect = DisconnectUi.ASKING) }

            BackupAction.ConfirmDisconnect -> disconnect()

            BackupAction.DismissDisconnect ->
                state.update {
                    if (it.disconnect == DisconnectUi.ASKING) {
                        it.copy(disconnect = DisconnectUi.HIDDEN)
                    } else {
                        it
                    }
                }

            BackupAction.ReviewConflict, BackupAction.RetryConflict -> describeConflict()

            // Decidir después no cambia nada: el aviso sigue a la vista.
            BackupAction.DecideLater -> state.update { it.copy(conflict = null) }

            is BackupAction.RestoreCloud -> {
                state.update { it.copy(conflict = null) }
                (action.key?.let(::copyOf) ?: newestRestorable())?.let { restoring?.open(it) }
            }

            BackupAction.KeepPhone -> askKeep()

            BackupAction.ConfirmKeep -> keep()

            BackupAction.DismissKeep ->
                state.update { it.copy(conflict = it.conflict?.copy(confirm = null)) }

            BackupAction.CreateConflictFile -> state.update { it.copy(conflict = null) }

            BackupAction.RunDrill -> runDrill()

            BackupAction.CheckFullCopy -> checkFullCopy()

            BackupAction.ToggleHealth ->
                state.update { current ->
                    val open = current.healthOpen ?: !healthFolded(current.health)
                    current.copy(healthOpen = !open)
                }

            // Lo resuelve la navegación.
            BackupAction.Back,
            BackupAction.ChooseDestination,
            BackupAction.ReviewPolicy,
            BackupAction.OpenProvider,
            BackupAction.FindFile,
            BackupAction.OpenLocalCopies,
            BackupAction.OpenEncryption,
            BackupAction.Reconnect,
            BackupAction.SignInToProvider,
            -> Unit
        }
    }

    /**
     * La pantalla volvió a primer plano: el estado se calcula con la hora de ahora. Con la sesión
     * de la nube del teléfono cerrada, comprueba sus archivos: si la persona inició sesión, las
     * copias siguen solas.
     */
    fun onReturnToForeground() {
        refreshes.value += 1
        refreshUsage()
        val slots = state.value.destination as? BackupDestinationInfo.Slots
        if (link != null && slots?.sessionRequired == true) {
            viewModelScope.launch {
                if (read { link.resumeAfterSignIn() } == true) reload()
            }
        }
    }

    /**
     * Vuelve a leer `Salud de mis datos` con el estado y la lista de ahora. Solo lee: el panel no
     * escribe nada.
     */
    private fun refreshHealth() {
        val use = health ?: return
        val overview = state.value.wording?.overview ?: return
        val pinned =
            (state.value.copies as? BackupCopyList.Listed)?.copies?.count { it.pinned }
        healthJob?.cancel()
        healthJob =
            viewModelScope.launch {
                val rows = read { use(overview, pinned) } ?: return@launch
                state.update { it.copy(health = rows) }
            }
    }

    private var healthJob: Job? = null

    /**
     * `Comprobar ahora`: solo corre si la persona lo toca, porque baja la copia entera. Al terminar
     * se vuelve a leer el estado y el panel, que traen lo anotado.
     */
    private fun checkFullCopy() {
        val use = fullVerify ?: return
        if (state.value.verifying) return
        state.update { it.copy(verifying = true) }
        viewModelScope.launch {
            val outcome = read { use() } ?: FullVerifyOutcome.UNREACHABLE
            val message =
                when (outcome) {
                    FullVerifyOutcome.MATCHED -> BackupMessage.VERIFY_MATCHED

                    FullVerifyOutcome.MISMATCH -> BackupMessage.VERIFY_MISMATCH

                    FullVerifyOutcome.NO_SPACE -> BackupMessage.VERIFY_NO_SPACE

                    FullVerifyOutcome.NO_COPY -> BackupMessage.DRILL_NO_COPY

                    FullVerifyOutcome.UNREACHABLE ->
                        if (destination.hasNetwork()) {
                            BackupMessage.CONFLICT_UNREACHABLE
                        } else {
                            BackupMessage.CONFLICT_NO_INTERNET
                        }
                }
            state.update { it.copy(verifying = false, message = message) }
            refreshes.value += 1
        }
    }

    /**
     * `Probar restauración`: corre el simulacro y dice cómo terminó. Nunca cambia los datos; al
     * terminar se vuelve a leer el estado, que trae el resultado anotado.
     */
    private fun runDrill() {
        val use = drill ?: return
        if (state.value.drilling) return
        state.update { it.copy(drilling = true) }
        viewModelScope.launch {
            val outcome = read { use() } ?: DrillOutcome.UNREACHABLE
            val message =
                when (outcome) {
                    DrillOutcome.PASSED -> BackupMessage.DRILL_PASSED

                    DrillOutcome.FAILED -> BackupMessage.DRILL_FAILED

                    DrillOutcome.SKIPPED -> BackupMessage.DRILL_SKIPPED

                    DrillOutcome.NO_COPY -> BackupMessage.DRILL_NO_COPY

                    DrillOutcome.UNREACHABLE ->
                        if (destination.hasNetwork()) {
                            BackupMessage.CONFLICT_UNREACHABLE
                        } else {
                            BackupMessage.CONFLICT_NO_INTERNET
                        }
                }
            state.update { it.copy(drilling = false, message = message) }
            refreshes.value += 1
        }
    }

    private fun disconnect() {
        val port = link ?: return
        if (state.value.disconnect != DisconnectUi.ASKING) return
        state.update { it.copy(disconnect = DisconnectUi.WORKING) }
        viewModelScope.launch {
            val result = read { port.disconnect() } ?: DisconnectResult.FAILED
            val message =
                when (result) {
                    DisconnectResult.DISCONNECTED, DisconnectResult.NOT_CONNECTED ->
                        BackupMessage.DISCONNECTED

                    DisconnectResult.FAILED -> BackupMessage.DISCONNECT_FAILED
                }
            state.update { it.copy(disconnect = DisconnectUi.HIDDEN, message = message) }
            reload()
        }
    }

    /** Abre la hoja y lee los dos lados. Con WebDAV lee el destino, así que puede tardar. */
    private fun describeConflict() {
        val port = link ?: return
        if (state.value.conflict?.working == true) return
        state.update { it.copy(conflict = ConflictUi()) }
        viewModelScope.launch {
            val lookup = read { port.describeConflict() } ?: ConflictLookup.Unreachable
            when (lookup) {
                // Ya no hay nada que decidir: la hoja no se abre.
                ConflictLookup.NotInConflict -> state.update { it.copy(conflict = null) }

                is ConflictLookup.Shown,
                ConflictLookup.EmptyDatabase,
                is ConflictLookup.NewSlotFileRequired,
                ConflictLookup.Unreachable,
                ->
                    state.update { current ->
                        val open = current.conflict ?: return@update current
                        val offline =
                            lookup == ConflictLookup.Unreachable && !destination.hasNetwork()
                        current.copy(conflict = open.copy(lookup = lookup, offline = offline))
                    }
            }
        }
    }

    /** `Conservar este teléfono`: primero se confirma, con los registros de cada lado. */
    private fun askKeep() {
        val conflict = state.value.conflict ?: return
        val confirm =
            when (val lookup = conflict.lookup) {
                is ConflictLookup.Shown ->
                    KeepConfirm.Sides(lookup.sides, reinforced = lookup.sides.localHasFewer)

                is ConflictLookup.NewSlotFileRequired -> KeepConfirm.NewFile(lookup)

                // Sin los dos lados, o con la base vacía, no se ofrece.
                ConflictLookup.NotInConflict,
                ConflictLookup.EmptyDatabase,
                ConflictLookup.Unreachable,
                null,
                -> return
            }
        state.update { it.copy(conflict = conflict.copy(confirm = confirm)) }
    }

    private fun keep() {
        val port = link ?: return
        val conflict = state.value.conflict ?: return
        // El archivo nuevo lo resuelve la navegación: aquí solo se confirma con los dos lados.
        val confirm = conflict.confirm as? KeepConfirm.Sides ?: return
        if (conflict.working) return
        state.update { it.copy(conflict = conflict.copy(working = true)) }
        viewModelScope.launch {
            val result =
                read { port.keepThisPhone(fewerRecordsConfirmed = confirm.reinforced) }
                    ?: KeepPhoneResult.Unreachable
            onKept(result)
        }
    }

    private fun onKept(result: KeepPhoneResult) {
        state.update { current ->
            val conflict = current.conflict?.copy(working = false, confirm = null)
            when (result) {
                KeepPhoneResult.Kept, KeepPhoneResult.NotInConflict -> current.copy(conflict = null)

                // Este teléfono tiene menos: la confirmación reforzada, con los números nuevos.
                is KeepPhoneResult.NeedsConfirmation ->
                    current.copy(
                        conflict =
                            conflict?.copy(
                                lookup = ConflictLookup.Shown(result.sides),
                                confirm = KeepConfirm.Sides(result.sides, reinforced = true),
                            ),
                    )

                // La nube cambió: la persona decide otra vez con lo nuevo a la vista.
                is KeepPhoneResult.CloudChanged ->
                    current.copy(
                        conflict = conflict?.copy(lookup = ConflictLookup.Shown(result.sides)),
                    )

                is KeepPhoneResult.NewSlotFileRequired -> current.copy(conflict = conflict)

                KeepPhoneResult.TooManyPinned ->
                    current.copy(conflict = conflict, message = BackupMessage.PINNED_MAX)

                KeepPhoneResult.Unreachable -> {
                    val message =
                        if (destination.hasNetwork()) {
                            BackupMessage.CONFLICT_UNREACHABLE
                        } else {
                            BackupMessage.CONFLICT_NO_INTERNET
                        }
                    current.copy(conflict = conflict, message = message)
                }

                KeepPhoneResult.EmptyDatabase ->
                    current.copy(conflict = conflict?.copy(lookup = ConflictLookup.EmptyDatabase))
            }
        }
        // Con un archivo nuevo de por medio, se vuelve a leer qué ranura lo necesita.
        if (result is KeepPhoneResult.NewSlotFileRequired) describeConflict()
    }

    private fun webDav(): Boolean =
        state.value.wording?.overview?.destinationKind == BackupDestinationKind.WEBDAV

    /** Con ranuras, la red la decide la app de esa nube: la hoja no se abre. */
    private fun openMetered() {
        if (webDav()) state.update { it.copy(metered = MeteredUi.Choosing) }
    }

    /**
     * Elegir `Solo con Wi-Fi` pide confirmar con su advertencia, salvo si ya era la elegida; las
     * otras dos se guardan enseguida.
     */
    private fun pickPolicy(policy: MeteredBackupPolicy) {
        val current = state.value.wording?.overview?.meteredPolicy
        if (policy == MeteredBackupPolicy.WIFI_ONLY && current != MeteredBackupPolicy.WIFI_ONLY) {
            state.update { it.copy(metered = MeteredUi.ConfirmWifiOnly) }
            return
        }
        state.update { it.copy(metered = MeteredUi.Closed) }
        if (policy != current) applyPolicy(policy)
    }

    private fun applyPolicy(policy: MeteredBackupPolicy) {
        viewModelScope.launch {
            // Si no se pudo guardar, la fila vuelve sola a la política guardada, y se dice.
            if (read { changePolicy(policy) } == null) {
                state.update { it.copy(message = BackupMessage.SAVE_FAILED) }
            }
            // El estado y los límites cambian con la política: se calculan de nuevo.
            refreshes.value += 1
        }
    }

    /**
     * `Liberar copia` solo existe para una copia fijada de la lista. Reintentar tras un fallo vuelve
     * a preguntar por la misma copia.
     */
    private fun askRelease(key: String) {
        val failed = (state.value.release as? ReleaseUi.Failed)?.copy?.takeIf { it.key == key }
        val copy = failed ?: copyOf(key)?.takeIf { it.pinned && webDav() } ?: return
        state.update { it.copy(restore = RestoreUi.Idle, release = ReleaseUi.Confirm(copy)) }
    }

    /** Libera la copia confirmada y vuelve a leer la lista, para ver la copia sin la marca. */
    private fun release() {
        val copy = (state.value.release as? ReleaseUi.Confirm)?.copy ?: return
        state.update { it.copy(release = ReleaseUi.Working(copy)) }
        viewModelScope.launch {
            val result = read { releaseCopy(copy.key) } ?: PinRelease.FAILED
            state.update {
                when (result) {
                    PinRelease.RELEASED ->
                        it.copy(release = ReleaseUi.Idle, message = BackupMessage.RELEASED)

                    PinRelease.FAILED ->
                        it.copy(
                            release = ReleaseUi.Failed(copy),
                            message = BackupMessage.RELEASE_FAILED,
                        )
                }
            }
            if (result == PinRelease.RELEASED) reload()
        }
    }

    /** Con WebDAV, lo subido este mes; con ranuras no aparece, porque sube otra app. */
    private fun refreshUsage() {
        if (!webDav()) return
        viewModelScope.launch {
            val bytes = read { uploadedThisMonth() }
            state.update { it.copy(uploadedThisMonth = bytes) }
        }
    }

    private fun reload() {
        listing?.cancel()
        state.update { it.copy(copies = null) }
        listing = viewModelScope.launch {
            // Sin destino, o si no se pudo leer, no hay nombre que mostrar.
            val info = read { destination.describe() }
            val copies = read { destination.listCopies() } ?: BackupCopyList.Failed
            val handoff = read { destination.latestHandoff() }
            val usage = if (info is BackupDestinationInfo.WebDav) {
                read {
                    uploadedThisMonth()
                }
            } else {
                null
            }
            val encrypted = if (info != null) read { copiesEncrypted() } else null
            // La copia previa a migrar es local: se ofrece con o sin destino.
            val beforeUpdate = restore?.let { use -> read { use.beforeUpdateCopy() } }
            state.update { current ->
                val slots = info as? BackupDestinationInfo.Slots
                current.copy(
                    destination = info,
                    copies = copies,
                    handoff = handoff,
                    uploadedThisMonth = usage,
                    copiesEncrypted = encrypted,
                    beforeUpdate = beforeUpdate,
                    wording =
                        current.wording?.copy(
                            providerLabel = slots?.providerLabel,
                            sessionRequired = slots?.sessionRequired == true,
                        ),
                )
            }
            refreshHealth()
        }
    }

    private fun backUpNow() {
        if (state.value.requesting) return
        state.update { it.copy(requesting = true) }
        viewModelScope.launch {
            try {
                if (read { requestBackup() } == null) {
                    state.update { it.copy(message = BackupMessage.REQUEST_FAILED) }
                    return@launch
                }
                // Con WebDAV, sin red la copia queda encolada: se dice, para que nadie espere.
                val webDav =
                    state.value.wording?.overview?.destinationKind == BackupDestinationKind.WEBDAV
                if (webDav && !destination.hasNetwork()) {
                    state.update { it.copy(message = BackupMessage.NO_INTERNET) }
                }
            } finally {
                state.update { it.copy(requesting = false) }
            }
        }
    }

    /**
     * Anota la revisión de la copia en pantalla y la vuelve a leer, para mostrar la fecha nueva.
     * Nunca toca lo que cuenta como respaldado: el estado sigue diciendo entregada.
     */
    private fun markSeen() {
        val generation = state.value.handoff?.generation ?: return
        viewModelScope.launch {
            val saved = read { recordCheck(generation) } == true
            if (!saved) {
                state.update { it.copy(message = BackupMessage.SAVE_FAILED) }
                return@launch
            }
            val handoff = read { destination.latestHandoff() }
            state.update { it.copy(handoff = handoff, message = BackupMessage.CHECK_SAVED) }
        }
    }

    /** La copia más reciente que se puede restaurar, para un conflicto sin cabeza a la vista. */
    private fun newestRestorable(): BackupCopySummary? =
        (state.value.copies as? BackupCopyList.Listed)?.copies?.firstOrNull { it.restorable }

    private fun copyOf(key: String): BackupCopySummary? =
        (state.value.copies as? BackupCopyList.Listed)?.copies?.firstOrNull { it.key == key }

    /**
     * Lo leído, o nulo si falló: una falla de lectura no cierra la pantalla, y la lista lo dice
     * como un error, nunca como una lista vacía.
     */
    private suspend fun <T> read(block: suspend () -> T): T? = try {
        withContext(computation) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }
}

/** Si `Salud de mis datos` va plegado sin que la persona lo haya tocado: solo con todo bien. */
internal fun healthFolded(rows: List<HealthRow>?): Boolean =
    rows != null && DataHealthRule.allGood(rows)
