package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.BackupNotification
import io.github.yesidrobayo.shopledger.data.BackupNotifier
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import io.github.yesidrobayo.shopledger.domain.rule.backup.BackupStatusResolver
import io.github.yesidrobayo.shopledger.domain.rule.backup.StaleNotificationRule
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * El mantenimiento diario del respaldo, que corre una vez al día en segundo plano. **Nunca usa la
 * red:** una ejecución diaria que solo mira el teléfono no es un sondeo, y lo que hay que contrastar
 * con el destino lo hace la sonda de la cabeza, aparte.
 *
 * En cada ejecución, en este orden:
 *
 * 1. **Verifica la base activa.** Corre las consultas de invariantes que revisan cada copia y, si
 *    algún saldo no coincide con su libro, deja cuántos elementos y el fallo de invariantes a la
 *    vista. La app no corrige sola: la salida es restaurar la cabeza, que recalcula con
 *    confirmación.
 * 2. **Limpia lo local vencido:** las copias de seguridad de una restauración y las bases apartadas
 *    sin copia, a los 30 días, nunca con un reloj atrasado.
 * 3. **Reconcilia el respaldo:** si quedaron cambios sin copia y ningún trabajo de subida, encola la
 *    subida, también tras un fallo. Es lo que retoma una copia que no se pudo restaurar, que no se
 *    repite sola.
 * 4. **Avisa un día sin copia** con una notificación local, como mucho una cada 24 horas, y nunca
 *    sin destino conectado: esa persona ya tiene su aviso permanente.
 * 5. **Avisa la revisión pendiente** con la nube del teléfono, una vez en cada mantenimiento, sea
 *    cual sea el estado: entregada no es subida, y solo la persona puede comprobar que la copia
 *    aparece. Una nueva reemplaza a la de ayer.
 * 6. **Pide la prueba del código** con el cifrado activo, cuando toca: a los 7 y 30 días de activar
 *    o cambiar el código, y luego cada 90 desde la última prueba correcta. Sale una vez en cada
 *    mantenimiento mientras toque, sin anotar nada, y nunca bloquea.
 * 7. **Recuerda buscar una versión nueva** cada 60 días, sin red, solo con la búsqueda permitida y
 *    la automática apagada, que ya busca sola.
 *    Solo cuenta el que salió: si el sistema no lo deja salir, se intenta al día siguiente.
 *
 * Los dos primeros pasos van bajo el cerrojo del respaldo, sin esperarlo: con una copia en curso,
 * esa copia ya corre la misma revisión sobre lo que sube, y con una restauración en curso la base y
 * sus copias locales están cambiando. Se quedan para el día siguiente. Una falla en un paso no
 * detiene a los demás: queda en el registro con su clase.
 *
 * Si el estado del respaldo no se reconoce, el aviso de un día sin copia no se pierde: el estado se
 * calcula cada vez que la app se abre, haya corrido o no este trabajo.
 *
 * @param mutex el cerrojo del respaldo, el mismo de cada copia y de cada restauración.
 * @param restorePending si hay una restauración en curso o a medias. Lee el disco.
 * @param open abre la base para un trabajo, sin migrar.
 * @param reconcile encola lo que quedó pendiente.
 * @param notifier lanza las notificaciones del respaldo.
 * @param cleanUp borra lo local vencido en el instante que recibe.
 * @param recoveryCheckDue si toca la prueba periódica del código. Solo lee `recovery/state.json`.
 * @param updateReminderDue si en ese instante toca recordar la búsqueda de versión. No usa la red.
 * @param markUpdateReminder anota que el recordatorio salió en ese instante.
 */
