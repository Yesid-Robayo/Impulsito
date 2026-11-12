package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.work.BackupWorkNames
import io.github.yesidrobayo.shopledger.domain.model.backup.AppOpenUpload
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupTimings
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.backup.SecondDestinationRule
import io.github.yesidrobayo.shopledger.domain.rule.backup.UploadCadence
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Traduce lo que pasa en la app a trabajos en segundo plano del respaldo: una venta confirmada, la
 * espera que vence, la app que se abre o pasa a segundo plano, `Respaldar ahora` y el final de cada
 * subida. Solo programa: la copia la hace el trabajo de subida, que vuelve a mirar todo al empezar.
 *
 * Los trabajos, con sus nombres únicos de [BackupWorkNames]:
 *
 * - **La espera** junta los cambios seguidos: cada cambio la vuelve a empezar, y al vencer encola la
 *   subida. Es un trabajo aparte porque reemplazar una subida que corre la cortaría.
 * - **Las dos subidas** de WebDAV: una sin retraso que solo corre con una red sin datos móviles, y
 *   otra con cualquier red y el resto del intervalo de datos móviles como retraso. Las dos se
 *   encolan sin reemplazar la que ya espera o corre. Con la nube del teléfono va solo la segunda,
 *   sin pedir red y con su propio intervalo ([UploadCadence]).
 *
 * **Nada se encola con una restauración en curso o a medias:** la base puede cambiar entera, y el
 * arranque retoma el respaldo al terminar.
 *
 * **Sin la versión vigente de la política aceptada, nada que vaya al destino se encola:** ni la
 * espera ni las subidas. `Respaldar ahora` pide aceptarla, y al aceptar se encola lo pendiente.
 *
 * Nada de esto usa la red ni espera a ella, y nada lleva datos del negocio.
 *
 * @param facts lo que el estado guardado dice, o nulo si la base todavía no se puede leer, como
 *   con una migración pendiente.
 * @param restorePending si hay una restauración en curso o a medias. Lee el disco.
 * @param policyAccepted si la persona aceptó la versión vigente de la política y los términos. Lee
 *   el disco.
 * @param probeDue si la última sonda de la cabeza ya venció, según `last-probe`. Lee el disco.
 * @param secondDestination el segundo destino y los cambios, o nulo sin carpeta elegida. Lee el
 *   disco. Es local: no depende del destino conectado ni de la política.
 * @param mainWaitingForNetwork si la copia principal espera red: sin red, sin Wi-Fi o con el ahorro
 *   de datos. Con `Mantener al día esta carpeta`, la carpeta no la espera.
 */
