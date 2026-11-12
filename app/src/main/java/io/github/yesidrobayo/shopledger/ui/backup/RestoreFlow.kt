package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La restauración que pide una pantalla, la del respaldo o la del primer uso: la hoja de una copia,
 * la confirmación, el progreso, las preguntas y cancelar. El estado lo guarda quien la usa, por
 * [get] y [set]: así cada pantalla lo tiene en su propio estado.
 *
 * Nada de esto cambia los datos: solo una restauración lista y un reinicio los reemplazan.
 *
 * @param needsNetwork si traer una copia de la lista pide internet, como con WebDAV.
 * @param hasNetwork si hay ahora una red comprobada.
 */
internal class RestoreFlow(
    private val use: RestoreBackupUseCase,
    private val scope: CoroutineScope,
    private val computation: CoroutineDispatcher,
    private val get: () -> RestoreUi,
    private val set: (RestoreUi) -> Unit,
    private val needsNetwork: () -> Boolean,
    private val hasNetwork: () -> Boolean,
) {
    private var running: Job? = null

    /** La hoja de [copy]. Una copia que no se puede restaurar no tiene hoja. */
    fun open(copy: BackupCopySummary) {
        if (copy.restorable) set(RestoreUi.CopySheet(copy))
    }

    /** `Restaurar` en la hoja: pide confirmar. */
    fun confirm(copy: BackupCopySummary) {
        if (copy.restorable) set(RestoreUi.ConfirmCopy(copy))
    }

    /** `Volver a antes de la actualización`: pide confirmar, con el día de [copy]. */
    fun confirmBeforeUpdate(copy: BeforeUpdateCopy) =
        set(RestoreUi.ConfirmBeforeUpdate(copy.createdAt))

    /** El selector devolvió [uri]: pide confirmar, porque no viene de la lista. */
    fun picked(uri: String) = set(RestoreUi.ConfirmFile(uri))

    /** Empieza lo confirmado. Sin red, una copia que la necesita no empieza. */
    fun start() {
        val (origin, step) =
            when (val current = get()) {
                is RestoreUi.ConfirmCopy -> {
                    if (needsNetwork() && !hasNetwork()) {
                        set(RestoreUi.Stopped(RestoreNotice.NO_INTERNET, RestoreOrigin.LIST))
                        return
                    }
                    RestoreOrigin.LIST to suspend { use.copy(current.copy.key) }
                }

                is RestoreUi.ConfirmFile -> RestoreOrigin.FILE to suspend { use.file(current.uri) }

                is RestoreUi.ConfirmBeforeUpdate ->
                    RestoreOrigin.BEFORE_UPDATE to suspend { use.beforeUpdate() }

                RestoreUi.Idle,
                is RestoreUi.CopySheet,
                is RestoreUi.Working,
                is RestoreUi.Asking,
                is RestoreUi.Stopped,
                RestoreUi.Ready,
                -> return
            }
        follow(origin, step)
    }

    /** Contesta la pregunta en espera. */
    fun answer(answer: RestoreAnswer) {
        val asking = get() as? RestoreUi.Asking ?: return
        follow(asking.origin) { use.answer(answer) }
    }

    /**
     * Cancela: corta lo que corre, lo que abandona la preparación sin tocar los datos, o suelta la
     * pregunta en espera. Lista para reiniciar ya no se cancela.
     */
    fun cancel() {
        val current = get()
        val origin =
            when (current) {
                is RestoreUi.Working -> current.origin

                is RestoreUi.Asking -> current.origin

                RestoreUi.Idle,
                is RestoreUi.CopySheet,
                is RestoreUi.ConfirmCopy,
                is RestoreUi.ConfirmFile,
                is RestoreUi.ConfirmBeforeUpdate,
                is RestoreUi.Stopped,
                RestoreUi.Ready,
                -> return
            }
        running?.cancel()
        running = null
        set(RestoreUi.Stopped(RestoreNotice.CANCELLED, origin))
        if (current is RestoreUi.Asking) scope.launch { read { use.cancel() } }
    }

    /** Cierra la hoja, el diálogo o el aviso. */
    fun close() = set(RestoreUi.Idle)

    private fun follow(origin: RestoreOrigin, step: suspend () -> RestoreProgress) {
        set(RestoreUi.Working(origin))
        running = scope.launch {
            val progress = read { step() } ?: RestoreProgress.Failed
            set(uiOf(progress, origin))
        }
    }

    private fun uiOf(progress: RestoreProgress, origin: RestoreOrigin): RestoreUi {
        if (RestoreBackupUseCase.asks(progress)) return RestoreUi.Asking(progress, origin)
        if (progress is RestoreProgress.ReadyToRestart) return RestoreUi.Ready
        val notice = noticeOf(progress, origin) ?: RestoreNotice.OTHER
        val missing = (progress as? RestoreProgress.NotEnoughSpace)?.missingBytes ?: 0L
        return RestoreUi.Stopped(notice, origin, missing)
    }

    private suspend fun <T> read(block: suspend () -> T): T? = try {
        withContext(computation) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