internal class DailyMaintenance(
    private val clock: Clock,
    private val log: EventLog,
    private val mutex: Mutex,
    private val restorePending: suspend () -> Boolean,
    private val open: suspend () -> MaintenanceAccess,
    private val reconcile: suspend () -> Unit,
    private val notifier: () -> BackupNotifier,
    private val cleanUp: suspend (Instant) -> Unit,
    private val recoveryCheckDue: suspend () -> Boolean = { false },
    private val updateReminderDue: suspend (Instant) -> Boolean = { false },
    private val markUpdateReminder: suspend (Instant) -> Unit = {},
) {
    /** Corre el mantenimiento de hoy. */
    suspend fun run(): MaintenanceRun {
        if (restorePending()) return MaintenanceRun.RETRY
        val database = when (val access = open()) {
            is MaintenanceAccess.Ready -> access.database

            // Una migración que espera a la app, o un reemplazo que espera al arranque.
            MaintenanceAccess.Waiting -> return MaintenanceRun.RETRY

            // La base no abre sin riesgo: la app muestra el rescate, y aquí no se toca nada.
            MaintenanceAccess.Unavailable -> return MaintenanceRun.DONE
        }
        val now = clock.now()
        if (mutex.tryLock()) {
            try {
                if (!restorePending()) {
                    log.contain(MaintenanceEvent.INVARIANTS_FAILED) { checkInvariants(database) }
                    log.contain(MaintenanceEvent.CLEANUP_FAILED) { cleanUp(now) }
                }
            } finally {
                mutex.unlock()
            }
        } else {
            log.info(MaintenanceEvent.BACKUP_BUSY)
        }
        log.contain(BackupEvent.SCHEDULING_FAILED) { reconcile() }
        log.contain(MaintenanceEvent.NOTICE_FAILED) {
            val report = database.report(now)
            noticeStale(database, report.status, now)
            if (BackupNotice.HUMAN_CHECK_DUE in report.notices) noticeCheckDue(database)
        }
        log.contain(MaintenanceEvent.NOTICE_FAILED) {
            if (recoveryCheckDue()) noticeRecoveryCheck()
        }
        log.contain(MaintenanceEvent.NOTICE_FAILED) {
            if (updateReminderDue(now)) noticeUpdateCheck(now)
        }
        return MaintenanceRun.DONE
    }

    private suspend fun checkInvariants(database: MaintenanceDatabase) {
        when (val result = database.invariants()) {
            is ActiveInvariants.Checked -> {
                if (result.repairCases > 0) database.recordInvariantFailures(result.repairCases)
                log.info(
                    MaintenanceEvent.INVARIANTS_CHECKED,
                    LogField.count("casos", result.repairCases),
                )
            }

            ActiveInvariants.DatabaseMissing -> log.warn(MaintenanceEvent.DATABASE_MISSING)

            is ActiveInvariants.Failed -> log.warn(MaintenanceEvent.INVARIANTS_FAILED, result.cause)
        }
    }

    private suspend fun noticeStale(
        database: MaintenanceDatabase,
        status: BackupStatus,
        now: Instant,
    ) {
        if (!StaleNotificationRule.isDue(status, database.lastStaleNoticeAt(), now)) return
        // Solo cuenta la que salió: si el sistema no la deja salir, mañana se intenta de nuevo.
        if (notifier().show(BackupNotification.Stale)) {
            database.markStaleNotice(now)
            log.info(MaintenanceEvent.STALE_NOTIFIED)
        } else {
            log.info(MaintenanceEvent.NOTIFICATION_BLOCKED)
        }
    }

    /**
     * La revisión pendiente sale en cada mantenimiento, una vez al día, sin anotar nada: es el
     * mismo aviso hasta que la persona revise. Sin el nombre de la nube, `slots.json` no se pudo
     * leer, y la revisión tampoco podría estar pendiente.
     */
    private suspend fun noticeCheckDue(database: MaintenanceDatabase) {
        val provider = database.providerLabel() ?: return
        if (notifier().show(BackupNotification.CheckDue(provider))) {
            log.info(MaintenanceEvent.CHECK_DUE_NOTIFIED)
        } else {
            log.info(MaintenanceEvent.NOTIFICATION_BLOCKED)
        }
    }

    /** El recordatorio de buscar una versión nueva. Solo se anota el que salió. */
    private suspend fun noticeUpdateCheck(now: Instant) {
        if (notifier().show(BackupNotification.UpdateCheckDue)) {
            markUpdateReminder(now)
            log.info(MaintenanceEvent.UPDATE_REMINDER_NOTIFIED)
        } else {
            log.info(MaintenanceEvent.NOTIFICATION_BLOCKED)
        }
    }

    /** La prueba del código pendiente: el mismo aviso hasta que la persona lo escriba bien. */
    private fun noticeRecoveryCheck() {
        if (notifier().show(BackupNotification.RecoveryCheckDue)) {
            log.info(MaintenanceEvent.RECOVERY_CHECK_NOTIFIED)
        } else {
            log.info(MaintenanceEvent.NOTIFICATION_BLOCKED)
        }
    }
}

/** Cómo terminó un mantenimiento. */
internal enum class MaintenanceRun {
    /** Terminó, o no había nada que hacer: el siguiente es mañana. */
    DONE,

    /** La base todavía no se puede usar: el sistema lo reintenta más tarde. */
    RETRY,
}

/** Lo que el mantenimiento encontró al abrir la base. */
internal sealed interface MaintenanceAccess {
    /** La base está abierta. */
    class Ready(val database: MaintenanceDatabase) : MaintenanceAccess

    /** Una migración o una restauración esperan a la app: hay que volver más tarde. */
    data object Waiting : MaintenanceAccess

    /** La base no abre sin riesgo: el rescate es de la app, no de este trabajo. */
    data object Unavailable : MaintenanceAccess
}

