package io.github.yesidrobayo.shopledger.data

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import androidx.work.WorkerFactory
import io.github.yesidrobayo.shopledger.data.backup.BackupCopiesReader
import io.github.yesidrobayo.shopledger.data.backup.BackupEngine
import io.github.yesidrobayo.shopledger.data.backup.BackupEvent
import io.github.yesidrobayo.shopledger.data.backup.BackupPackager
import io.github.yesidrobayo.shopledger.data.backup.BackupRestoreService
import io.github.yesidrobayo.shopledger.data.backup.BackupScheduler
import io.github.yesidrobayo.shopledger.data.backup.BackupSettingsService
import io.github.yesidrobayo.shopledger.data.backup.BackupStaging
import io.github.yesidrobayo.shopledger.data.backup.BackupWorkQueue
import io.github.yesidrobayo.shopledger.data.backup.CandidateUnpacker
import io.github.yesidrobayo.shopledger.data.backup.CandidateValidator
import io.github.yesidrobayo.shopledger.data.backup.ConflictResolver
import io.github.yesidrobayo.shopledger.data.backup.CustodyRestoreKeys
import io.github.yesidrobayo.shopledger.data.backup.DailyMaintenance
import io.github.yesidrobayo.shopledger.data.backup.DamagedGenerationStore
import io.github.yesidrobayo.shopledger.data.backup.DataHealthReader
import io.github.yesidrobayo.shopledger.data.backup.DeviceIdentityStore
import io.github.yesidrobayo.shopledger.data.backup.DocumentExportSink
import io.github.yesidrobayo.shopledger.data.backup.DocumentTreeFolder
import io.github.yesidrobayo.shopledger.data.backup.DurableFileOps
import io.github.yesidrobayo.shopledger.data.backup.FullCopyExporter
import io.github.yesidrobayo.shopledger.data.backup.FullVerifyGenerationStore
import io.github.yesidrobayo.shopledger.data.backup.HeadDeepCheck
import io.github.yesidrobayo.shopledger.data.backup.HeadProbe
import io.github.yesidrobayo.shopledger.data.backup.HighestSeenGenerationStore
import io.github.yesidrobayo.shopledger.data.backup.LastProbeStore
import io.github.yesidrobayo.shopledger.data.backup.LocalCopyBuilder
import io.github.yesidrobayo.shopledger.data.backup.LocalEncryption
import io.github.yesidrobayo.shopledger.data.backup.MaintenanceAccess
import io.github.yesidrobayo.shopledger.data.backup.PackEncryption
import io.github.yesidrobayo.shopledger.data.backup.PendingPlainUpload
import io.github.yesidrobayo.shopledger.data.backup.ProbeAccess
import io.github.yesidrobayo.shopledger.data.backup.RejectedGenerationStore
import io.github.yesidrobayo.shopledger.data.backup.ResolverPickedFiles
import io.github.yesidrobayo.shopledger.data.backup.RestoreCoordinator
import io.github.yesidrobayo.shopledger.data.backup.RestoreFiles
import io.github.yesidrobayo.shopledger.data.backup.RestoreLockFile
import io.github.yesidrobayo.shopledger.data.backup.RestoreMarkers
import io.github.yesidrobayo.shopledger.data.backup.RoomCandidateMigrator
import io.github.yesidrobayo.shopledger.data.backup.RoomMaintenanceDatabase
import io.github.yesidrobayo.shopledger.data.backup.RoomRestoredDatabaseVerifier
import io.github.yesidrobayo.shopledger.data.backup.SavedDestination
import io.github.yesidrobayo.shopledger.data.backup.ScheduleFacts
import io.github.yesidrobayo.shopledger.data.backup.ScheduledBackupWork
import io.github.yesidrobayo.shopledger.data.backup.SecondDestinationFacts
import io.github.yesidrobayo.shopledger.data.backup.SecondDestinationJob
import io.github.yesidrobayo.shopledger.data.backup.SecondDestinationLoad
import io.github.yesidrobayo.shopledger.data.backup.SecondDestinationService
import io.github.yesidrobayo.shopledger.data.backup.SecondDestinationStore
import io.github.yesidrobayo.shopledger.data.backup.SecondDestinationWriter
import io.github.yesidrobayo.shopledger.data.backup.SnapshotCreator
import io.github.yesidrobayo.shopledger.data.backup.SnapshotSealer
import io.github.yesidrobayo.shopledger.data.backup.SnapshotValidation
import io.github.yesidrobayo.shopledger.data.backup.StartupRestore
import io.github.yesidrobayo.shopledger.data.backup.StartupRestoreGate
import io.github.yesidrobayo.shopledger.data.backup.UploadUsageRecorder
import io.github.yesidrobayo.shopledger.data.backup.UploadUsageStore
import io.github.yesidrobayo.shopledger.data.backup.cleanUpExpiredLocalCopies
import io.github.yesidrobayo.shopledger.data.backup.connectedOf
import io.github.yesidrobayo.shopledger.data.backup.packEncryption
import io.github.yesidrobayo.shopledger.data.backup.probeAccess
import io.github.yesidrobayo.shopledger.data.backup.productionBackupEngine
import io.github.yesidrobayo.shopledger.data.backup.productionRestoreCoordinator
import io.github.yesidrobayo.shopledger.data.backup.recordEncryptionChanged
import io.github.yesidrobayo.shopledger.data.backup.recordFullCopyExported
import io.github.yesidrobayo.shopledger.data.backup.savedDestination
import io.github.yesidrobayo.shopledger.data.backup.slotProviderLabel
import io.github.yesidrobayo.shopledger.data.crypto.EncryptionActivation
import io.github.yesidrobayo.shopledger.data.crypto.EncryptionDeactivation
import io.github.yesidrobayo.shopledger.data.crypto.EncryptionService
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCustody
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeyRead
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryKeyStore
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryState
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryStateStore
import io.github.yesidrobayo.shopledger.data.demo.DemoFiles
import io.github.yesidrobayo.shopledger.data.demo.DemoSession
import io.github.yesidrobayo.shopledger.data.destination.DestinationLinkService
import io.github.yesidrobayo.shopledger.data.destination.DestinationLinkStore
import io.github.yesidrobayo.shopledger.data.destination.DestinationMemory
import io.github.yesidrobayo.shopledger.data.destination.HttpClientFactory
import io.github.yesidrobayo.shopledger.data.destination.ProductionDestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.WebDavConnectionService
import io.github.yesidrobayo.shopledger.data.destination.WebDavConnector
import io.github.yesidrobayo.shopledger.data.destination.WebDavCredentialStore
import io.github.yesidrobayo.shopledger.data.destination.WebDavDestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.factsOf
import io.github.yesidrobayo.shopledger.data.destination.slots.ContentResolverDocumentAccess
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentSlotDestination
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.ResolverSlotFileDocuments
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotFilesSetup
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotPendingStore
import io.github.yesidrobayo.shopledger.data.export.ContentCsvDocument
import io.github.yesidrobayo.shopledger.data.export.CsvExporter
import io.github.yesidrobayo.shopledger.data.export.SummaryPackageWriter
import io.github.yesidrobayo.shopledger.data.export.recordCsvExported
import io.github.yesidrobayo.shopledger.data.imports.DocumentImportReader
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.DatabaseGate
import io.github.yesidrobayo.shopledger.data.local.GateCaller
import io.github.yesidrobayo.shopledger.data.local.GateOutcome
import io.github.yesidrobayo.shopledger.data.local.PreMigrationCopy
import io.github.yesidrobayo.shopledger.data.local.VacuumPreMigrationCopy
import io.github.yesidrobayo.shopledger.data.log.AndroidEventLog
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.data.media.MediaCleanup
import io.github.yesidrobayo.shopledger.data.media.MediaCleanupEvent
import io.github.yesidrobayo.shopledger.data.media.MediaExporter
import io.github.yesidrobayo.shopledger.data.media.MediaImporter
import io.github.yesidrobayo.shopledger.data.media.MediaOpen
import io.github.yesidrobayo.shopledger.data.media.MediaSync
import io.github.yesidrobayo.shopledger.data.media.MediaSyncAccess
import io.github.yesidrobayo.shopledger.data.media.MediaSyncStop
import io.github.yesidrobayo.shopledger.data.media.MediaSyncStore
import io.github.yesidrobayo.shopledger.data.print.BluetoothPrinters
import io.github.yesidrobayo.shopledger.data.print.PrinterSetupFile
import io.github.yesidrobayo.shopledger.data.repository.RoomAuditRecorder
import io.github.yesidrobayo.shopledger.data.repository.toStoredImage
import io.github.yesidrobayo.shopledger.data.rescue.RescueData
import io.github.yesidrobayo.shopledger.data.safety.CloudAccountChecklistFile
import io.github.yesidrobayo.shopledger.data.safety.PolicyConsentFile
import io.github.yesidrobayo.shopledger.data.safety.PolicyConsentGate
import io.github.yesidrobayo.shopledger.data.transaction.CommitListener
import io.github.yesidrobayo.shopledger.data.work.DataWorkerFactory
import io.github.yesidrobayo.shopledger.data.work.DeferredWorkerFactory
import io.github.yesidrobayo.shopledger.data.work.WorkManagerBackupQueue
import io.github.yesidrobayo.shopledger.data.work.backupNetworkOf
import io.github.yesidrobayo.shopledger.data.work.productionBackupStatusEnvironment
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoBackupFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoDestination
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoExport
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyVersions
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.AuditRecorder
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import io.github.yesidrobayo.shopledger.domain.port.BackupLinkPort
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort
import io.github.yesidrobayo.shopledger.domain.port.BackupSettingsPort
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CloudAccountChecklistPort
import io.github.yesidrobayo.shopledger.domain.port.CsvExportPort
import io.github.yesidrobayo.shopledger.domain.port.DataHealthSource
import io.github.yesidrobayo.shopledger.domain.port.EncryptionPort
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.FullCopyExportPort
import io.github.yesidrobayo.shopledger.domain.port.FullVerifyPort
import io.github.yesidrobayo.shopledger.domain.port.ImageImporter
import io.github.yesidrobayo.shopledger.domain.port.ImportFileReader
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.ManagementSummaryPackagePort
import io.github.yesidrobayo.shopledger.domain.port.PhotoBackupPort
import io.github.yesidrobayo.shopledger.domain.port.PolicyConsentPort
import io.github.yesidrobayo.shopledger.domain.port.PrinterSetupStore
import io.github.yesidrobayo.shopledger.domain.port.RestoreDrillPort
import io.github.yesidrobayo.shopledger.domain.port.SafetyCopyPort
import io.github.yesidrobayo.shopledger.domain.port.SecondDestinationPort
import io.github.yesidrobayo.shopledger.domain.port.SlotFilesSetupPort
import io.github.yesidrobayo.shopledger.domain.port.WebDavConnectionPort
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.port.error
import io.github.yesidrobayo.shopledger.domain.port.failureHandler
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import io.github.yesidrobayo.shopledger.domain.rule.backup.BackupStatusResolver
import io.github.yesidrobayo.shopledger.domain.rule.backup.HeadProbeRule
import io.github.yesidrobayo.shopledger.domain.rule.backup.UploadUsageRule
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * @param demoFiles los archivos del modo demostración, siempre aparte de los de la base. Se
 *   piden en [ioContext], como [files].
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
    private val demoFiles: () -> DemoFiles = { DemoFiles(context.noBackupFilesDir) },
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

    /**
     * Lee el archivo que la persona eligió para importar, como entrada no confiable y con sus
     * topes. No escribe en la base ni copia el archivo.
     */
    val importFileReader: ImportFileReader by lazy {
        DocumentImportReader(resolver = context.contentResolver, ioContext = ioContext)
    }

    /**
     * Las impresoras térmicas que la persona ya emparejó por Bluetooth. Crearlas no toca el
     * Bluetooth: se lee al listar o al imprimir, con el permiso ya concedido.
     */
    val printers: BluetoothPrinters by lazy { BluetoothPrinters(context, log, ioContext) }

    /**
     * Las impresoras guardadas en `noBackupFilesDir/printers.json`, con su papel y la de por
     * defecto. Son de esta instalación: no viajan en una copia. Una sola instancia: su caché y su
     * cerrojo viven en ella.
     */
    val printerSetup: PrinterSetupStore by lazy {
        PrinterSetupFile({ File(context.noBackupFilesDir, PrinterSetupFile.FILE_NAME) }, ioContext)
    }

    /**
     * El código de recuperación sellado y el estado del cifrado, en `no_backup/recovery/`. Una sola
     * instancia de cada uno: sus cerrojos viven en ellos. Crearlos no toca el disco ni Keystore.
     */
    internal val recoveryCustody by lazy {
        val directory = { File(context.noBackupFilesDir, RecoveryKeyStore.DIRECTORY) }
        RecoveryCustody(
            keys = RecoveryKeyStore(directory, ioContext),
            state = RecoveryStateStore(directory, ioContext),
            nowUtcMillis = clock::nowEpochMillis,
        )
    }

    /**
     * El cifrado de las copias en su pantalla: el estado y la activación. El código nuevo solo vive
     * en memoria hasta sellarlo. Crearlo no toca el disco ni Keystore.
     */
    val encryption: EncryptionPort by lazy {
        val pending =
            PendingPlainUpload(
                state = { businessPorts()?.backupState },
                staging = {
                    BackupStaging(
                        File(files().noBackupDirectory, BackupStaging.DIRECTORY_NAME),
                        ioContext,
                    )
                },
                rejected = {
                    noBackupStore(RejectedGenerationStore.FILE_NAME, ::RejectedGenerationStore)
                },
                ioContext = ioContext,
            )
        EncryptionService(
            deactivation =
                EncryptionDeactivation(
                    custody = recoveryCustody,
                    backupMutex = backupMutex,
                    recordChanged = {
                        businessPorts()?.let {
                            recordEncryptionChanged(
                                it,
                                clock,
                                log,
                                DataEvent.ENCRYPTION_AUDIT_FAILED,
                            )
                        }
                    },
                    forceUpload = {
                        businessPorts()?.backupState?.forceUpload(
                            clock.nowEpochMillis(),
                        )
                        backupScheduler.onDataChanged()
                    },
                    nowUtcMillis = clock::nowEpochMillis,
                ),
            keyUnavailableFailure = {
                businessPorts()?.backupState?.get()?.failureCode ==
                    BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE.name
            },
            custody = recoveryCustody,
            activation =
                EncryptionActivation(
                    custody = recoveryCustody,
                    backupMutex = backupMutex,
                    discardPendingPlainUpload = { pending.discard() },
                    forceUpload = {
                        businessPorts()?.backupState?.forceUpload(
                            clock.nowEpochMillis(),
                        )
                        backupScheduler.onDataChanged()
                    },
                    nowUtcMillis = clock::nowEpochMillis,
                    ioContext = ioContext,
                ),
        )
    }

    /** El identificador de la instalación. Una sola instancia: su caché vive en ella. */
    private val deviceIdentity by lazy {
        DeviceIdentityStore.inNoBackupDir(context, ioContext)
    }

    /** Los bytes subidos en el mes, en `no_backup/upload-usage.json`. Crearlo no toca el disco. */
    private val uploadUsage by lazy {
        UploadUsageStore({ File(context.noBackupFilesDir, UploadUsageStore.FILE_NAME) }, ioContext)
    }

    /** Abre el destino conectado, para el respaldo y para restaurar. Crearlo no abre nada. */
    internal val destinations by lazy {
        val meter =
            UploadUsageRecorder(
                store = uploadUsage,
                // Con la base ya abierta por el trabajo; sin ella, el mes guardado.
                month = { ready?.ports?.let { businessMonth(it.settings) } },
                onFailure = { log.warn(BackupEvent.UPLOAD_USAGE_FAILED, it) },
            )
        ProductionDestinationOpener(WebDavDestinationOpener(http, webDavCredentials, meter)) {
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
     * Lo que la persona marcó de la cuenta de la nube, en este teléfono. No necesita la base
     * abierta ni usa la red, y no viaja en una copia.
     */
    val cloudAccountChecklist: CloudAccountChecklistPort by lazy {
        CloudAccountChecklistFile({ files().noBackupDirectory }, ioContext)
    }

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
            secondDestination = ::secondDestinationFacts,
            mainWaitingForNetwork = {
                val ports = (openForWork() as? DataAccess.Ready)?.ports
                val status =
                    ports?.backupStatus?.current()?.let {
                        BackupStatusResolver.resolve(it, clock.now()).status
                    }
                status == BackupStatus.WAITING_FOR_NETWORK ||
                    status == BackupStatus.WAITING_FOR_WIFI ||
                    status == BackupStatus.BLOCKED_BY_DATA_SAVER
            },
            mediaOnMetered = {
                val ports = (openForWork() as? DataAccess.Ready)?.ports
                ports?.settings?.get()?.preferences?.mediaOnMetered == true
            },
            mediaWaiting = { mediaSyncWaiting() },
        )
    }

    /**
     * Lo que la pantalla del respaldo lee del destino conectado: su nombre, sin red, y la lista de
     * sus copias. Solo lee. Crearlo no toca el disco.
     */
    val backupDestination: BackupDestinationSource by lazy {
        BackupCopiesReader(
            saved = ::savedInDatabase,
            destinations = destinations,
            slots = FileSlotStateStore.inNoBackupDir(context, ioContext),
            deviceId = deviceIdentity::deviceId,
            hasNetwork = { backupNetworkOf(context).validated },
            highestSeen = highestSeenGeneration,
            encryptionActive = { recoveryCustody.state.read().enabled },
        )
    }

    /** La mayor generación vista en el destino conectado: el ancla del aviso de copias que faltan. */
    private val highestSeenGeneration by lazy {
        HighestSeenGenerationStore({ context.noBackupFilesDir }, ioContext)
    }

    /**
     * Los ajustes del respaldo con WebDAV en su pantalla: el consumo del mes, liberar una copia
     * fijada y volver a programar la subida con otra política. Crearlo no toca el disco.
     */
    val backupSettings: BackupSettingsPort by lazy {
        BackupSettingsService(
            state = { businessPorts()?.backupState?.get() },
            destinations = destinations,
            usage = uploadUsage,
            month = { businessPorts()?.let { businessMonth(it.settings) } },
            backupLock = backupMutex,
            rescheduled = backupScheduler::onMeteredPolicyChanged,
            encryption = ::localEncryption,
        )
    }

    /**
     * Exportar una copia completa al archivo que eligió la persona, con los mismos pasos que una
     * copia del respaldo y sin red. Solo lee la base. Crearlo no toca el disco.
     */
    val fullCopyExport: FullCopyExportPort by lazy {
        FullCopyExporter(
            builder = { businessPorts()?.let(::localCopyBuilder) },
            exportsDirectory = { File(context.cacheDir, FullCopyExporter.DIRECTORY_NAME) },
            sinkFor = { uri -> DocumentExportSink(context.contentResolver, Uri.parse(uri)) },
            recordExport = {
                businessPorts()?.let { recordFullCopyExported(it, clock, log) }
            },
            ioContext = ioContext,
        )
    }

    /**
     * Exportar los datos del negocio a CSV, en el archivo que eligió la persona y sin red. Solo lee
     * la base. Crearlo no toca el disco.
     */
    val csvExport: CsvExportPort by lazy {
        CsvExporter(
            rows = { (open() as? DataAccess.Ready)?.ports?.csvRows },
            documentFor = { uri -> ContentCsvDocument(context.contentResolver, Uri.parse(uri)) },
            recordExport = {
                (open() as? DataAccess.Ready)?.ports?.let { recordCsvExported(it, clock, log) }
            },
            log = log,
            ioContext = ioContext,
        )
    }

    /**
     * El paquete del resumen de gestión: PDF y CSV del período en un `.zip`, en la carpeta de lo
     * que sale de la app y sin red. Solo lee la base. Crearlo no toca el disco.
     */
    val managementSummaryPackage: ManagementSummaryPackagePort by lazy {
        SummaryPackageWriter(
            rows = { (open() as? DataAccess.Ready)?.ports?.csvRows },
            recordExport = {
                (open() as? DataAccess.Ready)?.ports?.let { recordCsvExported(it, clock, log) }
            },
            log = log,
            ioContext = ioContext,
        )
    }

    /** Arma copias fuera del destino, con las piezas del respaldo sobre la base de [ports]. */
    private fun localCopyBuilder(ports: DataPorts): LocalCopyBuilder = LocalCopyBuilder(
        snapshots = SnapshotCreator(ports.databaseFile, ioContext),
        sealer = SnapshotSealer(ioContext),
        validation = SnapshotValidation(CandidateValidator(ioContext, schemaVersion)),
        packager = BackupPackager(ioContext, encryption = ::packEncryption),
        headGeneration = { ports.backupState.get()?.headGeneration ?: 0 },
        deviceId = { deviceIdentity.deviceId() },
        clock = clock,
        schemaVersion = schemaVersion,
    )

    /** El mes de ahora en la zona del negocio, como `AAAA-MM`, o nulo si la zona no se entiende. */
    private suspend fun businessMonth(settings: AppSettingsRepository): String? {
        val calendar =
            (BusinessCalendar.of(settings.get().regional) as? BusinessCalendarResult.Ready)
                ?.calendar ?: return null
        return UploadUsageRule.monthOf(calendar.businessDateOf(clock.now()))
    }

    /**
     * La preparación de los tres archivos de la nube del teléfono, para su pantalla. Usa el mismo
     * `slots.json` que el respaldo y su cerrojo. Crearla no toca el disco.
     */
    val slotFilesSetup: SlotFilesSetupPort by lazy {
        SlotFilesSetup(
            open = ::openBusiness,
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
            change = { state -> destinationLinks.changeToSlots(state) },
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
            state = { businessPorts()?.backupState?.get() },
            slotsLabel = {
                (backupDestination.describe() as? BackupDestinationInfo.Slots)?.providerLabel
            },
            record = { link ->
                val ports = businessPorts()
                ports?.backupState?.connectDestination(
                    kind = link.kind.name,
                    account = link.account,
                    destinationId = link.destinationId,
                    folder = link.folderUrl,
                    now = link.connectedAtMillis,
                ) == 1
            },
            backupLock = backupMutex,
            onConnected = { backupScheduler.onLinkRestored() },
            network = { backupNetworkOf(context).validated },
            nowMillis = { clock.now().toEpochMilliseconds() },
            change = { credential, link -> destinationLinks.changeToWebDav(credential, link) },
        )
    }

    /**
     * El enlace al destino desde la pantalla del respaldo: desconectar, volver a la nube del
     * teléfono tras iniciar sesión y decidir un conflicto, todo bajo el cerrojo del respaldo. Al
     * abrir la base, deja en orden lo que un corte dejó a medias. Crearlo no toca el disco.
     */
    internal val destinationLinks: DestinationLinkService by lazy {
        val slots = FileSlotStateStore.inNoBackupDir(context, ioContext)
        DestinationLinkService(
            open = ::openBusiness,
            mutex = backupMutex,
            credentials = webDavCredentials,
            links = DestinationLinkStore({ context.noBackupFilesDir }, ioContext),
            slots = slots,
            pending = SlotPendingStore.inNoBackupDir(context, ioContext),
            permissions = ResolverSlotFileDocuments(context.contentResolver),
            documents = ContentResolverDocumentAccess(context.contentResolver),
            cancelWork = { backupScheduler.cancelForDisconnect() },
            afterLinkRestored = { backupScheduler.onLinkRestored() },
            conflicts = { dao ->
                ConflictResolver(
                    state = dao,
                    destinations = destinations,
                    deviceId = deviceIdentity::deviceId,
                    clock = clock,
                    schemaVersion = schemaVersion,
                    mutex = backupMutex,
                    slotFile = { ref -> slots.factsOf(ref) },
                )
            },
            clock = clock,
            log = log,
            ioContext = ioContext,
            memory = DestinationMemory({ files().noBackupDirectory }, ioContext),
            afterChange = { backupScheduler.onDestinationChanged() },
        )
    }

    /** Desconectar, volver tras iniciar sesión y decidir un conflicto, para la pantalla. */
    val backupLink: BackupLinkPort get() = destinationLinks

    /**
     * Restaurar desde la pantalla: una copia de la lista del destino conectado o un archivo elegido
     * con el selector, sobre la preparación de restauraciones del proceso. Crearlo no toca el disco.
     */
    val backupRestore: BackupRestorePort by lazy {
        BackupRestoreService(
            saved = ::savedInDatabase,
            destinations = destinations,
            coordinator = ::restoreCoordinator,
            files = ResolverPickedFiles(context.contentResolver),
            beforeUpdate = ::preMigrationCopy,
            ioContext = ioContext,
        )
    }

    /**
     * La copia local de seguridad antes de un cambio delicado que no es una restauración, como
     * corregir la moneda del negocio: la misma que guarda la restauración, con su rotación, sus
     * 30 días y su mismo cerrojo. Crearlo no toca el disco.
     */
    val safetyCopyBeforeChange: SafetyCopyPort by lazy {
        SafetyCopyPort {
            // En la demostración el cambio cae en la base de ejemplo, que se descarta al salir:
            // la base real no cambia y no hace falta copiarla.
            if (demo.isActive()) null else restoreCoordinator().saveSafetyCopyBeforeChange()
        }
    }

    /**
     * Anota en la auditoría de la base abierta lo que pide un caso de uso, como la corrección de
     * la moneda. Sin la base abierta no anota nada, y una falla al anotar nunca detiene nada.
     */
    val auditRecorder: AuditRecorder by lazy {
        // En la demostración se anota en la base de ejemplo: lo que se hace ahí nunca llega a la
        // auditoría del negocio.
        RoomAuditRecorder(
            ports = { if (demo.isActiveNow) demo.currentPorts else ready?.ports },
            clock = clock,
            log = log,
        )
    }

    /**
     * El modo demostración de este proceso: la base de ejemplo que usa la interfaz mientras
     * existe su marca. Nunca se respalda ni se mezcla: los trabajos en segundo plano y el respaldo
     * siguen con la base del negocio. Crearlo no toca el disco.
     */
    val demo: DemoSession by lazy {
        DemoSession(
            files = demoFiles,
            ioContext = ioContext,
            clock = clock,
            log = log,
            schemaVersion = schemaVersion,
            openDatabase = { file -> AppDatabase.open(context, file, ioContext, clock) },
            businessRegion = { businessPorts()?.settings?.get()?.regional },
        )
    }

    /** La copia previa a migrar. Pedir los archivos de la base toca el disco. */
    private suspend fun preMigrationCopy(): File =
        withContext(ioContext) { files().preMigrationCandidate }

    /** El destino conectado según la base abierta. */
    private suspend fun savedInDatabase(): SavedDestination? =
        businessPorts()?.backupState?.get()?.savedDestination()

    /** `link.json`: el destino que se conectó, sin secretos, para cuando la base no se lee. */
    private val links by lazy { DestinationLinkStore({ files().noBackupDirectory }, ioContext) }

    /**
     * Lo que ofrece la pantalla de rescate sin abrir la base: el destino de `link.json` con sus
     * copias, restaurar una de ellas o un archivo elegido, y la base dañada que apartó una
     * restauración. Nada de esto llama a [open]: con una migración que falló, eso la intentaría
     * otra vez. Crearlo no toca el disco.
     */
    val rescue: RescueData by lazy {
        val saved: suspend () -> SavedDestination? = { links.read()?.savedDestination() }
        RescueData(
            destination =
                BackupCopiesReader(
                    saved = saved,
                    destinations = destinations,
                    slots = FileSlotStateStore.inNoBackupDir(context, ioContext),
                    deviceId = deviceIdentity::deviceId,
                    hasNetwork = { backupNetworkOf(context).validated },
                    highestSeen = highestSeenGeneration,
                    encryptionActive = { recoveryCustody.state.read().enabled },
                ),
            restore =
                BackupRestoreService(
                    saved = saved,
                    destinations = destinations,
                    coordinator = ::restoreCoordinator,
                    files = ResolverPickedFiles(context.contentResolver),
                    beforeUpdate = ::preMigrationCopy,
                    ioContext = ioContext,
                ),
            savedKind = { links.read()?.kind },
            restoreFiles = { RestoreFiles(files()) },
            clock = clock,
            ioContext = ioContext,
        )
    }

    private val openingStep = MutableStateFlow(OpeningStep.OPENING)

    /**
     * Lo que hace ahora la compuerta para la app en primer plano: copiar y migrar, o terminar una
     * restauración. La pantalla lo muestra mientras espera la base.
     */
    val opening: StateFlow<OpeningStep> = openingStep.asStateFlow()

    @Volatile private var restoredCodeSaved = false

    /**
     * Si la restauración que terminó al abrir dejó guardado en este teléfono el código de la copia
     * cifrada, para decirlo una vez. Devuelve `true` una sola vez por proceso.
     */
    fun takeRestoredCodeSaved(): Boolean = restoredCodeSaved.also { restoredCodeSaved = false }

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
     * Las fotos en el servicio WebDAV, aparte de las copias y cediéndoles el turno. Crearlo no toca
     * el disco.
     */
    internal val mediaSync: MediaSync by lazy {
        val webDav = WebDavDestinationOpener(http, webDavCredentials)
        MediaSync(
            prepare = ::mediaSyncAccess,
            open = { link ->
                val folder = link.folderUrl
                if (folder == null) {
                    MediaOpen.Unavailable
                } else {
                    webDav.openMedia(link.destinationId, folder)
                }
            },
            store = ::mediaSyncStore,
            mediaDirectory = ::mediaDirectory,
            mutex = backupMutex,
            log = log,
            encryption = ::packEncryption,
            deviceId = { deviceIdentity.deviceId() },
            nowUtcMillis = clock::nowEpochMillis,
        )
    }

    /** La carpeta privada de las fotos. */
    private fun mediaDirectory(): File = File(context.filesDir, StoredImage.FOLDER)

    /** El estado local de `media-sync`, en `noBackupFilesDir`. Pedirlo toca el disco. */
    private suspend fun mediaSyncStore(): MediaSyncStore =
        withContext(ioContext) { MediaSyncStore(files().noBackupDirectory, ioContext) }

    /**
     * Si `media-sync` tiene algo que hacer, sin red: fotos en uso que faltan en el teléfono y no se
     * sabe que el servicio no tenga, fotos en uso sin verificar en el servicio o fotos que falta
     * borrar de él. Así abrir la app tras restaurar baja las fotos sin listar el servicio cada vez.
     */
    private suspend fun mediaSyncWaiting(): Boolean {
        val ports = (openForWork() as? DataAccess.Ready)?.ports ?: return false
        val inUse = ports.mediaAssetRows.findInUse().map { it.sha256 }
        val states = mediaSyncStore()
        if (states.deletions().isNotEmpty()) return true
        if (inUse.isEmpty()) return false
        val verified = states.verified() ?: return true
        val unavailable = states.unavailable()
        val directory = mediaDirectory()
        return withContext(ioContext) {
            inUse.any { sha ->
                val local = File(directory, StoredImage.fileNameOf(sha)).isFile
                if (local) sha !in verified else sha !in unavailable
            }
        }
    }

    /**
     * El respaldo de las fotos para las pantallas: qué falta subir, si se detuvo por el espacio y la
     * exportación aparte. Sin red, y sin cambiar el estado del respaldo. Crearlo no toca el disco.
     */
    val photoBackup: PhotoBackupPort by lazy {
        val exporter =
            MediaExporter(
                photos = {
                    businessPorts()?.mediaAssetRows?.findInUse()
                        ?.map { it.toStoredImage() }
                },
                mediaDirectory = ::mediaDirectory,
                workDirectory = { File(context.cacheDir, FullCopyExporter.DIRECTORY_NAME) },
                sinkFor = { uri -> DocumentExportSink(context.contentResolver, Uri.parse(uri)) },
                encryption = ::packEncryption,
                deviceId = { deviceIdentity.deviceId() },
                nowUtcMillis = clock::nowEpochMillis,
                ioContext = ioContext,
            )
        object : PhotoBackupPort {
            override suspend fun facts(): PhotoBackupFacts? = photoBackupFacts()

            override suspend fun export(documentUri: String): PhotoExport =
                exporter.export(documentUri)
        }
    }

    /** Lee lo que [photoBackup] dice de las fotos. */
    private suspend fun photoBackupFacts(): PhotoBackupFacts? {
        val ports = businessPorts() ?: return null
        val destination =
            when (ports.backupState.get()?.let(::connectedOf)?.kind) {
                BackupDestinationKind.WEBDAV -> PhotoDestination.WEBDAV
                BackupDestinationKind.DOCUMENT_SLOTS -> PhotoDestination.SLOTS
                null -> PhotoDestination.NONE
            }
        val rows = ports.mediaAssetRows.findInUse()
        val states = mediaSyncStore()
        val verified = states.verified().orEmpty()
        val stopped = states.stop() == MediaSyncStop.SERVICE_SPACE
        val directory = mediaDirectory()
        val local =
            withContext(ioContext) {
                rows.filter { File(directory, StoredImage.fileNameOf(it.sha256)).isFile }
            }
        // Pendiente es lo que el servicio no tiene en ninguna forma: una foto en claro que espera
        // su versión cifrada ya está respaldada.
        val pending = local.filter { it.sha256 !in verified }
        val dayAgo = (clock.now() - 1.days).toEpochMilliseconds()
        return PhotoBackupFacts(
            destination = destination,
            pending = pending.size,
            pendingOverADay = pending.count { it.createdAt <= dayAgo },
            stoppedForSpace = stopped,
            totalBytes = rows.sumOf { it.sizeBytes },
            exportable = local.size,
        )
    }

    /**
     * Qué mostrar en lugar de una foto en uso cuyo archivo falta en el teléfono: `true` si todavía
     * puede llegar del servicio WebDAV, y `false` si no hay de dónde, o si el servicio no la tiene.
     * Solo lee archivos y la base, sin red.
     */
    suspend fun photoMayArrive(sha256: String): Boolean {
        val ports = businessPorts() ?: return false
        val link = ports.backupState.get()?.let(::connectedOf) ?: return false
        if (link.kind != BackupDestinationKind.WEBDAV) return false
        return sha256 !in mediaSyncStore().unavailable()
    }

    /** Las precondiciones de `media-sync`: las de la sonda, más las fotos en uso y la red. */
    private suspend fun mediaSyncAccess(): MediaSyncAccess {
        val ready =
            when (val access = headCheckAccess()) {
                is ProbeAccess.Ready -> access
                ProbeAccess.Waiting -> return MediaSyncAccess.Waiting
                ProbeAccess.Nothing -> return MediaSyncAccess.Nothing
            }
        val ports = (openForWork() as? DataAccess.Ready)?.ports ?: return MediaSyncAccess.Waiting
        return MediaSyncAccess.Ready(
            link = ready.link,
            photos = ports.mediaAssetRows.findInUse().map { it.toStoredImage() },
            mediaOnMetered = ports.settings.get().preferences.mediaOnMetered,
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
            migrator = RoomCandidateMigrator(context, ioContext, clock),
            localKey = { CustodyRestoreKeys(recoveryCustody).local() },
        )
    }

    /**
     * Lo que `Salud de mis datos` lee aparte del estado del respaldo. Solo lee, sin red. Crearlo no
     * toca el disco.
     */
    val dataHealth: DataHealthSource by lazy {
        DataHealthReader(
            state = { businessPorts()?.backupState?.get() },
            deviceLocked = {
                context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
            },
            serviceSpaceKnown = { lastProbeStore().load()?.serviceSpace != null },
            recoveryCheck = { recoveryCustody.checkFacts() },
        )
    }

    /**
     * `Probar restauración`: el simulacro completo sobre un temporal, cuando lo pide la persona.
     * Nunca toca la base activa ni las copias. Crearlo no toca el disco.
     */
    val restoreDrill: RestoreDrillPort by lazy {
        RestoreDrillPort { headDeepCheck.runNow(TransferControl.Unbounded) }
    }

    /**
     * `Comprobar ahora` en `Salud de mis datos`: baja la cabeza entera y compara su suma. La única
     * escritura del panel. Crearlo no toca el disco.
     */
    val fullVerify: FullVerifyPort by lazy {
        FullVerifyPort { headDeepCheck.verifyNow(TransferControl.Unbounded) }
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
                keys = CustodyRestoreKeys(recoveryCustody),
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
            secondDestination = ::secondDestinationWriter,
            encryption = ::packEncryption,
            localEncryption = ::localEncryption,
            offHeadKeyId = ::noteOffHeadKeyId,
            currentKeyId = ::currentKeyId,
        )
    }

    /**
     * Cómo sale cada copia: cifrada con el código sellado si el cifrado está activo, en claro si
     * no. Con el cifrado activo y un código que no abre, no sale nada, nunca en claro. El sobre
     * manda sobre el estado, como al arrancar.
     */
    private suspend fun packEncryption(): PackEncryption = recoveryCustody.packEncryption()

    /**
     * La clave vigente para la poda: la del código si el cifrado está activo, o ninguna si está
     * apagado. Una copia con otra clave no se poda hasta comprobar una con la vigente.
     */
    private suspend fun currentKeyId(): String? =
        recoveryCustody.alignAtStartup().let { if (it.enabled) it.keyIdHex else null }

    /**
     * Completa `disabled_key_id` con la clave de la cabeza cifrada si se apagó el cifrado sin código
     * en este teléfono. Corre con el cerrojo del respaldo tomado, y nunca enciende nada.
     */
    private suspend fun noteOffHeadKeyId(keyIdHex: String) {
        if (!RecoveryState.isKeyIdHex(keyIdHex)) return
        try {
            recoveryCustody.state.update { state ->
                val offWithoutKey =
                    !state.enabled &&
                        state.offConfirmedAtUtcMillis != null &&
                        state.disabledKeyIdHex == null
                if (offWithoutKey) state.copy(disabledKeyIdHex = keyIdHex) else state
            }
        } catch (_: IOException) {
            // Se vuelve a intentar en la siguiente lista.
        }
    }

    /** El cifrado de las copias en este teléfono, como lo leen la guarda y el segundo destino. */
    private suspend fun localEncryption(): LocalEncryption {
        val state = recoveryCustody.alignAtStartup()
        val key = recoveryCustody.keys.open()
        return LocalEncryption(
            active = state.enabled,
            keyAvailable = key is RecoveryKeyRead.Available,
            hasRecoveryCode = key !is RecoveryKeyRead.Absent,
            offConfirmed = state.offConfirmedAtUtcMillis != null,
        )
    }

    /**
     * Crea los trabajos en segundo plano de esta capa con sus dependencias. La app la entrega a la
     * biblioteca de trabajos al configurarla, y crearla no toca el disco.
     *
     * La biblioteca la pide en el hilo principal, al arrancar. Las dependencias se arman recién al
     * crear el primer trabajo, en su hilo: el cliente de red del motor de respaldo prepara su
     * cifrado, y eso demoraba el primer cuadro de la app.
     */
    val workerFactory: WorkerFactory by lazy {
        DeferredWorkerFactory {
            DataWorkerFactory(
                backupEngine,
                log,
                backupScheduler,
                dailyMaintenance,
                headProbe to headDeepCheck,
                secondDestinationJob,
                mediaSync,
            )
        }
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
                (openForWork() as? DataAccess.Ready)?.ports?.let { ports ->
                    log.contain(MediaCleanupEvent.FAILED) {
                        MediaCleanup(
                            rows = ports.mediaAssetRows,
                            transactions = ports.transactions,
                            store = ::mediaSyncStore,
                            mediaDirectory = ::mediaDirectory,
                            ioContext = ioContext,
                            log = log,
                        ).run(now)
                    }
                }
            },
            recoveryCheckDue = { recoveryCustody.checkFacts().due },
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

    /** `second-destination.json`, en `noBackupFilesDir`. Crearlo no toca el disco. */
    private val secondDestinationStore by lazy {
        SecondDestinationStore(
            { File(files().noBackupDirectory, SecondDestinationStore.FILE_NAME) },
            ioContext,
        )
    }

    /** El escritor del segundo destino sobre la base de [ports]. */
    private fun secondDestinationWriter(ports: DataPorts): SecondDestinationWriter =
        SecondDestinationWriter(
            store = secondDestinationStore,
            folderFor = { saved ->
                DocumentTreeFolder(
                    context.contentResolver,
                    Uri.parse(saved.treeUri),
                    Uri.parse(saved.folderUri),
                )
            },
            build = { directory -> localCopyBuilder(ports).build(directory) },
            staging = {
                File(
                    File(files().noBackupDirectory, BackupStaging.DIRECTORY_NAME),
                    SECOND_STAGING,
                )
            },
            state = { ports.backupState.get() },
            // El mismo cifrado que mira el motor al armar cada copia.
            encryption = ::localEncryption,
            schemaVersion = schemaVersion,
            deviceId = { deviceIdentity.deviceId() },
            clock = clock,
            ioContext = ioContext,
        )

    /**
     * Elegir, leer y quitar la carpeta del segundo destino, para `Copias locales` y la bienvenida.
     * Crearlo no toca el disco.
     */
    val secondDestination: SecondDestinationPort by lazy {
        SecondDestinationService(
            resolver = context.contentResolver,
            store = secondDestinationStore,
            mutex = backupMutex,
            afterConnect = { backupScheduler.reconcile() },
            ioContext = ioContext,
        )
    }

    /** El trabajo del segundo destino, con la base abierta para un trabajo. */
    private val secondDestinationJob by lazy {
        SecondDestinationJob(
            restorePending = {
                withContext(ioContext) { RestoreMarkers(files().noBackupDirectory).present() }
            },
            writer = {
                when (val access = openForWork()) {
                    is DataAccess.Ready ->
                        SecondDestinationJob.Opened.Ready(secondDestinationWriter(access.ports))

                    WorkAccess.MigrationPending, WorkAccess.RestorePending ->
                        SecondDestinationJob.Opened.Waiting

                    is DataAccess.Rescue -> SecondDestinationJob.Opened.Unavailable
                }
            },
            mutex = backupMutex,
            log = log,
            afterRun = { backupScheduler.onSecondDestinationFinished() },
        )
    }

    /** Lo que el programador mira del segundo destino, o nulo sin carpeta o sin base. */
    private suspend fun secondDestinationFacts(): SecondDestinationFacts? {
        val saved =
            (secondDestinationStore.load() as? SecondDestinationLoad.Loaded)?.state ?: return null
        val row = (openForWork() as? DataAccess.Ready)?.ports?.backupState?.get() ?: return null
        return SecondDestinationFacts(
            followMainBackup = saved.followMainBackup,
            lastCopyAt = saved.lastCopyAt?.let(Instant::fromEpochMilliseconds),
            lastCopySeq = saved.lastCopySeq,
            changeSeq = row.changeSeq,
        )
    }

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
     * - **En la demostración, la base de ejemplo.** Con la marca del modo demostración, la
     *   interfaz recibe los puertos de la base de ejemplo, que nunca se respalda. Si esa base no
     *   abre, se descarta y llega la del negocio. El respaldo y los trabajos nunca pasan por
     *   aquí: usan la base del negocio con [openForWork].
     */
    suspend fun open(): DataAccess {
        if (!demo.isActive()) return openBusiness()
        return demo.open() ?: openBusiness()
    }

    /**
     * La base del negocio para la app en primer plano, con o sin demostración: lo que el
     * respaldo, el destino, el cifrado y las fotos leen desde la interfaz. Igual que [open] sin
     * demostración.
     */
    private suspend fun openBusiness(): DataAccess {
        val access = enter(GateCaller.APP_IN_FOREGROUND)
        return when (access) {
            is DataAccess.Ready -> access

            is DataAccess.Rescue -> access

            // La app en primer plano es quien migra: la compuerta nunca le pide esperar.
            WorkAccess.MigrationPending ->
                error("La app en primer plano nunca espera una migración")

            // Tampoco una restauración: es ella quien verifica la base nueva.
            WorkAccess.RestorePending ->
                error("La app en primer plano nunca espera una restauración")
        }
    }

    /**
     * Abre la base para un trabajo en segundo plano. Es igual que [open], salvo por la migración:
     * un trabajo nunca la corre, porque una falla ahí no tendría pantalla. Si la base es de una
     * versión anterior, devuelve [WorkAccess.MigrationPending] sin abrir Room, y el trabajo
     * devuelve `retry()`. Si la app ya está migrando, espera a que termine y recibe los puertos.
     */
    suspend fun openForWork(): WorkAccess = enter(GateCaller.BACKGROUND_WORK)

    /** Los puertos de la base del negocio, o `null` si no abre sin riesgo. */
    private suspend fun businessPorts(): DataPorts? = (openBusiness() as? DataAccess.Ready)?.ports

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
        val foreground = caller == GateCaller.APP_IN_FOREGROUND
        // Con un diario, la compuerta termina primero el reemplazo de una restauración.
        if (foreground && restoreFiles.journal.exists()) openingStep.value = OpeningStep.RESTORING
        try {
            return passGate(caller, databaseFiles, restoreFiles, started)
        } finally {
            openingStep.value = OpeningStep.OPENING
        }
    }

    private suspend fun passGate(
        caller: GateCaller,
        databaseFiles: DatabaseFiles,
        restoreFiles: RestoreFiles,
        started: TimeSource.Monotonic.ValueTimeMark,
    ): WorkAccess {
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
                // Solo la app en primer plano migra: un trabajo nunca llega aquí.
                onMigrating = { openingStep.value = OpeningStep.MIGRATING },
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
                    restoredCodeSaved = true
                }
                val opened = DataAccess.Ready(ports)
                ready = opened
                // Al arrancar, y tras reemplazar la base: retoma lo pendiente y deja programado el
                // mantenimiento. Va aparte: la apertura no espera por él.
                if (workQueue != null) {
                    backgroundScope.launch {
                        destinationLinks.tidy()
                        backupScheduler.reconcile()
                    }
                }
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

/** La carpeta de preparación del segundo destino, dentro de `backup-staging/`. */
private const val SECOND_STAGING = "second"
