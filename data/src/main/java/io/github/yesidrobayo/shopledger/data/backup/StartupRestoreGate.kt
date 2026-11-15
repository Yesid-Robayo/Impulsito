package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.RescueCause
import io.github.yesidrobayo.shopledger.data.local.GateCaller
import io.github.yesidrobayo.shopledger.data.local.PendingRestore
import io.github.yesidrobayo.shopledger.data.local.RestoreStep
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.error
import io.github.yesidrobayo.shopledger.domain.port.info
import java.io.File

/**
 * La segunda fase de la restauración vista desde la compuerta del arranque: corre antes de que nada
 * abra la base, y nada la abre sin pasar por la compuerta.
 *
 * Los pasos de archivo corren siempre, también para un trabajo en segundo plano: solo renombran. La
 * verificación de la base nueva abre Room, y por eso solo la hace la app en primer plano; un trabajo
 * espera y devuelve `retry()`.
 *
 * Si el reemplazo terminado promovió el código de una copia cifrada, lo recuerda hasta que la
 * compuerta abre la base: el evento de auditoría se escribe en la base nueva, y antes no hay dónde.
 *
 * @param verifierOf arma el verificador con la apertura de la compuerta.
 */
internal class StartupRestoreGate<D>(
    private val startupRestore: StartupRestore,
    private val log: EventLog,
    private val verifierOf: (suspend (File) -> D) -> RestoredDatabaseVerifier<D>,
) : PendingRestore<D> {
    @Volatile private var encryptionChanged = false

    /**
     * Si un reemplazo terminado en este proceso dejó vigente el código de una copia cifrada, y
     * todavía no se anotó. Devuelve `true` una sola vez.
     */
    fun takeEncryptionChanged(): Boolean = encryptionChanged.also { encryptionChanged = false }

    @Volatile private var restoreOutcome: Boolean? = null

    /**
     * Cómo terminó el reemplazo de una restauración en este proceso, para anotarlo en la
     * auditoría de la base que queda abierta: `true` si la base nueva quedó en su sitio, `false`
     * si volvió la anterior, y `null` si no hubo reemplazo o ya se pidió. Lo da una sola vez.
     */
    fun takeRestoreOutcome(): Boolean? = restoreOutcome.also { restoreOutcome = null }

    override suspend fun finish(caller: GateCaller, open: suspend (File) -> D): RestoreStep<D> =
        when (val files = startupRestore.runFileSteps()) {
            StartupRestoreResult.NoRestore -> RestoreStep.Continue

            StartupRestoreResult.UnreadableJournal -> {
                log.error(DataEvent.RESTORE_JOURNAL_UNREADABLE)
                RestoreStep.Rescue(RescueCause.RestoreJournalUnreadable)
            }

            is StartupRestoreResult.RolledBack -> rolledBack(files.databasePresent)

            is StartupRestoreResult.Restored -> {
                log.info(DataEvent.RESTORE_FINISHED)
                if (files.encryptionChanged) encryptionChanged = true
                restoreOutcome = true
                RestoreStep.Continue
            }

            StartupRestoreResult.VerificationPending -> when (caller) {
                GateCaller.BACKGROUND_WORK -> {
                    log.info(DataEvent.RESTORE_LEFT_TO_APP)
                    RestoreStep.WaitForApp
                }

                GateCaller.APP_IN_FOREGROUND -> verify(open)
            }
        }

    private suspend fun verify(open: suspend (File) -> D): RestoreStep<D> =
        when (val result = startupRestore.verifyAndFinish(verifierOf(open))) {
            is StartupRestoreResult.Restored -> {
                log.info(DataEvent.RESTORE_FINISHED)
                if (result.encryptionChanged) encryptionChanged = true
                restoreOutcome = true
                result.database?.let { RestoreStep.Restored(it) } ?: RestoreStep.Continue
            }

            is StartupRestoreResult.RolledBack -> rolledBack(result.databasePresent)

            StartupRestoreResult.UnreadableJournal -> {
                log.error(DataEvent.RESTORE_JOURNAL_UNREADABLE)
                RestoreStep.Rescue(RescueCause.RestoreJournalUnreadable)
            }

            // El diario desapareció entre los dos pasos: no hay nada más que terminar.
            StartupRestoreResult.NoRestore -> RestoreStep.Continue

            // No vuelve a quedar pendiente: la verificación siempre decide.
            StartupRestoreResult.VerificationPending -> RestoreStep.WaitForApp
        }

    private fun rolledBack(databasePresent: Boolean): RestoreStep<D> = if (databasePresent) {
        log.info(DataEvent.RESTORE_ROLLED_BACK)
        restoreOutcome = false
        RestoreStep.Continue
    } else {
        // Sin base que devolver, abrir Room crearía una vacía: toca restaurar de nuevo.
        log.error(DataEvent.RESTORE_LEFT_NO_DATABASE)
        RestoreStep.Rescue(RescueCause.RestoreLeftNoDatabase)
    }
}
