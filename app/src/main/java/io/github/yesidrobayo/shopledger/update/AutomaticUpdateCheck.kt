package io.github.yesidrobayo.shopledger.update

import io.github.yesidrobayo.shopledger.domain.model.update.KnownUpdate
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.domain.port.Contained
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.rule.update.AutomaticUpdateRule

/**
 * La búsqueda automática de versión, una vez al día. Hace lo mismo que `Buscar actualización`:
 * lee el aviso firmado, comprueba su firma y compara el número de versión, sin enviar ningún dato.
 * Solo informa: nunca descarga ni instala nada, y nunca bloquea.
 *
 * - **Apagada no se conecta:** sin la búsqueda permitida o sin la automática, termina sin red.
 * - **Avisa una sola vez por versión:** con una versión nueva más alta que la última avisada, lanza
 *   la notificación y solo anota la versión si salió. Sin el permiso de avisos o con el canal
 *   silenciado, no anota nada: el aviso de `Inicio` sigue igual, y mañana se intenta de nuevo.
 * - **Un fallo nunca molesta:** sin red, con una firma que no coincide o con una respuesta rara,
 *   termina en silencio, y la del día siguiente lo intenta otra vez.
 * - **Ninguna excepción sale con su mensaje:** todo corre dentro de [EventLog.contain], y una falla
 *   queda en el registro solo con su clase.
 *
 * @param enabled si la búsqueda está permitida y la automática encendida. No usa la red.
 * @param check busca la versión, como el botón: anota la búsqueda y la versión conocida.
 * @param lastNotified la última versión avisada con una notificación, o nula.
 * @param notify lanza la notificación de [KnownUpdate]; `false` si el sistema no la deja salir.
 * @param markNotified anota la versión avisada.
 */
class AutomaticUpdateCheck(
    private val enabled: suspend () -> Boolean,
    private val check: suspend () -> UpdateCheckOutcome,
    private val lastNotified: suspend () -> Long?,
    private val notify: (KnownUpdate) -> Boolean,
    private val markNotified: suspend (Long) -> Boolean,
    private val log: EventLog,
) {
    /** Corre la búsqueda de hoy y dice cómo terminó. Nunca lanza, salvo la cancelación. */
    suspend fun run(): AutomaticCheckRun {
        val done = log.contain(UpdateCheckEvent.FAILED) { runChecked() }
        return when (done) {
            is Contained.Done -> done.value
            is Contained.Failed -> AutomaticCheckRun.FAILED
        }
    }

    private suspend fun runChecked(): AutomaticCheckRun {
        if (!enabled()) return finish(AutomaticCheckRun.OFF)
        val outcome = check()
        val found =
            AutomaticUpdateRule.toNotify(outcome, lastNotified())
                ?: return finish(quietRun(outcome))
        if (!notify(found)) return finish(AutomaticCheckRun.BLOCKED)
        markNotified(found.versionCode)
        return finish(AutomaticCheckRun.NOTIFIED)
    }

    private fun quietRun(outcome: UpdateCheckOutcome): AutomaticCheckRun = when (outcome) {
        // Una versión nueva que ya se avisó: Inicio la sigue mostrando, sin otra notificación.
        is UpdateCheckOutcome.NewVersion -> AutomaticCheckRun.ALREADY_NOTIFIED

        is UpdateCheckOutcome.Latest -> AutomaticCheckRun.LATEST

        UpdateCheckOutcome.Offline -> AutomaticCheckRun.OFFLINE

        UpdateCheckOutcome.BadSignature -> AutomaticCheckRun.BAD_SIGNATURE

        UpdateCheckOutcome.Unreadable -> AutomaticCheckRun.UNREADABLE
    }

    private fun finish(run: AutomaticCheckRun): AutomaticCheckRun {
        log.info(UpdateCheckEvent.FINISHED, LogField.state("estado", run))
        return run
    }
}

/** Cómo terminó una búsqueda automática. Solo es un estado: no lleva nada del aviso. */
enum class AutomaticCheckRun {
    /** La búsqueda o la automática están apagadas: no se conectó. */
    OFF,

    /** La instalada es la más reciente. */
    LATEST,

    /** Hay una versión nueva y salió su notificación. */
    NOTIFIED,

    /** Hay una versión nueva, pero el sistema no dejó salir la notificación. */
    BLOCKED,

    /** Hay una versión nueva que ya se avisó antes. */
    ALREADY_NOTIFIED,

    /** Sin red: se intenta mañana. */
    OFFLINE,

    /** El aviso no pasó la comprobación de firma y se ignoró. */
    BAD_SIGNATURE,

    /** El aviso no se pudo leer. */
    UNREADABLE,

    /** Una falla no prevista, que quedó en el registro con su clase. */
    FAILED,
}

/** Lo que registra la búsqueda automática. Cada texto es fijo y no lleva datos. */
internal enum class UpdateCheckEvent(override val text: String) : LogEvent {
    FINISHED("Búsqueda automática de versión terminada"),
    FAILED("Falla no prevista en la búsqueda automática de versión"),
    SCHEDULING_FAILED("No se pudo programar la búsqueda automática de versión"),
}
