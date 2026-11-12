package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.model.backup.SafetyCopyInfo
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort
import java.io.File
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.withContext

/**
 * Lo que un archivo elegido con el selector deja hacer: decir su tamaño, si el proveedor lo sabe, y
 * abrirlo para leerlo una vez. Su nombre nunca se usa.
 */
internal interface PickedFiles {
    /** El tamaño que dice el proveedor, o nulo. */
    fun declaredSize(uri: String): Long?

    /** Abre [uri] para leerlo de principio a fin. Lanza `IOException` si el proveedor no lo da. */
    fun open(uri: String): InputStream
}

/**
 * Restaurar desde la pantalla, sobre la misma preparación del arranque: una copia de la lista del
 * destino conectado, o un archivo elegido con el selector.
 *
 * - La copia de la lista se vuelve a buscar en el destino por su clave: lo que viaja desde la
 *   pantalla es solo esa clave, nunca una ruta ni metadatos.
 * - Un archivo elegido no trae suma esperada: lo revisa el CRC de gzip y todo lo demás.
 * - Nada de lo que devuelve lleva texto del destino ni datos del negocio: solo códigos.
 *
 * @param saved el destino conectado: de la fila del respaldo, o de `link.json` en el rescate, donde
 *   la base no se lee.
 * @param coordinator la preparación de restauraciones, una por proceso.
 * @param beforeUpdate la copia previa a migrar, en `pre-migration/`. Pedirla no toca el disco.
 */