class BackupScheduler internal constructor(
    private val facts: suspend () -> ScheduleFacts?,
    private val restorePending: suspend () -> Boolean,
    private val policyAccepted: suspend () -> Boolean,
    private val queue: BackupWorkQueue,
    private val clock: Clock,
    private val secondDestination: suspend () -> SecondDestinationFacts? = { null },
    private val mainWaitingForNetwork: suspend () -> Boolean = { false },
    private val probeDue: suspend () -> Boolean = { false },
) {
    /**
     * Tras confirmar un cambio del negocio: siempre vuelve a empezar la espera. Si la racha ya lleva
     * [BackupTimings.BACKUP_MAX_WAIT] sin copia, encola además la subida, salvo tras una copia que
     * la app no podría restaurar: esa no reinicia el cambio más viejo, y cada venta subiría otra.
     */
    internal suspend fun onDataChanged() {
        if (!restorePending()) followStreak()
        if (paused()) return
        queue.restartDebounce(BackupTimings.BACKUP_DEBOUNCE)
        val current = uploadable() ?: return
        if (current.failureCode == BackupFailureCode.LOCAL_INVARIANT_FATAL.name) return
        if (UploadCadence.maxWaitReached(current.firstUnsyncedAt, clock.now())) {
            enqueueUpload(current, pastInterval = false)
        }
    }

    /**
     * Venció la espera: encola la subida si hay algo que subir. Devuelve `false` si la base todavía
     * no se puede leer, para que la espera se reintente sin perder el cambio.
     */
    internal suspend fun onDebounceElapsed(): Boolean {
        if (!restorePending()) followWithoutUpload()
        if (paused()) return true
        val current = facts() ?: return false
        if (current.isUploadable) enqueueUpload(current, pastInterval = false)
        return true
    }

    /**
     * La app pasó a segundo plano: con cambios sin copia, la subida sale ya, sin esperar, salvo lo
     * que pida la red: con datos móviles respeta el intervalo, y con la nube del teléfono el suyo.
     */
    suspend fun onAppBackgrounded() {
        if (restorePending()) return
        val current = uploadable() ?: return
        queue.cancel(BackupWorkNames.DEBOUNCE)
        enqueueUpload(current, pastInterval = false)
    }

    /**
     * La app se abrió, con lo que decidió el estado del respaldo en ese momento: con la app a la
     * vista no aplican los ahorros de batería, y un teléfono que mata los trabajos en segundo plano
     * recupera aquí la copia que no salió.
     *
     * Con la última sonda de la cabeza vencida, encola además la sonda única: con la app poco usada
     * el sistema le quita la red al trabajo semanal, y sin sondear el estado diría que todo está
     * guardado sin mirar el destino.
     */
    suspend fun onAppOpened(upload: AppOpenUpload) {
        if (paused()) return
        if (probeDue()) probeOnce()
        if (upload == AppOpenUpload.NONE) return
        val current = uploadable() ?: return
        enqueueUpload(current, pastInterval = upload == AppOpenUpload.PAST_INTERVAL)
    }

    /**
     * `Respaldar ahora`: una subida sin espera ni intervalo, con cualquier red, y aunque la cuota
     * informada diga que no cabe. Sin red queda encolada hasta que vuelva.
     */
    suspend fun backupNow(): BackupNowRequest {
        if (restorePending()) return BackupNowRequest.RESTORE_IN_PROGRESS
        val current = facts() ?: return BackupNowRequest.UNAVAILABLE
        val kind = current.connectedKind ?: return BackupNowRequest.NOT_CONNECTED
        if (!policyAccepted()) return BackupNowRequest.POLICY_PENDING
        if (queue.uploadWork() == BackupUploadWork.RUNNING) return BackupNowRequest.ALREADY_RUNNING
        queue.replaceUpload(needsNetwork = needsNetwork(kind), kind = RunKind.BACKUP_NOW)
        return BackupNowRequest.QUEUED
    }

    /**
     * Cambió la política con datos móviles: la subida con cualquier red tenía el retraso de la
     * anterior, y se vuelve a encolar con la nueva.
     */
    suspend fun onMeteredPolicyChanged() {
        if (restorePending()) return
        queue.cancel(BackupWorkNames.UPLOAD)
        val current = uploadable() ?: return
        enqueueUpload(current, pastInterval = false)
    }

    /**
     * La persona aceptó la versión vigente de la política: lo que quedó pendiente durante la pausa
     * se encola, como al conectar, y la sonda única de la cabeza contrasta el destino.
     */
    suspend fun onPolicyAccepted() {
        if (paused()) return
        // Durante la pausa nadie miró el destino: puede tener algo más nuevo.
        probeOnce()
        val current = uploadable() ?: return
        enqueueUpload(current, pastInterval = false)
    }

    /**
     * Terminó una ejecución del trabajo [workName]. Una copia comprobada cancela la otra subida: en
     * una racha siempre queda algo pendiente, y la otra subiría otra copia en seguida. Si quedaron
     * cambios, vuelve a empezar la espera, salvo tras una copia que la app no podría restaurar, que
     * se repetiría igual. Si la compuerta detuvo la ejecución con el intervalo corriendo, la espera
     * vuelve a calcular el retraso al vencer.
     */
    internal suspend fun onRunFinished(run: BackupRun, workName: String?) {
        when (run) {
            is BackupRun.Verified -> {
                cancelOtherUpload(workName)
                if (run.unrestorable || paused()) return
                if (facts()?.hasPending == true) {
                    queue.restartDebounce(BackupTimings.BACKUP_DEBOUNCE)
                }
            }

            is BackupRun.Skipped ->
                when (run.reason) {
                    SkipReason.WAITING_FOR_INTERVAL ->
                        if (!paused()) queue.restartDebounce(BackupTimings.BACKUP_DEBOUNCE)

                    // Con la nube del teléfono, la copia que la app no restauraría no se escribió.
                    SkipReason.UNRESTORABLE_NOT_WRITTEN -> cancelOtherUpload(workName)

                    SkipReason.NOT_CONNECTED,
                    SkipReason.POLICY_PENDING,
                    SkipReason.NOTHING_PENDING,
                    SkipReason.EMPTY_DATABASE,
                    SkipReason.DESTINATION_CHANGED,
                    SkipReason.WAITING_FOR_UNMETERED,
                    -> Unit
                }

            // El sistema la vuelve a correr, o espera a la persona o a un cambio nuevo.
            is BackupRun.Retry, is BackupRun.Stopped -> Unit
        }
    }

    /**
     * El paso 2 de una restauración: cancela la espera y las dos subidas. Lo que corría se detiene
     * en su siguiente revisión.
     */
    internal suspend fun cancelForRestore() {
        BackupWorkNames.ALL.forEach { queue.cancel(it) }
    }

    /**
     * Antes de desconectar: cancela la espera, las dos subidas y la sonda única. Lo que corría se
     * detiene en su siguiente revisión, y el cerrojo del respaldo espera a que salga.
     */
    internal suspend fun cancelForDisconnect() {
        // La copia de la carpeta local sigue: no depende del destino conectado.
        (BackupWorkNames.MAIN + BackupWorkNames.HEAD_PROBE_ONCE).forEach { queue.cancel(it) }
    }

    /**
     * El destino volvió a conectarse, o salió de un conflicto: la sonda única contrasta el destino
     * sin esperar a la semana, y lo pendiente se encola.
     */
    internal suspend fun onLinkRestored() {
        if (paused()) return
        facts()?.connectedKind?.let { queue.keepHeadChecks(needsNetwork(it)) }
        probeOnce()
        val current = uploadable() ?: return
        enqueueUpload(current, pastInterval = false)
    }

    /**
     * Se cambió de destino: la sonda semanal toma la red que pide el destino nuevo, la sonda única
     * lo contrasta y la subida forzada se encola.
     */
    internal suspend fun onDestinationChanged() {
        if (paused()) return
        val kind = facts()?.connectedKind ?: return
        queue.replaceHeadChecks(needsNetwork(kind))
        probeOnce()
        val current = uploadable() ?: return
        enqueueUpload(current, pastInterval = false)
    }

    /** Espera a que ningún trabajo del respaldo figure corriendo. */
    internal suspend fun awaitNoneRunning() {
        queue.awaitNoneRunning(BackupWorkNames.ALL)
    }

    /**
     * Reconcilia el respaldo al arrancar, a diario y tras reemplazar la base: deja programados el
     * mantenimiento diario y, con un destino, la sonda y la comprobación de la cabeza. Si además hay
     * cambios sin copia, un destino conectado y ningún trabajo de subida esperando ni corriendo,
     * encola la subida. También tras un fallo o una copia que la app
     * no podría restaurar: esa no se repite sola, y esta es una de sus salidas.
     *
     * Con una restauración en curso no encola nada: el arranque la termina y vuelve a llamar aquí.
     */
    internal suspend fun reconcile() {
        if (restorePending()) return
        queue.keepDailyMaintenance()
        secondDestination()?.let { second ->
            if (SecondDestinationRule.dailyDue(
                    second.changeSeq,
                    second.lastCopySeq,
                    second.lastCopyAt,
                    clock.now(),
                )
            ) {
                queue.keepSecondDestination()
            }
        }
        facts()?.connectedKind?.let { queue.keepHeadChecks(needsNetwork(it)) }
        val current = uploadable() ?: return
        if (queue.uploadWork() != BackupUploadWork.NONE) return
        enqueueUpload(current, pastInterval = false)
    }

    /**
     * Terminó el trabajo del segundo destino. Con `Mantener al día esta carpeta` y cambios que
     * llegaron mientras escribía, la espera vuelve a empezar, como tras el paso 14.
     */
    internal suspend fun onSecondDestinationFinished() {
        if (restorePending()) return
        val second = secondDestination() ?: return
        if (second.followMainBackup && second.changeSeq != second.lastCopySeq) {
            queue.restartDebounce(BackupTimings.BACKUP_DEBOUNCE)
        }
    }

    /**
     * Con `Mantener al día esta carpeta`, una racha larga también escribe en la carpeta tras
     * [BackupTimings.BACKUP_MAX_WAIT], como la subida.
     */
    private suspend fun followStreak() {
        val second = secondDestination()?.takeIf { it.followMainBackup } ?: return
        if (SecondDestinationRule.followMaxWaitReached(
                second.changeSeq,
                second.lastCopySeq,
                second.lastCopyAt,
                clock.now(),
            )
        ) {
            queue.keepSecondDestination()
        }
    }

    /**
     * Venció la espera: con `Mantener al día esta carpeta`, sin destino conectado o con la copia
     * principal esperando red, la carpeta no espera. Con red, la escribe el paso 9b.
     */
    private suspend fun followWithoutUpload() {
        val second = secondDestination()?.takeIf { it.followMainBackup } ?: return
        if (second.changeSeq == second.lastCopySeq) return
        val connected = facts()?.connectedKind != null
        if (!connected || mainWaitingForNetwork()) queue.keepSecondDestination()
    }

    /** La restauración se canceló: lo que quedó pendiente vuelve a encolarse. */
    internal suspend fun resumeAfterRestore() {
        if (restorePending()) return
        val current = uploadable() ?: return
        enqueueUpload(current, pastInterval = false)
    }

    /**
     * El estado, si hay un destino conectado, algo que subir y la política aceptada. Todo lo que
     * encola una subida pasa por aquí.
     */
    private suspend fun uploadable(): ScheduleFacts? =
        facts()?.takeIf { it.isUploadable && policyAccepted() }

    /**
     * Si nada del destino se puede encolar ahora: hay una restauración en curso, o falta aceptar la
     * versión vigente de la política.
     */
    private suspend fun paused(): Boolean = restorePending() || !policyAccepted()

    private suspend fun enqueueUpload(current: ScheduleFacts, pastInterval: Boolean) {
        val kind = current.connectedKind ?: return
        val schedule =
            UploadCadence.schedule(
                kind,
                current.policy,
                current.lastUploadFinishedAt,
                clock.now(),
                pastInterval,
            )
        if (schedule.unmetered) queue.keepUnmetered()
        val delay = schedule.anyNetworkDelay ?: return
        if (pastInterval) {
            // La que ya esperaba tiene el retraso del intervalo: se reemplaza, salvo que corra.
            if (queue.uploadWork() == BackupUploadWork.RUNNING) return
            queue.replaceUpload(schedule.anyNetworkNeedsNetwork, RunKind.PAST_INTERVAL)
        } else {
            queue.keepUpload(delay, schedule.anyNetworkNeedsNetwork)
        }
    }

    /** Encola la sonda única de la cabeza, con la red que pida el destino conectado. */
    private suspend fun probeOnce() {
        val kind = facts()?.connectedKind ?: return
        queue.keepHeadProbeOnce(needsNetwork(kind))
    }

    private suspend fun cancelOtherUpload(workName: String?) {
        workName?.let(BackupWorkNames::otherUpload)?.let { queue.cancel(it) }
    }

    private fun needsNetwork(kind: BackupDestinationKind): Boolean =
        kind == BackupDestinationKind.WEBDAV
}

