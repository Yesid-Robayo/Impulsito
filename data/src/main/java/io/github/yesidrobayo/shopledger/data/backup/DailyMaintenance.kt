package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.BackupNotification
import io.github.yesidrobayo.shopledger.data.BackupNotifier
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import io.github.yesidrobayo.shopledger.domain.rule.backup.BackupStatusResolver
import io.github.yesidrobayo.shopledger.domain.rule.backup.StaleNotificationRule
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
        log.contain(MaintenanceEvent.NOTICE_FAILED) { noticeStale(database, now) }
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

    private suspend fun noticeStale(database: MaintenanceDatabase, now: Instant) {
        val due = StaleNotificationRule.isDue(
            database.status(now),
            database.lastStaleNoticeAt(),
            now,
        )
        if (!due) return
        // Solo cuenta la que salió: si el sistema no la deja salir, mañana se intenta de nuevo.
        if (notifier().show(BackupNotification.STALE)) {
            database.markStaleNotice(now)
            log.info(MaintenanceEvent.STALE_NOTIFIED)
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

    /** El estado del respaldo en [now], con el mismo cálculo que la pantalla. */
    suspend fun status(now: Instant): BackupStatus

    /** Cuándo salió la última notificación de un día sin copia, o nulo si nunca. */
    suspend fun lastStaleNoticeAt(): Instant?

    /** Anota que la notificación de un día sin copia salió en [now]. */
    suspend fun markStaleNotice(now: Instant)
}

/** El mantenimiento sobre la base de la app, con sus puertos abiertos. */
internal class RoomMaintenanceDatabase(private val ports: DataPorts, ioContext: CoroutineContext) :
    MaintenanceDatabase {
    private val check = ActiveInvariantCheck(ports.databaseFile, ioContext)

    override suspend fun invariants(): ActiveInvariants = check.check()

    override suspend fun recordInvariantFailures(count: Long) {
        ports.backupState.recordActiveInvariantFailures(count)
    }

    override suspend fun status(now: Instant): BackupStatus =
        BackupStatusResolver.resolve(ports.backupStatus.current(), now).status

    override suspend fun lastStaleNoticeAt(): Instant? =
        ports.backupState.get()?.lastStaleNoticeAt?.let(Instant::fromEpochMilliseconds)

    override suspend fun markStaleNotice(now: Instant) {
        ports.backupState.markStaleNotice(now.toEpochMilliseconds())
    }
}

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
    NOTIFICATION_BLOCKED("La notificación del respaldo no puede salir"),
    NOTICE_FAILED("Falla no prevista al avisar un día sin copia"),
    WORKER_FAILED("Falla no prevista del mantenimiento diario"),
}