internal class BackupRestoreService(
    private val saved: suspend () -> SavedDestination?,
    private val destinations: DestinationOpener,
    private val coordinator: suspend () -> RestoreCoordinator,
    private val files: PickedFiles,
    private val beforeUpdate: suspend () -> File,
    private val ioContext: CoroutineContext,
) : BackupRestorePort {
    private var waiting: List<SafetyCopy> = emptyList()

    /** Si la preparación en curso vuelve a antes de la actualización: cambia lo que dice un rechazo. */
    private var undoingUpdate = false

    override suspend fun restoreCopy(copyKey: String): RestoreProgress {
        val link = saved()?.connected()
            ?: return RestoreProgress.Refused(RestoreProblem.NOT_CONNECTED)
        val destination =
            when (val opened = destinations.open(link)) {
                is DestinationOpen.Opened -> opened.destination

                DestinationOpen.PermissionLost ->
                    return RestoreProgress.Refused(RestoreProblem.NOT_AVAILABLE)

                DestinationOpen.RetryLater -> return RestoreProgress.Failed
            }
        val listing =
            when (val listed = destination.listCopies(TransferControl.Unbounded)) {
                is DestinationResult.Success -> listed.value
                is DestinationResult.Failure -> return RestoreProgress.Failed
            }
        val copy = listing.copies.firstOrNull { it.ref.value == copyKey }
            ?: return RestoreProgress.Refused(RestoreProblem.NOT_AVAILABLE)
        undoingUpdate = false
        return progressOf(coordinator().start(RestoreSource.Listed(copy)))
    }

    override suspend fun restoreFile(uri: String): RestoreProgress {
        val source = RestoreSource.PickedFile(files.declaredSize(uri)) { files.open(uri) }
        undoingUpdate = false
        return progressOf(coordinator().start(source))
    }

    override suspend fun beforeUpdateCopy(): BeforeUpdateCopy? = withContext(ioContext) {
        val copy = beforeUpdate()
        if (copy.isFile) {
            BeforeUpdateCopy(
                Instant.fromEpochMilliseconds(copy.lastModified()),
            )
        } else {
            null
        }
    }

    override suspend fun restoreBeforeUpdate(): RestoreProgress {
        undoingUpdate = true
        return progressOf(coordinator().start(RestoreSource.BeforeUpdate))
    }

    override suspend fun answer(answer: RestoreAnswer): RestoreProgress {
        val decision =
            when (answer) {
                RestoreAnswer.Repair -> RestoreDecision.Repair

                RestoreAnswer.ReplaceWithEmptyCopy -> RestoreDecision.ReplaceWithEmptyCopy

                RestoreAnswer.ContinueWithoutSafetyCopy -> RestoreDecision.ContinueWithoutSafetyCopy

                is RestoreAnswer.DiscardSafetyCopy ->
                    RestoreDecision.DiscardSafetyCopy(
                        waiting.getOrNull(answer.index) ?: return RestoreProgress.Busy,
                    )
            }
        return progressOf(coordinator().decide(decision))
    }

    override suspend fun cancel(): Boolean = coordinator().cancel()

    private fun progressOf(stage: RestoreStage): RestoreProgress = when (stage) {
        RestoreStage.Busy -> RestoreProgress.Busy

        is RestoreStage.NotEnoughSpace -> RestoreProgress.NotEnoughSpace(stage.missingBytes)

        is RestoreStage.Refused -> RestoreProgress.Refused(refusalOf(stage.reason))

        is RestoreStage.Failed -> RestoreProgress.Failed

        is RestoreStage.ConfirmRepair -> RestoreProgress.AskRepair(stage.rows)

        RestoreStage.ConfirmEmptyCopy -> RestoreProgress.AskEmptyCopy

        RestoreStage.ConfirmWithoutSafetyCopy -> RestoreProgress.AskWithoutSafetyCopy

        is RestoreStage.ChooseSafetyCopyToDiscard -> {
            waiting = stage.copies
            RestoreProgress.AskDiscardSafetyCopy(
                stage.copies.map { SafetyCopyInfo(it.facts?.createdAt) },
            )
        }

        is RestoreStage.ReadyToRestart -> RestoreProgress.ReadyToRestart(stage.warnings)
    }

    /**
     * Lo que dice un rechazo. Al volver a antes de la actualización, una copia previa que esta
     * versión no sabe migrar pide instalar la versión corregida: no es una copia dañada.
     */
    private fun refusalOf(reason: RestoreRefusal): RestoreProblem {
        if (!undoingUpdate) return problemOf(reason)
        return when (reason) {
            RestoreRefusal.MigrationFailed,
            RestoreRefusal.Candidate(CandidateRejection.SCHEMA_TOO_NEW),
            RestoreRefusal.Candidate(CandidateRejection.UNKNOWN_SCHEMA_VERSION),
            -> RestoreProblem.NEEDS_UPDATE

            else -> problemOf(reason)
        }
    }

    companion object {
        internal fun problemOf(reason: RestoreRefusal): RestoreProblem = when (reason) {
            is RestoreRefusal.Candidate -> problemOf(reason.reason)

            RestoreRefusal.MarkedFatal -> RestoreProblem.MARKED_FATAL

            RestoreRefusal.TooLarge -> RestoreProblem.TOO_LARGE

            // El destino no la entregó: ya no está, o no deja leerla.
            is RestoreRefusal.Download -> RestoreProblem.NOT_AVAILABLE

            RestoreRefusal.NoDestination -> RestoreProblem.NOT_CONNECTED

            RestoreRefusal.NoBeforeUpdateCopy -> RestoreProblem.NOT_AVAILABLE

            RestoreRefusal.MigrationFailed,
            RestoreRefusal.SafetyCopyNoSpace,
            RestoreRefusal.LockLost,
            -> RestoreProblem.OTHER
        }

        internal fun problemOf(rejection: CandidateRejection): RestoreProblem = when (rejection) {
            CandidateRejection.UNKNOWN_FORMAT,
            CandidateRejection.NOT_A_DATABASE,
            CandidateRejection.UNKNOWN_SCHEMA_VERSION,
            -> RestoreProblem.NOT_A_BACKUP

            CandidateRejection.SCHEMA_TOO_NEW -> RestoreProblem.NEWER_VERSION

            CandidateRejection.ENCRYPTED_FORMAT -> RestoreProblem.ENCRYPTED

            CandidateRejection.DECOMPRESSED_TOO_LARGE -> RestoreProblem.TOO_LARGE

            CandidateRejection.CHECKSUM_MISMATCH,
            CandidateRejection.DAMAGED_ARCHIVE,
            CandidateRejection.WAL_DATABASE,
            CandidateRejection.SCHEMA_MISMATCH,
            CandidateRejection.INTEGRITY_FAILED,
            CandidateRejection.DATA_FATAL,
            CandidateRejection.REPAIR_INCOMPLETE,
            -> RestoreProblem.DAMAGED

            // El teléfono no pudo abrir la copia aislada: no dice nada de la copia.
            CandidateRejection.ISOLATION_FAILED -> RestoreProblem.OTHER
        }
    }
}
