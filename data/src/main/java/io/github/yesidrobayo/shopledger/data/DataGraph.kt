package io.github.yesidrobayo.shopledger.data

import android.content.Context
import androidx.work.WorkerFactory
import io.github.yesidrobayo.shopledger.data.backup.BackupCopiesReader
import io.github.yesidrobayo.shopledger.data.backup.BackupEngine
import io.github.yesidrobayo.shopledger.data.backup.BackupEvent
import io.github.yesidrobayo.shopledger.data.backup.BackupScheduler
import io.github.yesidrobayo.shopledger.data.backup.BackupStaging
import io.github.yesidrobayo.shopledger.data.backup.BackupWorkQueue
import io.github.yesidrobayo.shopledger.data.backup.CandidateUnpacker
import io.github.yesidrobayo.shopledger.data.backup.CandidateValidator
import io.github.yesidrobayo.shopledger.data.backup.DailyMaintenance
import io.github.yesidrobayo.shopledger.data.backup.DamagedGenerationStore
import io.github.yesidrobayo.shopledger.data.backup.DeviceIdentityStore
import io.github.yesidrobayo.shopledger.data.backup.DurableFileOps
import io.github.yesidrobayo.shopledger.data.backup.FullVerifyGenerationStore
import io.github.yesidrobayo.shopledger.data.backup.HeadDeepCheck
import io.github.yesidrobayo.shopledger.data.backup.HeadProbe
import io.github.yesidrobayo.shopledger.data.backup.LastProbeStore
import io.github.yesidrobayo.shopledger.data.backup.MaintenanceAccess
import io.github.yesidrobayo.shopledger.data.backup.ProbeAccess
import io.github.yesidrobayo.shopledger.data.backup.RejectedGenerationStore
import io.github.yesidrobayo.shopledger.data.backup.RestoreCoordinator
import io.github.yesidrobayo.shopledger.data.backup.RestoreFiles
import io.github.yesidrobayo.shopledger.data.backup.RestoreLockFile
import io.github.yesidrobayo.shopledger.data.backup.RestoreMarkers
import io.github.yesidrobayo.shopledger.data.backup.RoomMaintenanceDatabase
import io.github.yesidrobayo.shopledger.data.backup.RoomRestoredDatabaseVerifier
import io.github.yesidrobayo.shopledger.data.backup.ScheduleFacts
import io.github.yesidrobayo.shopledger.data.backup.ScheduledBackupWork
import io.github.yesidrobayo.shopledger.data.backup.StartupRestore
import io.github.yesidrobayo.shopledger.data.backup.StartupRestoreGate
import io.github.yesidrobayo.shopledger.data.backup.cleanUpExpiredLocalCopies
import io.github.yesidrobayo.shopledger.data.backup.probeAccess
import io.github.yesidrobayo.shopledger.data.backup.productionBackupEngine
import io.github.yesidrobayo.shopledger.data.backup.productionRestoreCoordinator
import io.github.yesidrobayo.shopledger.data.backup.recordEncryptionChanged
import io.github.yesidrobayo.shopledger.data.backup.slotProviderLabel
import io.github.yesidrobayo.shopledger.data.destination.DestinationLinkStore
import io.github.yesidrobayo.shopledger.data.destination.HttpClientFactory
import io.github.yesidrobayo.shopledger.data.destination.ProductionDestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.WebDavConnectionService
import io.github.yesidrobayo.shopledger.data.destination.WebDavConnector
import io.github.yesidrobayo.shopledger.data.destination.WebDavCredentialStore
import io.github.yesidrobayo.shopledger.data.destination.WebDavDestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.slots.ContentResolverDocumentAccess
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentSlotDestination
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.ResolverSlotFileDocuments
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotFilesSetup
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotPendingStore
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.DatabaseGate
import io.github.yesidrobayo.shopledger.data.local.GateCaller
import io.github.yesidrobayo.shopledger.data.local.GateOutcome
import io.github.yesidrobayo.shopledger.data.local.PreMigrationCopy
import io.github.yesidrobayo.shopledger.data.local.VacuumPreMigrationCopy
import io.github.yesidrobayo.shopledger.data.log.AndroidEventLog
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.data.media.MediaImporter
import io.github.yesidrobayo.shopledger.data.safety.PolicyConsentFile
import io.github.yesidrobayo.shopledger.data.safety.PolicyConsentGate
import io.github.yesidrobayo.shopledger.data.transaction.CommitListener
import io.github.yesidrobayo.shopledger.data.work.DataWorkerFactory
import io.github.yesidrobayo.shopledger.data.work.WorkManagerBackupQueue
import io.github.yesidrobayo.shopledger.data.work.backupNetworkOf
import io.github.yesidrobayo.shopledger.data.work.productionBackupStatusEnvironment
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyVersions
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.ImageImporter
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.PolicyConsentPort
import io.github.yesidrobayo.shopledger.domain.port.SlotFilesSetupPort
import io.github.yesidrobayo.shopledger.domain.port.WebDavConnectionPort
import io.github.yesidrobayo.shopledger.domain.port.error
import io.github.yesidrobayo.shopledger.domain.port.failureHandler
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.rule.backup.HeadProbeRule
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * @param workQueue los trabajos del respaldo en la biblioteca de trabajos. Sin ella, como en las
 *   capas de datos que las pruebas arman a mano, confirmar un cambio no programa nada.
 * @param currentPolicy las versiones de la política y los términos que publica la app. Solo las
 *   pruebas la cambian, para ver qué pasa cuando sube una versión.
 * @param notifier las notificaciones del respaldo, que la interfaz implementa con sus textos. Se
 *   pide al lanzar una, no al crear esta clase.
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
    private val workQueue: ((Context) -> BackupWorkQueue)? = null,
    private val currentPolicy: PolicyVersions = PolicyVersions.CURRENT,
    private val notifier: () -> BackupNotifier = { BackupNotifier.None },
) {
    private val mutex = Mutex()

    @Volatile private var ready: DataAccess.Ready? = null

    /** El cerrojo del respaldo: una ejecución a la vez en todo el proceso. */
    internal val backupMutex = Mutex()

    /** El único cliente HTTP del proceso. Crearlo no abre ninguna conexión. */
    internal val http by lazy { HttpClientFactory.newClient() }

    /** La credencial WebDAV sellada. Una sola instancia: su cerrojo vive en ella. */
    internal val webDavCredentials by lazy {
        WebDavCredentialStore({ context.noBackupFilesDir }, ioContext)
    }

    /**
     * Trae las imágenes que elige la persona, recodificadas y sin EXIF, a `filesDir/media/`. No
     * escribe en la base: el caso de uso anota cada imagen en su transacción.
     */
    val imageImporter: ImageImporter by lazy {
        MediaImporter(
            resolver = context.contentResolver,
            mediaDirectory = { File(context.filesDir, StoredImage.FOLDER) },
            ioContext = ioContext,
        )
    }

    /** El identificador de la instalación. Una sola instancia: su caché vive en ella. */
    private val deviceIdentity by lazy {
        DeviceIdentityStore.inNoBackupDir(context, ioContext)
    }

    /** Abre el destino conectado, para el respaldo y para restaurar. Crearlo no abre nada. */
    internal val destinations by lazy {
        ProductionDestinationOpener(WebDavDestinationOpener(http, webDavCredentials)) {
            DocumentSlotDestination(
                store = FileSlotStateStore.inNoBackupDir(context, ioContext),
                documents = ContentResolverDocumentAccess(context.contentResolver),
                clock = clock,
                ioContext = ioContext,
            )
        }
    }

    /**
     * Lo corto que sigue a una confirmación, como programar la copia. Una falla se registra solo con
     * su clase y no detiene a lo demás.
     */
    private val backgroundScope =
        CoroutineScope(
            SupervisorJob() + ioContext + log.failureHandler(BackupEvent.SCHEDULING_FAILED),
        )

    /** La compuerta de la política, una por proceso. Crearla no toca el disco. */
    private val consentGate by lazy {
        PolicyConsentGate(
            file = PolicyConsentFile({ files().noBackupDirectory }, ioContext),
            current = currentPolicy,
            log = log,
            onAccepted = { backgroundScope.launch { backupScheduler.onPolicyAccepted() } },
        )
    }

    /**
     * La aceptación de la política y los términos en este teléfono. Sin la versión vigente, ninguna
     * copia sale a la nube, y lo demás sigue igual. No necesita la base abierta.
     */
    val policyConsent: PolicyConsentPort get() = consentGate

    /**
     * Programa el respaldo: traduce cada cambio confirmado, la app que se abre o pasa a segundo
     * plano y `Respaldar ahora` a trabajos en segundo plano. Crearlo no toca el disco.
     */
    val backupScheduler: BackupScheduler by lazy {
        BackupScheduler(
            facts = ::scheduleFacts,
            restorePending = {
                withContext(ioContext) { RestoreMarkers(files().noBackupDirectory).present() }
            },
            policyAccepted = consentGate::allowsUploads,
            queue = workQueue?.invoke(context) ?: BackupWorkQueue.Detached,
            clock = clock,
            probeDue = {
                val last = lastProbeStore().load()
                HeadProbeRule.isDue(last?.at, clock.now())
            },
        )
    }

    /**
     * Lo que la pantalla del respaldo lee del destino conectado: su nombre, sin red, y la lista de
     * sus copias. Solo lee. Crearlo no toca el disco.
     */
    val backupDestination: BackupDestinationSource by lazy {
        BackupCopiesReader(
            state = { (open() as? DataAccess.Ready)?.ports?.backupState?.get() },
            destinations = destinations,
            slots = FileSlotStateStore.inNoBackupDir(context, ioContext),
            deviceId = deviceIdentity::deviceId,
            hasNetwork = { backupNetworkOf(context).validated },
        )
    }

    /**
     * La preparación de los tres archivos de la nube del teléfono, para su pantalla. Usa el mismo
     * `slots.json` que el respaldo y su cerrojo. Crearla no toca el disco.
     */
    val slotFilesSetup: SlotFilesSetupPort by lazy {
        SlotFilesSetup(
            open = ::open,
            mutex = backupMutex,
            slots = FileSlotStateStore.inNoBackupDir(context, ioContext),
            pending = SlotPendingStore.inNoBackupDir(context, ioContext),
            picked = ResolverSlotFileDocuments(context.contentResolver),
            documents = ContentResolverDocumentAccess(context.contentResolver),
            links = DestinationLinkStore({ context.noBackupFilesDir }, ioContext),
            afterConfirm = { hasData ->
                // Con datos sale la primera copia sin esperar; sin datos no hay nada que escribir.
                if (hasData) backupScheduler.backupNow() else backupScheduler.onDataChanged()
            },
            clock = clock,
            ioContext = ioContext,
        )
    }

    /**
     * Conectar un servicio WebDAV desde la pantalla: la prueba de conexión, que no guarda nada, y
     * el guardado, bajo el cerrojo del respaldo. Crearlo no toca el disco ni la red.
     */
    val webDavConnection: WebDavConnectionPort by lazy {
        WebDavConnectionService(
            connector =
                WebDavConnector(
                    http,
                    webDavCredentials,
                    DestinationLinkStore({ context.noBackupFilesDir }, ioContext),
                ),
            state = { (open() as? DataAccess.Ready)?.ports?.backupState?.get() },
            slotsLabel = {
                (backupDestination.describe() as? BackupDestinationInfo.Slots)?.providerLabel
            },
            record = { link ->
                val ports = (open() as? DataAccess.Ready)?.ports
                ports?.backupState?.connectDestination(
                    kind = link.kind.name,
                    account = link.account,
                    destinationId = link.destinationId,
                    folder = link.folderUrl,
                    now = link.connectedAtMillis,
                ) == 1
            },
            backupLock = backupMutex,
            onConnected = { backupScheduler.onDataChanged() },
            network = { backupNetworkOf(context).validated },
            nowMillis = { clock.now().toEpochMilliseconds() },
        )
    }

    /** La última sonda de la cabeza, en `no_backup/last-probe`. Pedirla toca el disco. */
    private suspend fun lastProbeStore(): LastProbeStore = withContext(ioContext) {
        LastProbeStore(File(files().noBackupDirectory, LastProbeStore.FILE_NAME), ioContext)
    }

    /** Las precondiciones de la sonda y de la comprobación de la cabeza. */
    private suspend fun headCheckAccess(): ProbeAccess = probeAccess(
        restorePending = {
            withContext(ioContext) { RestoreMarkers(files().noBackupDirectory).present() }
        },
        policyAccepted = consentGate::allowsUploads,
        access = ::openForWork,
    )

    /**
     * La sonda de la cabeza: contrasta el estado con el destino, una vez por semana y al abrir con
     * la última vencida. Crearla no toca el disco.
     */
    internal val headProbe: HeadProbe by lazy {
        HeadProbe(
            prepare = ::headCheckAccess,
            destinations = destinations,
            deviceId = { deviceIdentity.deviceId() },
            schemaVersion = schemaVersion,
            rejected = {
                noBackupStore(RejectedGenerationStore.FILE_NAME, ::RejectedGenerationStore)
            },
            lastProbe = ::lastProbeStore,
            mutex = backupMutex,
            clock = clock,
            log = log,
        )
    }

    /**
     * La comprobación completa de la cabeza, una vez al mes con una red sin datos móviles. Crearla
     * no toca el disco.
     */
    internal val headDeepCheck: HeadDeepCheck by lazy {
        HeadDeepCheck(
            prepare = ::headCheckAccess,
            destinations = destinations,
            stagingDirectory = { File(files().noBackupDirectory, BackupStaging.DIRECTORY_NAME) },
            unpacker = CandidateUnpacker(ioContext),
            validator = CandidateValidator(ioContext),
            fullVerify = {
                noBackupStore(FullVerifyGenerationStore.FILE_NAME, ::FullVerifyGenerationStore)
            },
            damaged = { noBackupStore(DamagedGenerationStore.FILE_NAME, ::DamagedGenerationStore) },
            mutex = backupMutex,
            clock = clock,
            log = log,
            ioContext = ioContext,
        )
    }

    /** Un archivo de la instalación en `noBackupFilesDir`. Pedir la carpeta toca el disco. */
    private suspend fun <T> noBackupStore(name: String, create: (File, CoroutineContext) -> T): T =
        withContext(ioContext) { create(File(files().noBackupDirectory, name), ioContext) }

    /** Lo que la pausa de una restauración hace con los trabajos programados. */
    private val scheduledBackupWork =
        object : ScheduledBackupWork {
            override suspend fun cancel() = backupScheduler.cancelForRestore()

            override suspend fun awaitNoneRunning() = backupScheduler.awaitNoneRunning()

            override fun resume() {
                backgroundScope.launch { backupScheduler.resumeAfterRestore() }
            }
        }

    /** Tras confirmar un cambio, la copia se programa aparte: la escritura no espera por ella. */
    private val commitListener =
        if (workQueue == null) {
            CommitListener {}
        } else {
            CommitListener { backgroundScope.launch { backupScheduler.onDataChanged() } }
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
                scheduledWork = scheduledBackupWork,
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
            policyAccepted = consentGate::allowsUploads,
        )
    }

    /**
     * Crea los trabajos en segundo plano de esta capa con sus dependencias. La app la entrega a la
     * biblioteca de trabajos al configurarla, y crearla no toca el disco.
     */
    val workerFactory: WorkerFactory by lazy {
        DataWorkerFactory(
            backupEngine,
            log,
            backupScheduler,
            dailyMaintenance,
            headProbe to headDeepCheck,
        )
    }

    /**
     * El mantenimiento diario del respaldo: verifica la base, limpia lo local vencido, reconcilia
     * el respaldo y avisa un día sin copia. Crearlo no toca el disco.
     */
    internal val dailyMaintenance: DailyMaintenance by lazy {
        DailyMaintenance(
            clock = clock,
            log = log,
            mutex = backupMutex,
            restorePending = {
                withContext(ioContext) { RestoreMarkers(files().noBackupDirectory).present() }
            },
            open = {
                when (val access = openForWork()) {
                    is DataAccess.Ready ->
                        MaintenanceAccess.Ready(
                            RoomMaintenanceDatabase(access.ports, ioContext) {
                                val directory = withContext(ioContext) { files().noBackupDirectory }
                                slotProviderLabel(directory, ioContext)
                            },
                        )

                    WorkAccess.MigrationPending, WorkAccess.RestorePending ->
                        MaintenanceAccess.Waiting

                    is DataAccess.Rescue -> MaintenanceAccess.Unavailable
                }
            },
            reconcile = backupScheduler::reconcile,
            notifier = notifier,
            cleanUp = { now ->
                val restoreFiles = withContext(ioContext) { RestoreFiles(files()) }
                cleanUpExpiredLocalCopies(restoreFiles, clock, log, ioContext, now)
            },
        )
    }

    /**
     * La capa de datos de la app, con su base en la carpeta de bases, el registro de Android y los
     * trabajos del respaldo en la biblioteca de trabajos del sistema.
     */
    constructor(
        context: Context,
        ioContext: CoroutineContext,
        clock: Clock,
        notifier: () -> BackupNotifier = { BackupNotifier.None },
    ) : this(
        context = context.applicationContext,
        ioContext = ioContext,
        clock = clock,
        files = { DatabaseFiles.of(context) },
        log = AndroidEventLog(),
        workQueue = ::WorkManagerBackupQueue,
        notifier = notifier,
    )

    /**
     * Lo que el programador del respaldo mira del estado guardado, con la compuerta de los trabajos:
     * nulo si la base todavía no se puede abrir sin riesgo, como con una migración pendiente.
     */
    private suspend fun scheduleFacts(): ScheduleFacts? = when (val access = openForWork()) {
        is DataAccess.Ready ->
            ScheduleFacts.of(
                access.ports.backupState.get(),
                access.ports.settings.get().preferences.meteredBackupPolicy,
            )

        WorkAccess.MigrationPending, WorkAccess.RestorePending, is DataAccess.Rescue -> null
    }

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
            StartupRestoreGate(StartupRestore(restoreFiles, operations, clock), log) { open ->
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
                        backupStatusEnvironment =
                            productionBackupStatusEnvironment(
                                context,
                                databaseFiles.noBackupDirectory,
                                consentGate,
                                ioContext,
                                databaseFiles.database,
                            ),
                        commitListener = commitListener,
                    )
                if (pendingRestore.takeEncryptionChanged()) {
                    recordEncryptionChanged(ports, clock, log)
                }
                val opened = DataAccess.Ready(ports)
                ready = opened
                // Al arrancar, y tras reemplazar la base: retoma lo pendiente y deja programado el
                // mantenimiento. Va aparte: la apertura no espera por él.
                if (workQueue != null) backgroundScope.launch { backupScheduler.reconcile() }
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