/**
 * Lo que el programador mira del segundo destino.
 *
 * @property followMainBackup `Mantener al día esta carpeta`.
 * @property lastCopyAt cuándo terminó la última copia de la carpeta, o nulo si no hubo.
 * @property lastCopySeq el `change_seq` de esa copia, o nulo.
 * @property changeSeq el `change_seq` de ahora.
 */
internal data class SecondDestinationFacts(
    val followMainBackup: Boolean,
    val lastCopyAt: Instant?,
    val lastCopySeq: Long?,
    val changeSeq: Long,
)

/** Lo que pasó con `Respaldar ahora`. */
enum class BackupNowRequest {
    /** La subida quedó encolada: sale en cuanto haya red. */
    QUEUED,

    /** Ya hay una subida corriendo: esa es la copia. */
    ALREADY_RUNNING,

    /** No hay un destino conectado. */
    NOT_CONNECTED,

    /**
     * Falta aceptar la versión vigente de la política y los términos: la pantalla la muestra, y al
     * aceptarla la copia sale.
     */
    POLICY_PENDING,

    /** Hay una restauración en curso: la copia sale cuando termine. */
    RESTORE_IN_PROGRESS,

    /** La base todavía no se puede leer, como con una migración pendiente. */
    UNAVAILABLE,
}

