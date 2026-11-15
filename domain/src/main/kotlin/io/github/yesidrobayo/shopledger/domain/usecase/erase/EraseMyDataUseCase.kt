package io.github.yesidrobayo.shopledger.domain.usecase.erase

import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseOutcome
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseStop
import io.github.yesidrobayo.shopledger.domain.port.AuditRecorder
import io.github.yesidrobayo.shopledger.domain.port.EraseMyDataPort
import io.github.yesidrobayo.shopledger.domain.rule.erase.EraseRules
import io.github.yesidrobayo.shopledger.domain.rule.erase.EraseStart

/**
 * `Borrar mis datos`, una máquina de estados guardada en el teléfono:
 *
 * 1. Con la confirmación, [EraseRules.start] decide por dónde empieza, y esa fase se guarda antes
 *    de tocar nada.
 * 2. En [ErasePhase.REMOTE] se borran las copias del destino. Si no se pudo, se detiene con la
 *    fase guardada y sin tocar este teléfono: [resume] lo intenta otra vez, y [deviceOnly] pasa a
 *    lo local con la confirmación expresa de la persona.
 * 3. Con la nube lista se guarda [ErasePhase.LOCAL], se anota `DELETE_MY_DATA` en la auditoría y
 *    se borra este teléfono. Una muerte aquí se retoma al abrir la app, antes que la base.
 *
 * Repetir cualquier paso es seguro: lo que ya no está cuenta como borrado. Nada se ejecuta solo.
 */
class EraseMyDataUseCase(private val port: EraseMyDataPort, private val audit: AuditRecorder) {
    /** El borrado confirmado que quedó en espera, si lo hay. */
    suspend fun pending(): ErasePhase? = port.pending()

    /** Empieza el borrado de [scope], ya confirmado por la persona. */
    suspend operator fun invoke(scope: EraseScope): EraseOutcome =
        begin(EraseRules.start(scope, port.facts()))

    /** Retoma el borrado en espera desde su fase guardada, o `null` si no hay ninguno. */
    suspend fun resume(): EraseOutcome? = port.pending()?.let { run(it) }

    /**
     * `Borrar solo este teléfono`, confirmado de forma expresa: lo local sin las copias, también
     * tras un borrado de la nube que se detuvo. Las copias que queden siguen en la nube.
     */
    suspend fun deviceOnly(): EraseOutcome = begin(EraseRules.deviceOnly(port.facts()))

    private suspend fun begin(start: EraseStart): EraseOutcome = when (start) {
        is EraseStart.Refused -> EraseOutcome.Stopped(start.reason)

        is EraseStart.Begin -> {
            port.save(start.phase)
            run(start.phase)
        }
    }

    private suspend fun run(phase: ErasePhase): EraseOutcome {
        if (phase == ErasePhase.REMOTE) {
            val stop = EraseRules.afterRemote(port.deleteRemote())
            if (stop != null) {
                audit.record(AuditEventType.DELETE_MY_DATA, AuditResult.FAILURE)
                return EraseOutcome.Stopped(stop)
            }
            port.save(ErasePhase.LOCAL)
        }
        // Antes de borrar lo local: después ya no hay base donde anotarlo.
        audit.record(AuditEventType.DELETE_MY_DATA, AuditResult.SUCCESS)
        return if (port.deleteLocal()) {
            EraseOutcome.Finished
        } else {
            EraseOutcome.Stopped(EraseStop.LOCAL_FAILED)
        }
    }
}
