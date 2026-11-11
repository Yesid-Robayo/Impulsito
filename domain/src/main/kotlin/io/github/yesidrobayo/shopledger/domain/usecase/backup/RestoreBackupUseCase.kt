package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort

/**
 * Restaurar una copia o un archivo elegido. Nada se reemplaza hasta reiniciar, y una pregunta
 * detiene la restauración hasta que la persona conteste o cancele.
 */
class RestoreBackupUseCase(private val port: BackupRestorePort) {
    /** Empieza con una copia de la lista. */
    suspend fun copy(copyKey: String): RestoreProgress = port.restoreCopy(copyKey)

    /** Empieza con un archivo elegido con el selector. */
    suspend fun file(uri: String): RestoreProgress = port.restoreFile(uri)

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
            -> false
        }
    }
}