/** Por qué corre una subida: lo lleva el trabajo, y el motor decide con eso su compuerta. */
internal enum class RunKind {
    /** Una copia automática, con la compuerta de red. */
    AUTOMATIC,

    /** Una copia automática que ya lleva un día: sale sin esperar el intervalo. */
    PAST_INTERVAL,

    /** La pidió la persona: sale con cualquier red y aunque la cuota diga que no cabe. */
    BACKUP_NOW,
}

/**
 * Lo que el programador mira del estado guardado. No incluye la red: la mira cada trabajo al
 * empezar, porque puede cambiar en un instante.
 *
 * @property connectedKind el destino conectado, o nulo si no hay uno al que subir.
 * @property hasPending hay cambios sin copia, o una subida a medias.
 * @property firstUnsyncedAt el cambio más viejo sin copia, o nulo.
 * @property lastUploadFinishedAt cuándo se envió el último byte de la última subida, o nulo.
 * @property failureCode el fallo que dejó la última ejecución, tal como está guardado.
 * @property policy cada cuánto se respalda con datos móviles.
 */
internal data class ScheduleFacts(
    val connectedKind: BackupDestinationKind?,
    val hasPending: Boolean,
    val firstUnsyncedAt: Instant?,
    val lastUploadFinishedAt: Instant?,
    val failureCode: String?,
    val policy: MeteredBackupPolicy,
) {
    /** Hay un destino conectado y algo que subir. */
    val isUploadable: Boolean get() = connectedKind != null && hasPending

    companion object {
        /** Lo que dice [row] con la política [policy]. Sin fila, no hay nada que programar. */
        fun of(row: BackupStateEntity?, policy: MeteredBackupPolicy): ScheduleFacts? {
            row ?: return null
            return ScheduleFacts(
                connectedKind = connectedOf(row)?.kind,
                hasPending = row.changeSeq > row.verifiedSeq || row.pendingGeneration != null,
                firstUnsyncedAt = row.firstUnsyncedAt?.let(Instant::fromEpochMilliseconds),
                lastUploadFinishedAt =
                    row.lastUploadFinishedAt?.let(Instant::fromEpochMilliseconds),
                failureCode = row.failureCode,
                policy = policy,
            )
        }
    }
}

