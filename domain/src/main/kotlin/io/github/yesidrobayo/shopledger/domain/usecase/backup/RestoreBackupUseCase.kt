package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.model.backup.UndoRestoreCopy
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort

/**
 * Restaurar una copia o un archivo elegido. Nada se reemplaza hasta reiniciar, y una pregunta
 * detiene la restauración hasta que la persona conteste o cancele.
 */
class RestoreBackupUseCase(private val port: BackupRestorePort) {
    /** Empieza con una copia de la lista, con el código escrito si la copia lo pidió. */
    suspend fun copy(copyKey: String, code: String? = null): RestoreProgress =
        port.restoreCopy(copyKey, code)

    /** Empieza con un archivo elegido con el selector, con el código escrito si lo pidió. */
    suspend fun file(uri: String, code: String? = null): RestoreProgress =
        port.restoreFile(uri, code)

    /** La copia previa a la última actualización, o nula si no hay. */
    suspend fun beforeUpdateCopy(): BeforeUpdateCopy? = port.beforeUpdateCopy()

    /** Empieza a volver a antes de la última actualización. */
    suspend fun beforeUpdate(): RestoreProgress = port.restoreBeforeUpdate()

    /** La copia local para volver a como estaba antes de restaurar, o nula si no hay vigente. */
    suspend fun undoRestoreCopy(): UndoRestoreCopy? = port.undoRestoreCopy()

    /** Empieza a volver a [copy], la copia local de antes de restaurar. */
    suspend fun undoRestore(copy: UndoRestoreCopy): RestoreProgress = port.restoreUndo(copy)

    /** Contesta una pregunta. */
    suspend fun answer(answer: RestoreAnswer): RestoreProgress = port.answer(answer)

    /** Cancela lo que espera una respuesta. */
    suspend fun cancel(): Boolean = port.cancel()

    companion object {
        /** Si la restauración espera una respuesta de la persona. */
        fun asks(progress: RestoreProgress): Boolean = when (progress) {
            is RestoreProgress.AskRepair,
            RestoreProgress.AskEmptyCopy,
            RestoreProgress.AskWithoutSafetyCopy,
            is RestoreProgress.AskDiscardSafetyCopy,
            -> true

            RestoreProgress.Busy,
            is RestoreProgress.NotEnoughSpace,
            is RestoreProgress.Refused,
            RestoreProgress.Failed,
            is RestoreProgress.ReadyToRestart,
            // El código no se contesta: con él, la restauración empieza de nuevo.
            is RestoreProgress.AskRecoveryCode,
            -> false
        }
    }
}
