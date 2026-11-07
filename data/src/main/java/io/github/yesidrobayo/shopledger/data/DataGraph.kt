package io.github.yesidrobayo.shopledger.data

import android.content.Context
import androidx.work.WorkerFactory
import io.github.yesidrobayo.shopledger.data.backup.BackupEngine
import io.github.yesidrobayo.shopledger.data.backup.DeviceIdentityStore
import io.github.yesidrobayo.shopledger.data.backup.DurableFileOps
import io.github.yesidrobayo.shopledger.data.backup.RestoreCoordinator
import io.github.yesidrobayo.shopledger.data.backup.RestoreFiles
import io.github.yesidrobayo.shopledger.data.backup.RestoreLockFile
import io.github.yesidrobayo.shopledger.data.backup.RoomRestoredDatabaseVerifier
import io.github.yesidrobayo.shopledger.data.backup.StartupRestore
import io.github.yesidrobayo.shopledger.data.backup.StartupRestoreGate
import io.github.yesidrobayo.shopledger.data.backup.productionBackupEngine
import io.github.yesidrobayo.shopledger.data.backup.productionRestoreCoordinator
import io.github.yesidrobayo.shopledger.data.backup.recordEncryptionChanged
import io.github.yesidrobayo.shopledger.data.destination.HttpClientFactory
import io.github.yesidrobayo.shopledger.data.destination.ProductionDestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.WebDavCredentialStore
import io.github.yesidrobayo.shopledger.data.destination.WebDavDestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.slots.ContentResolverDocumentAccess
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentSlotDestination
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.DatabaseGate
import io.github.yesidrobayo.shopledger.data.local.GateCaller
import io.github.yesidrobayo.shopledger.data.local.GateOutcome
import io.github.yesidrobayo.shopledger.data.local.PreMigrationCopy
import io.github.yesidrobayo.shopledger.data.local.VacuumPreMigrationCopy
import io.github.yesidrobayo.shopledger.data.log.AndroidEventLog
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.data.work.DataWorkerFactory
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.error
import io.github.yesidrobayo.shopledger.domain.port.info
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La entrada pública de la capa de datos: lo único de `:data` que la app construye.
 *
 * Crearlo no toca el disco ni abre la base, así que se puede crear al arrancar el proceso, también
 * cuando el sistema lo arranca solo para un trabajo en segundo plano. La base se abre con [open] o
 * con [openForWork], fuera del hilo principal, y entrega los puertos de `:domain` ya conectados.
 * Las clases de Room quedan dentro de este módulo.
 *
 * **La compuerta del arranque.** Esos dos son el único camino hacia la base, y los dos pasan por
 * [DatabaseGate]: si la app ya creó la base alguna vez y el archivo falta, Room no se abre, porque
 * crearía una base vacía encima del negocio, y la respuesta es el rescate. Si la base es de una
 * versión anterior, la app en primer plano hace la copia previa y migra, y un trabajo espera. Nada
 * fuera de esta clase abre la base, así que la compuerta no tiene otro camino que cerrar.
 *
 * Una sola instancia por proceso: la base abierta vive en ella.
 *
 * @param ioContext contexto de las lecturas y escrituras del disco, fuera del hilo principal.
 * @param files da los archivos de la base. Se piden al abrir, en [ioContext], y no al crear esta
 *   clase: pedir la carpeta de bases toca el disco, porque Android la crea si falta, y crear esta
 *   clase ocurre en el hilo principal al arrancar el proceso.
 * @param schemaVersion la versión del esquema que compila la app. Solo las pruebas la cambian, para
 *   ver una base de una versión anterior sin tener todavía una segunda versión.
 * @param preMigrationCopy deja la copia previa a migrar de esos archivos, en `pre-migration/`.
 */
