package io.github.yesidrobayo.shopledger.data.backup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.yesidrobayo.shopledger.data.WorkAccess
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.domain.model.backup.MetaValueForm
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex

/**
 * El motor de respaldo de la app, armado con sus piezas de producción: la base que abre la
 * compuerta, la carpeta de preparación en `noBackupFilesDir` y el destino que recuerda el estado.
 *
 * Crearlo no toca el disco: cada carpeta se pide al correr, fuera del hilo principal.
 *
 * @param noBackupDirectory da `noBackupFilesDir`, donde viven las marcas de una restauración y la
 *   carpeta de preparación. Pedirla toca el disco: se pide al correr.
 * @param destinations abre el destino conectado.
 * @param deviceIdentity el identificador de la instalación, uno por proceso.
 * @param mutex el cerrojo del respaldo, uno por proceso.
 * @param policyAccepted si la persona aceptó la versión vigente de la política y los términos. Lee
 *   el disco.
 */
internal fun productionBackupEngine(
    context: Context,
    ioContext: CoroutineContext,
    clock: Clock,
    log: EventLog,
    schemaVersion: Int,
    openForWork: suspend () -> WorkAccess,
    noBackupDirectory: () -> File,
    destinations: DestinationOpener,
    deviceIdentity: () -> DeviceIdentityStore,
    mutex: Mutex,
    policyAccepted: suspend () -> Boolean,
): BackupEngine = DatabaseBackupEngine(
    restoreMarkers = { RestoreMarkers(noBackupDirectory()) },
    openForWork = openForWork,
    runnerFor = { ports ->
        val rejected =
            RejectedGenerationStore(
                File(noBackupDirectory(), RejectedGenerationStore.FILE_NAME),
                ioContext,
            )
        val fullVerify =
            FullVerifyGenerationStore(
                File(noBackupDirectory(), FullVerifyGenerationStore.FILE_NAME),
                ioContext,
            )
        val damaged =
            DamagedGenerationStore(
                File(noBackupDirectory(), DamagedGenerationStore.FILE_NAME),
                ioContext,
            )
        BackupRunner(
            state = ports.backupState,
            databaseFile = ports.databaseFile,
            destinations = destinations,
            snapshots = SnapshotCreator(ports.databaseFile, ioContext),
            sealer = SnapshotSealer(ioContext),
            packager = BackupPackager(ioContext),
            staging =
                BackupStaging(File(noBackupDirectory(), BackupStaging.DIRECTORY_NAME), ioContext),
            deviceId = { deviceIdentity().deviceId() },
            clock = clock,
            log = log,
            ioContext = ioContext,
            schemaVersion = schemaVersion,
            appVersion = appVersionOf(context),
            deviceLabel = SlMetaCodec.deviceLabel(Build.MANUFACTURER, Build.MODEL),
            mutex = mutex,
            rejected = rejected,
            fullVerify = fullVerify,
            meteredAllowed = {
                ports.settings.get().preferences.meteredBackupPolicy !=
                    MeteredBackupPolicy.WIFI_ONLY
            },
            policyAccepted = policyAccepted,
            uploadGate =
                PolicyUploadGate(
                    policy = { ports.settings.get().preferences.meteredBackupPolicy },
                    clock = clock,
                ),
            pruner =
                RetentionPruner(
                    deviceId = { deviceIdentity().deviceId() },
                    log = log,
                    marks = {
                        RetentionMarks(
                            rejectedGenerations = rejected.load(),
                            fullVerifyGeneration = fullVerify.load(),
                            damagedGenerations = damaged.load(),
                        )
                    },
                    forceUpload = { ports.backupState.forceUpload(clock.nowEpochMillis()) },
                    forgetDamaged = damaged::removeAll,
                ),
        )
    },
    ioContext = ioContext,
)

/** La versión de la app, si tiene la forma que aceptan los metadatos de una copia. */
private fun appVersionOf(context: Context): String? {
    val manager = context.packageManager
    val info =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
    return info.versionName?.takeIf(MetaValueForm.AppVersion::accepts)
}
