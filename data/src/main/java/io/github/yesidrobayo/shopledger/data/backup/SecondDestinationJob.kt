package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Una ejecución del trabajo del segundo destino, sin red y con cualquier `link_state`.
 *
 * Como el paso 0 del respaldo: con una restauración en curso o a medias, o con la base esperando una
 * migración o un reemplazo, pide reintentar; con la base en rescate no hace nada. Todo lo demás corre
 * bajo el cerrojo del respaldo, de generar la copia a podar: una subida y esta copia nunca se cruzan,
 * y una tarjeta retirada a mitad nunca corta una subida.
 *
 * @param restorePending si hay una restauración en curso o a medias. Lee el disco.
 * @param writer el escritor, con la base abierta para un trabajo; [Opened] dice si no se pudo.
 * @param afterRun lo que sigue: con la opción encendida y cambios que quedaron, vuelve a empezar la
 *   espera.
 */
internal class SecondDestinationJob(
    private val restorePending: suspend () -> Boolean,
    private val writer: suspend () -> Opened,
    private val mutex: Mutex,
    private val log: EventLog,
    private val afterRun: suspend () -> Unit = {},
) {
    suspend fun run(): JobResult {
        if (restorePending()) return JobResult.RETRY
        val ready =
            when (val opened = writer()) {
                is Opened.Ready -> opened.writer
                Opened.Waiting -> return JobResult.RETRY
                Opened.Unavailable -> return JobResult.DONE
            }
        val run =
            mutex.withLock {
                // Quien tenía el cerrojo pudo ser una restauración: se mira otra vez.
                if (restorePending()) return JobResult.RETRY
                ready.writeOwnCopy()
            }
        report(run)
        afterRun()
        return when (run) {
            SecondDestinationRun.Retry -> JobResult.RETRY

            SecondDestinationRun.Written,
            SecondDestinationRun.NoFolder,
            SecondDestinationRun.NoChanges,
            SecondDestinationRun.Empty,
            is SecondDestinationRun.Problem,
            -> JobResult.DONE
        }
    }

    private fun report(run: SecondDestinationRun) {
        when (run) {
            SecondDestinationRun.Written -> log.info(SecondDestinationEvent.WRITTEN)

            is SecondDestinationRun.Problem ->
                log.warn(SecondDestinationEvent.PROBLEM, LogField.state("motivo", run.problem))

            SecondDestinationRun.NoFolder,
            SecondDestinationRun.NoChanges,
            SecondDestinationRun.Empty,
            SecondDestinationRun.Retry,
            -> Unit
        }
    }

    /** Lo que dio abrir la base para el trabajo. */
    sealed interface Opened {
        class Ready(val writer: SecondDestinationWriter) : Opened

        /** Una migración o un reemplazo esperan: se reintenta. */
        data object Waiting : Opened

        /** La base está en rescate: aquí no se toca nada. */
        data object Unavailable : Opened
    }

    enum class JobResult {
        DONE,
        RETRY,
    }
}

/** Los eventos del registro del segundo destino. Solo motivos, nunca la carpeta ni un nombre. */
internal enum class SecondDestinationEvent(override val text: String) : LogEvent {
    WRITTEN("Copia escrita en el segundo destino"),
    PROBLEM("La copia del segundo destino no se escribió"),
    MAIN_COPY_FAILED("La copia principal no quedó en el segundo destino, y la subida sigue"),
    WORKER_FAILED("Falla no prevista del trabajo del segundo destino"),
}