/** Lo que el mantenimiento lee y escribe de la base abierta. */
internal interface MaintenanceDatabase {
    /** Revisa los invariantes de la base activa. */
    suspend fun invariants(): ActiveInvariants

    /** Deja [count] elementos con un saldo que recalcular, y el fallo de invariantes a la vista. */
    suspend fun recordInvariantFailures(count: Long)

    /** El estado del respaldo y sus avisos en [now], con el mismo cálculo que la pantalla. */
    suspend fun report(now: Instant): BackupStatusReport

    /** Con la nube del teléfono, el nombre de esa nube según `slots.json`, o nulo. */
    suspend fun providerLabel(): String?

    /** Cuándo salió la última notificación de un día sin copia, o nulo si nunca. */
    suspend fun lastStaleNoticeAt(): Instant?

    /** Anota que la notificación de un día sin copia salió en [now]. */
    suspend fun markStaleNotice(now: Instant)
}

/**
 * El mantenimiento sobre la base de la app, con sus puertos abiertos.
 *
 * @param providerLabel lee el nombre de la nube del teléfono en `slots.json`.
 */
internal class RoomMaintenanceDatabase(
    private val ports: DataPorts,
    ioContext: CoroutineContext,
    private val providerLabel: suspend () -> String? = { null },
) : MaintenanceDatabase {
    private val check = ActiveInvariantCheck(ports.databaseFile, ioContext)

    override suspend fun invariants(): ActiveInvariants = check.check()

    override suspend fun recordInvariantFailures(count: Long) {
        ports.backupState.recordActiveInvariantFailures(count)
    }

    override suspend fun report(now: Instant): BackupStatusReport =
        BackupStatusResolver.resolve(ports.backupStatus.current(), now)

    override suspend fun providerLabel(): String? = providerLabel.invoke()

    override suspend fun lastStaleNoticeAt(): Instant? =
        ports.backupState.get()?.lastStaleNoticeAt?.let(Instant::fromEpochMilliseconds)

    override suspend fun markStaleNotice(now: Instant) {
        ports.backupState.markStaleNotice(now.toEpochMilliseconds())
    }
}

/**
 * Con la nube del teléfono, el nombre de esa nube según `slots.json` en [noBackupDirectory], de la
 * lista fija de proveedores: las tres ranuras son de la misma. Nulo si no hay ranuras o el archivo
 * no se lee.
 */
internal suspend fun slotProviderLabel(
    noBackupDirectory: File,
    ioContext: CoroutineContext,
): String? = (FileSlotStateStore(noBackupDirectory, ioContext).load() as? SlotStateLoad.Loaded)
    ?.state
    ?.slots
    ?.values
    ?.firstOrNull()
    ?.providerLabel

/**
 * Borra lo local vencido en [now]: las copias de seguridad de una restauración y las bases
 * apartadas sin copia, las dos a los 30 días. Una falla en una no impide la otra.
 */
internal suspend fun cleanUpExpiredLocalCopies(
    files: RestoreFiles,
    clock: Clock,
    log: EventLog,
    ioContext: CoroutineContext,
    now: Instant,
) {
    val ops = DurableFileOps()
    log.contain(MaintenanceEvent.CLEANUP_FAILED) {
        SafetyCopyStore(files, ops, ioContext).pruneExpired(now)
    }
    log.contain(MaintenanceEvent.CLEANUP_FAILED) {
        withContext(ioContext) { DamagedOriginals(files, ops, clock).pruneExpired(now) }
    }
}

/** Los eventos del registro del mantenimiento. Solo llevan conteos y clases. */
internal enum class MaintenanceEvent(override val text: String) : LogEvent {
    INVARIANTS_CHECKED("Verificación diaria de la base terminada"),
    INVARIANTS_FAILED("La verificación diaria no pudo revisar la base"),
    DATABASE_MISSING("La verificación diaria no encontró la base"),
    CLEANUP_FAILED("La limpieza diaria no pudo borrar una copia local vencida"),
    BACKUP_BUSY(
        "Respaldo o restauración en curso: la verificación y la limpieza quedan para mañana",
    ),
    STALE_NOTIFIED("Notificación de un día sin copia lanzada"),
    CHECK_DUE_NOTIFIED("Notificación de revisar la copia en la nube lanzada"),
    RECOVERY_CHECK_NOTIFIED("Notificación de comprobar el código de recuperación lanzada"),
    UPDATE_REMINDER_NOTIFIED("Recordatorio de buscar una versión nueva lanzado"),
    NOTIFICATION_BLOCKED("La notificación del respaldo no puede salir"),
    NOTICE_FAILED("Falla no prevista al avisar un día sin copia"),
    WORKER_FAILED("Falla no prevista del mantenimiento diario"),
}