/**
 * Los trabajos del respaldo en la biblioteca de trabajos. Cada operación espera a que la biblioteca
 * la registre: así una cancelación va antes de lo que se encola después.
 */
internal interface BackupWorkQueue {
    /** Empieza la espera de [delay], y reemplaza la que hubiera. */
    suspend fun restartDebounce(delay: Duration)

    /** Encola la subida sin datos móviles, salvo que ya haya una encolada o corriendo. */
    suspend fun keepUnmetered()

    /**
     * Encola la subida con cualquier red tras [delay], salvo que ya haya una encolada o corriendo.
     * Sin [needsNetwork], no espera a ninguna red.
     */
    suspend fun keepUpload(delay: Duration, needsNetwork: Boolean)

    /** Reemplaza la subida con cualquier red por una que sale ya, marcada con [kind]. */
    suspend fun replaceUpload(needsNetwork: Boolean, kind: RunKind)

    /** Cancela el trabajo [name], si existe. Uno que corre se detiene en su siguiente revisión. */
    suspend fun cancel(name: String)

    /** Cómo están las dos subidas, vistas juntas. */
    suspend fun uploadWork(): BackupUploadWork

    /** Espera a que ninguno de [names] figure corriendo. */
    suspend fun awaitNoneRunning(names: List<String>)

    /**
     * Deja programado el mantenimiento diario, salvo que ya lo esté: conservar el que existe no
     * mueve su próxima ejecución.
     */
    suspend fun keepDailyMaintenance() {}

    /**
     * Deja programadas la sonda semanal y la comprobación mensual de la cabeza, salvo que ya lo
     * estén. Sin [needsNetwork], la sonda no espera a ninguna red, como con la nube del teléfono.
     */
    suspend fun keepHeadChecks(needsNetwork: Boolean) {}

    /** Encola la sonda única de la cabeza, salvo que ya haya una encolada o corriendo. */
    suspend fun keepHeadProbeOnce(needsNetwork: Boolean) {}

    /**
     * Como [keepHeadChecks], pero la sonda que ya estaba toma la red [needsNetwork]: tras cambiar
     * de destino, la del anterior podría esperar a una red que el nuevo no necesita.
     */
    suspend fun replaceHeadChecks(needsNetwork: Boolean) = keepHeadChecks(needsNetwork)

    /** Encola la copia del segundo destino, sin red, si no espera ni corre otra. */
    suspend fun keepSecondDestination() {}

    companion object {
        /**
         * Sin biblioteca de trabajos: no programa nada. Lo usan las capas de datos que se arman a
         * mano en las pruebas, que corren el respaldo por su cuenta.
         */
        val Detached: BackupWorkQueue =
            object : BackupWorkQueue {
                override suspend fun restartDebounce(delay: Duration) = Unit

                override suspend fun keepUnmetered() = Unit

                override suspend fun keepUpload(delay: Duration, needsNetwork: Boolean) = Unit

                override suspend fun replaceUpload(needsNetwork: Boolean, kind: RunKind) = Unit

                override suspend fun cancel(name: String) = Unit

                override suspend fun uploadWork(): BackupUploadWork = BackupUploadWork.NONE

                override suspend fun awaitNoneRunning(names: List<String>) = Unit
            }
    }
}