class DataGraph internal constructor(
    private val context: Context,
    private val ioContext: CoroutineContext,
    private val clock: Clock,
    private val files: () -> DatabaseFiles,
    /** El único punto de registro de la app. */
    val log: EventLog,
    private val schemaVersion: Int = AppDatabase.SCHEMA_VERSION,
    private val preMigrationCopy: (DatabaseFiles) -> PreMigrationCopy = {
        VacuumPreMigrationCopy(it, log)
    },
) {
    private val mutex = Mutex()

    @Volatile private var ready: DataAccess.Ready? = null

    /** El cerrojo del respaldo: una ejecución a la vez en todo el proceso. */
    private val backupMutex = Mutex()

    /** El único cliente HTTP del proceso. Crearlo no abre ninguna conexión. */
    private val http by lazy { HttpClientFactory.newClient() }

    /** La credencial WebDAV sellada. Una sola instancia: su cerrojo vive en ella. */
    private val webDavCredentials by lazy {
        WebDavCredentialStore({ context.noBackupFilesDir }, ioContext)
    }

    /** El identificador de la instalación. Una sola instancia: su caché vive en ella. */
    private val deviceIdentity by lazy {
        DeviceIdentityStore.inNoBackupDir(context, ioContext)
    }

    /** Abre el destino conectado, para el respaldo y para restaurar. Crearlo no abre nada. */
    private val destinations by lazy {
        ProductionDestinationOpener(WebDavDestinationOpener(http, webDavCredentials)) {
            DocumentSlotDestination(
                store = FileSlotStateStore.inNoBackupDir(context, ioContext),
                documents = ContentResolverDocumentAccess(context.contentResolver),
                clock = clock,
                ioContext = ioContext,
            )
        }
    }

    private val restoreMutex = Mutex()
    private var restoreCoordinator: RestoreCoordinator? = null

    /**
     * La preparación de restauraciones, una por proceso. Se pide fuera del hilo principal: pedir los
     * archivos de la base toca el disco. Funciona también sin la base abierta, como en el rescate.
     */
    internal suspend fun restoreCoordinator(): RestoreCoordinator = restoreMutex.withLock {
        restoreCoordinator ?: withContext(ioContext) {
            productionRestoreCoordinator(
                context = context,
                ioContext = ioContext,
                clock = clock,
                log = log,
                files = RestoreFiles(files()),
                ports = { ready?.ports },
                destinations = destinations,
                backupMutex = backupMutex,
            )
        }.also { restoreCoordinator = it }
    }

    /**
     * El motor de respaldo sobre la base de la app y el destino conectado. Corre solo dentro de un
     * trabajo en segundo plano, que pasa por [openForWork] y nunca migra.
     */
    internal val backupEngine: BackupEngine by lazy {
        productionBackupEngine(
            context = context,
            ioContext = ioContext,
            clock = clock,
            log = log,
            schemaVersion = schemaVersion,
            openForWork = ::openForWork,
            noBackupDirectory = { files().noBackupDirectory },
            destinations = destinations,
            deviceIdentity = { deviceIdentity },
            mutex = backupMutex,
        )
    }

    /**
     * Crea los trabajos en segundo plano de esta capa con sus dependencias. La app la entrega a la
     * biblioteca de trabajos al configurarla, y crearla no toca el disco.
     */
    val workerFactory: WorkerFactory by lazy { DataWorkerFactory(backupEngine, log) }

    /** La capa de datos de la app, con su base en la carpeta de bases y el registro de Android. */
    constructor(context: Context, ioContext: CoroutineContext, clock: Clock) : this(
        context.applicationContext,
        ioContext,
        clock,
        { DatabaseFiles.of(context) },
        AndroidEventLog(),
    )

    /**
     * Abre la base para la app en primer plano, una sola vez por proceso, y entrega sus puertos.
     *
     * - **Fuera del hilo principal.** Abrir lee el disco, y puede crear el esquema, copiar la base y
     *   migrarla: corre en [ioContext], y se puede llamar desde el hilo principal sin bloquearlo.
     * - **Una sola vez.** Las llamadas a la vez esperan la misma apertura, también las de los
     *   trabajos, y las siguientes reciben los mismos puertos. Así la compuerta retiene a todos
     *   mientras copia y migra.
     * - **Sin cortes.** Si quien la pidió se cancela, por ejemplo una pantalla que se cierra, la
     *   apertura termina igual y queda lista para la siguiente llamada. Una migración a medias no
     *   se corta.
     * - **Una base que no se puede abrir sin riesgo no se toca.** Devuelve [DataAccess.Rescue], con
     *   la causa en el registro, y de una falla solo su clase. La llamada siguiente lo intenta de
     *   nuevo.
     */
    suspend fun open(): DataAccess = when (val access = enter(GateCaller.APP_IN_FOREGROUND)) {
        is DataAccess.Ready -> access

        is DataAccess.Rescue -> access

        // La app en primer plano es quien migra: la compuerta nunca le pide esperar.
        WorkAccess.MigrationPending -> error("La app en primer plano nunca espera una migración")

        // Tampoco una restauración: es ella quien verifica la base nueva.
        WorkAccess.RestorePending -> error("La app en primer plano nunca espera una restauración")
    }

    /**
     * Abre la base para un trabajo en segundo plano. Es igual que [open], salvo por la migración:
     * un trabajo nunca la corre, porque una falla ahí no tendría pantalla. Si la base es de una
     * versión anterior, devuelve [WorkAccess.MigrationPending] sin abrir Room, y el trabajo
     * devuelve `retry()`. Si la app ya está migrando, espera a que termine y recibe los puertos.
     */
    suspend fun openForWork(): WorkAccess = enter(GateCaller.BACKGROUND_WORK)

    private suspend fun enter(caller: GateCaller): WorkAccess {
        ready?.let { return it }
        return mutex.withLock {
            ready ?: withContext(ioContext + NonCancellable) { openDatabase(caller) }
        }
    }

    private suspend fun openDatabase(caller: GateCaller): WorkAccess {
        val started = TimeSource.Monotonic.markNow()
        // Los archivos se piden aquí, ya en el contexto del disco.
        val databaseFiles =
            try {
                files()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.error(DataEvent.DATABASE_OPEN_FAILED, failure)
                return DataAccess.Rescue(RescueCause.OpenFailed)
            }
        val restoreFiles = RestoreFiles(databaseFiles)
        val operations = DurableFileOps()
        val pendingRestore =
            StartupRestoreGate(StartupRestore(restoreFiles, operations), log) { open ->
                RoomRestoredDatabaseVerifier(open)
            }
        val gate =
            DatabaseGate(
                files = databaseFiles,
                schemaVersion = schemaVersion,
                ioContext = ioContext,
                log = log,
                preMigrationCopy = preMigrationCopy(databaseFiles),
                pendingRestore = pendingRestore,
            ) { file -> AppDatabase.open(context, file, ioContext, clock) }
        return when (val outcome = gate.pass(caller)) {
            is GateOutcome.Opened -> {
                val ports =
                    DataPorts(
                        database = outcome.database,
                        databaseFile = databaseFiles.database,
                        ioContext = ioContext,
                        clock = clock,
                        restoreLock = RestoreLockFile(restoreFiles, operations, clock, ioContext),
                    )
                if (pendingRestore.takeEncryptionChanged()) {
                    recordEncryptionChanged(ports, clock, log)
                }
                val opened = DataAccess.Ready(ports)
                ready = opened
                log.info(
                    DataEvent.DATABASE_OPENED,
                    LogField.durationMillis("duración", started.elapsedNow().inWholeMilliseconds),
                )
                opened
            }

            GateOutcome.MigrationPending -> WorkAccess.MigrationPending

            GateOutcome.RestorePending -> WorkAccess.RestorePending

            is GateOutcome.Rescue -> DataAccess.Rescue(outcome.cause)
        }
    }
}
