package io.github.yesidrobayo.shopledger.domain.rule.erase

import io.github.yesidrobayo.shopledger.domain.model.erase.EraseDestination
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseFacts
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseStop
import io.github.yesidrobayo.shopledger.domain.model.erase.RemoteEraseResult

/** Por dónde empieza un borrado confirmado, o por qué no empieza. */
sealed interface EraseStart {
    /** Se guarda [phase] y se sigue desde ahí. */
    data class Begin(val phase: ErasePhase) : EraseStart

    /** No empieza, y no se guarda ni se toca nada. */
    data class Refused(val reason: EraseStop) : EraseStart
}

/**
 * Las decisiones de `Borrar mis datos`, sin tocar nada. El orden es siempre primero las copias del
 * destino y al final lo de este teléfono, para que un fallo a mitad nunca deje a la persona sin
 * nada: mientras la nube no confirme, lo local no se toca.
 */
object EraseRules {
    /**
     * Cómo empieza el borrado de [scope] con [facts].
     *
     * - Con una restauración en curso, nunca: se cruzarían.
     * - `Solo este teléfono` va directo a lo local.
     * - `Este teléfono y las copias` sin destino no tiene copias que alcanzar: va a lo local.
     * - Con el acceso perdido, o con WebDAV sin red, no empieza: lo local queda intacto, y la
     *   persona elige reconectar o borrar solo este teléfono, de forma expresa.
     * - Con ranuras no hace falta red: se le pide a la otra app.
     */
    fun start(scope: EraseScope, facts: EraseFacts): EraseStart {
        if (facts.restoreInProgress) return EraseStart.Refused(EraseStop.RESTORE_IN_PROGRESS)
        if (scope == EraseScope.DEVICE_ONLY) return EraseStart.Begin(ErasePhase.LOCAL)
        return when (facts.destination) {
            EraseDestination.NONE -> EraseStart.Begin(ErasePhase.LOCAL)

            EraseDestination.ACCESS_LOST -> EraseStart.Refused(EraseStop.ACCESS_LOST)

            EraseDestination.WEBDAV ->
                if (facts.hasNetwork) {
                    EraseStart.Begin(ErasePhase.REMOTE)
                } else {
                    EraseStart.Refused(EraseStop.OFFLINE)
                }

            EraseDestination.SLOTS -> EraseStart.Begin(ErasePhase.REMOTE)
        }
    }

    /**
     * Qué sigue tras pedir el borrado de las copias: `null` si ya no queda ninguna y toca lo
     * local, o por qué se detiene antes de tocar lo local.
     */
    fun afterRemote(result: RemoteEraseResult): EraseStop? = when (result) {
        RemoteEraseResult.DELETED -> null
        RemoteEraseResult.PARTIAL -> EraseStop.PARTIAL
        RemoteEraseResult.UNREACHABLE -> EraseStop.UNREACHABLE
        RemoteEraseResult.REFUSED -> EraseStop.REFUSED
        RemoteEraseResult.ACCESS_LOST -> EraseStop.ACCESS_LOST
    }

    /**
     * `Borrar solo este teléfono`, confirmado de forma expresa tras un borrado de la nube que no
     * pudo seguir: va a lo local, salvo con una restauración en curso. Las copias quedan en la
     * nube.
     */
    fun deviceOnly(facts: EraseFacts): EraseStart = start(EraseScope.DEVICE_ONLY, facts)
}
