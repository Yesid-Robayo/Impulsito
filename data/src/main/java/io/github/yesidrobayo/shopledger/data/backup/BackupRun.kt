package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.InterruptReason
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn

/**
 * Cómo terminó una ejecución del respaldo. El trabajo en segundo plano lo traduce a su resultado:
 * terminar, reintentar más tarde o detenerse hasta un evento nuevo.
 */
internal sealed interface BackupRun {
    /**
     * La copia [generation] quedó en el destino, comprobada y confirmada.
     *
     * @property unrestorable la app no podría restaurar esa copia: subió solo para un rescate
     *   manual, y los cambios siguen sin respaldar. Repetirla sin un cambio nuevo daría otra igual,
     *   así que no se encola otra copia por esos cambios.
     */
    data class Verified(val generation: Long, val unrestorable: Boolean = false) : BackupRun

    /** No había nada que subir, o no se debía subir. Nada falló. */
    data class Skipped(val reason: SkipReason) : BackupRun

    /**
     * No se pudo ahora, y puede pasar solo: el sistema vuelve a correr el trabajo más tarde. Si
     * había una copia en camino, su archivo se conserva para el siguiente intento.
     *
     * @property transientKind la clase del fallo de transporte, con [RetryReason.TRANSIENT].
     * @property interruptReason por qué se cortó, con [RetryReason.INTERRUPTED].
     */
    data class Retry(
        val reason: RetryReason,
        val transientKind: TransientKind? = null,
        val interruptReason: InterruptReason? = null,
    ) : BackupRun

    /**
     * No se reintenta sola: el estado quedó a la vista, y un cambio nuevo, o la persona, la retoma.
     */
    data class Stopped(val cause: StopCause) : BackupRun
}

/** Por qué una ejecución no subió nada sin que fallara. */
internal enum class SkipReason {
    /** No hay destino conectado, o el enlace espera a la persona. */
    NOT_CONNECTED,

    /**
     * Falta aceptar la versión vigente de la política y los términos: ninguna copia sale a la nube
     * hasta entonces, ni siquiera con `Respaldar ahora`. Al aceptarla se encola lo pendiente.
     */
    POLICY_PENDING,

    /** Todos los cambios ya tienen copia, y no hay ninguna subida a medias. */
    NOTHING_PENDING,

    /** La base no tiene nada del negocio: nunca tapa una copia con datos. */
    EMPTY_DATABASE,

    /** La persona cambió de destino durante la subida: la copia no se confirma en el nuevo. */
    DESTINATION_CHANGED,

    /**
     * La app no podría restaurar la copia, y con ranuras no se escribe: taparía una que sí se
     * restaura. El fallo queda a la vista y los cambios siguen sin respaldar.
     */
    UNRESTORABLE_NOT_WRITTEN,

    /**
     * Con datos móviles, el intervalo entre copias automáticas sigue corriendo: la espera vuelve a
     * calcular el retraso al vencer.
     */
    WAITING_FOR_INTERVAL,

    /** Con `Solo con Wi-Fi` y datos móviles: espera a la subida que corre sin datos móviles. */
    WAITING_FOR_UNMETERED,

    /**
     * Con la nube del teléfono, la copia no la pidió la persona, o la app ya no estaba a la vista:
     * no se escribió nada, y los cambios siguen sin respaldar hasta `Guardar en {provider}`.
     */
    WAITING_FOR_PERSON,
}

/** Por qué una ejecución se reintenta más tarde. */
internal enum class RetryReason {
    /** Hay una restauración en curso o a medias: la base puede cambiar entera. */
    RESTORE_IN_PROGRESS,

    /** La base debe migrar, y solo la app en primer plano lo hace. No se abrió. */
    MIGRATION_PENDING,

    /** La base no abrió sin riesgo: la app irá al rescate. */
    DATABASE_UNAVAILABLE,

    /** Keystore o el disco fallaron al abrir la credencial, sin perderla. */
    DESTINATION_NOT_READY,

    /** La lista del destino llegó con un error dentro: nunca se toma por una carpeta vacía. */
    INCOMPLETE_LISTING,

    /** Un fallo de transporte que puede pasar solo. */
    TRANSIENT,

    /** La operación se detuvo a propósito: el sistema pidió parar, o se acabó el tiempo. */
    INTERRUPTED,

    /** No se pudo guardar en el teléfono el estado que acompaña al destino. */
    LOCAL_STATE_NOT_SAVED,

    /** Una falla de SQLite o del disco al armar la copia, que no dice nada de la base. */
    LOCAL_FAILURE,
}

/** Por qué una ejecución se detiene hasta un evento nuevo. */
internal sealed interface StopCause {
    /** Falló, con este código a la vista. */
    data class Failure(val code: BackupFailureCode) : StopCause

    /** El destino ya no deja entrar. */
    data object PermissionLost : StopCause

    /** El destino tiene algo que la persona debe decidir. */
    data class Conflict(val kind: BackupConflictKind) : StopCause
}

/** Los eventos del registro del respaldo. Solo llevan códigos, conteos y duraciones. */
internal enum class BackupEvent(override val text: String) : LogEvent {
    COPY_VERIFIED("Copia verificada y confirmada"),
    COPY_UNRESTORABLE("Copia confirmada solo para un rescate: la app no la restaura"),
    RUN_SKIPPED("Respaldo sin nada que subir"),
    RUN_RETRY("Respaldo para más tarde"),
    RUN_STOPPED("Respaldo detenido"),
    LOCAL_FAILURE("Falla local al armar la copia"),
    PRUNE_FAILED("La poda falló, y la copia nueva sigue confirmada"),
    PRUNE_DONE("Poda terminada"),
    WORKER_FAILED("Falla no prevista del trabajo de respaldo"),
    SCHEDULING_FAILED("Falla no prevista al programar el respaldo"),
    UPLOAD_USAGE_FAILED("No se pudo anotar el consumo del mes, y la copia sigue"),
    EXPORT_AUDIT_FAILED("No se pudo anotar la copia exportada, que ya quedó escrita"),
}

/** Deja en el registro cómo terminó [run]: solo motivos, códigos y la generación. */
internal fun EventLog.report(run: BackupRun) {
    when (run) {
        is BackupRun.Verified ->
            if (run.unrestorable) {
                warn(BackupEvent.COPY_UNRESTORABLE, LogField.id("generación", run.generation))
            } else {
                info(BackupEvent.COPY_VERIFIED, LogField.id("generación", run.generation))
            }

        is BackupRun.Skipped -> info(BackupEvent.RUN_SKIPPED, LogField.state("motivo", run.reason))

        is BackupRun.Retry ->
            info(
                BackupEvent.RUN_RETRY,
                *listOfNotNull(
                    LogField.state("motivo", run.reason),
                    run.transientKind?.let { LogField.state("transporte", it) },
                    run.interruptReason?.let { LogField.state("corte", it) },
                ).toTypedArray(),
            )

        is BackupRun.Stopped -> warn(BackupEvent.RUN_STOPPED, stopField(run.cause))
    }
}

private fun stopField(cause: StopCause): LogField = when (cause) {
    is StopCause.Failure -> LogField.state("código", cause.code)
    StopCause.PermissionLost -> LogField.state("enlace", BackupLinkState.PERMISSION_LOST)
    is StopCause.Conflict -> LogField.state("conflicto", cause.kind)
}
