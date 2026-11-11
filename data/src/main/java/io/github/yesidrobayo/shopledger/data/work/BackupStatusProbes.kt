package io.github.yesidrobayo.shopledger.data.work

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.yesidrobayo.shopledger.data.backup.BackupStatusEnvironment
import io.github.yesidrobayo.shopledger.data.backup.LastProbeStore
import io.github.yesidrobayo.shopledger.data.backup.RestoreMarkers
import io.github.yesidrobayo.shopledger.data.destination.DestinationLinkStore
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.safety.PolicyConsentGate
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNetwork
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.model.backup.DeviceStorage
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Los nombres Ãºnicos de los trabajos de subida. No cambian nunca: el sistema los guarda entre
 * versiones de la app, y un nombre nuevo dejarÃ­a huÃ©rfano el trabajo que ya estaba encolado.
 */
internal object BackupWorkNames {
    /** La subida con cualquier red, con el intervalo de datos mÃ³viles como retraso. */
    const val UPLOAD = "backup-upload"

    /** La subida que solo corre con una red sin datos mÃ³viles. */
    const val UPLOAD_UNMETERED = "backup-upload-unmetered"

    /** Los dos, que el estado del respaldo mira juntos. */
    val UPLOADS = listOf(UPLOAD, UPLOAD_UNMETERED)

    /**
     * La espera que junta los cambios seguidos: cada cambio la vuelve a empezar, y al vencer encola
     * la subida. Es un trabajo aparte porque reemplazar una subida que corre la cortarÃ­a.
     */
    const val DEBOUNCE = "backup-debounce"

    /**
     * El mantenimiento diario: verifica la base, limpia lo local vencido, reconcilia el respaldo y
     * avisa un dÃ­a sin copia. Una restauraciÃ³n no lo detiene: no escribe en el destino, y su parte
     * local espera al cerrojo del respaldo.
     */
    const val DAILY_MAINTENANCE = "daily-maintenance"

    /** La sonda semanal de la cabeza: que siga en el destino y que nadie subiÃ³ algo mÃ¡s nuevo. */
    const val HEAD_PROBE = "head-probe"

    /** La misma sonda, una vez, al abrir la app con la Ãºltima vencida, al reconectar o al aceptar. */
    const val HEAD_PROBE_ONCE = "head-probe-once"

    /** La comprobaciÃ³n completa de la cabeza, una vez al mes y con una red sin datos mÃ³viles. */
    const val HEAD_DEEP_CHECK = "head-deep-check"

    /** Los tres que una restauraciÃ³n detiene antes de leer el destino. */
    val ALL = listOf(DEBOUNCE, UPLOAD_UNMETERED, UPLOAD)

    /** La otra subida de [name], la que se cancela cuando [name] deja una copia comprobada. */
    fun otherUpload(name: String): String? = when (name) {
        UPLOAD -> UPLOAD_UNMETERED
        UPLOAD_UNMETERED -> UPLOAD
        else -> null
    }
}

/**
 * Lo que el estado del respaldo lee del telÃ©fono en la app: las marcas de una restauraciÃ³n en
 * [noBackupDirectory], la aceptaciÃ³n de la polÃ­tica en [consent], los trabajos de subida, la red,
 * el estado de las ranuras, la hora de la conexiÃ³n en `link.json`, el espacio del servicio en
 * `last-probe` y el espacio libre junto a [databaseFile]. Aceptar la polÃ­tica vuelve a calcular el
 * estado, y tambiÃ©n que una subida empiece o termine: sin eso, una pantalla abierta seguirÃ­a en
 * `UPLOADING` despuÃ©s de la copia, porque la Ãºltima escritura de `backup_state` llega con el trabajo
 * todavÃ­a corriendo.
 *
 * Nada de esto usa la red ni espera a ella.
 */
internal fun productionBackupStatusEnvironment(
    context: Context,
    noBackupDirectory: File,
    consent: PolicyConsentGate,
    ioContext: CoroutineContext,
    databaseFile: File? = null,
): BackupStatusEnvironment {
    val slots = FileSlotStateStore(noBackupDirectory, ioContext)
    val links = DestinationLinkStore({ noBackupDirectory }, ioContext)
    val probes = LastProbeStore(File(noBackupDirectory, LastProbeStore.FILE_NAME), ioContext)
    return BackupStatusEnvironment(
        restorePending = { RestoreMarkers(noBackupDirectory).present() },
        policyAccepted = consent::allowsUploads,
        uploadWork = { uploadWorkOf(context) },
        network = { backupNetworkOf(context) },
        firstHandoffAt = { (slots.load() as? SlotStateLoad.Loaded)?.state?.firstHandoffAt },
        changes = merge(consent.changes, uploadWorkChanges(context)),
        storage = { databaseFile?.let(::deviceStorageOf) },
        connectedAt = { links.read()?.connectedAtMillis },
        serviceSpace = { probes.load()?.serviceSpace },
    )
}

/**
 * El espacio libre donde vive [databaseFile] y lo que ocupa la base con su diario de escritura, que
 * tambiÃ©n entra en la copia. Es lo que mira el aviso de poco espacio cada vez que la app se abre,
 * sin esperar a ningÃºn trabajo. Si el sistema no responde, no hay aviso: nulo.
 */
internal fun deviceStorageOf(databaseFile: File): DeviceStorage? {
    val directory = databaseFile.parentFile ?: return null
    return try {
        val usable = directory.usableSpace
        // Sin la carpeta, el sistema responde 0: eso no es un telÃ©fono lleno, es que no se pudo leer.
        if (usable <= 0L && !directory.isDirectory) return null
        val wal = File(databaseFile.path + WAL_SUFFIX)
        DeviceStorage(usableBytes = usable, databaseBytes = databaseFile.length() + wal.length())
    } catch (_: SecurityException) {
        null
    }
}

/** El diario de escritura de la base, que vive al lado con este sufijo. */
private const val WAL_SUFFIX = "-wal"

/**
 * CÃ³mo estÃ¡n los dos trabajos de subida: corriendo si alguno corre, encolado si alguno espera.
 *
 * Si la biblioteca de trabajos todavÃ­a no estÃ¡ lista en este proceso, no hay ninguno que mirar.
 */
internal suspend fun uploadWorkOf(context: Context): BackupUploadWork {
    val manager =
        try {
            WorkManager.getInstance(context)
        } catch (_: IllegalStateException) {
            return BackupUploadWork.NONE
        }
    val states =
        BackupWorkNames.UPLOADS.flatMap { name ->
            manager.getWorkInfosForUniqueWorkFlow(name).first().map(WorkInfo::state)
        }
    return uploadWorkOf(states)
}

/**
 * Emite cada vez que cambia cÃ³mo estÃ¡n los dos trabajos de subida: encolado, corriendo o ninguno.
 * Repetir el mismo no emite. Si la biblioteca de trabajos todavÃ­a no estÃ¡ lista en este proceso, no
 * hay nada que mirar.
 */
internal fun uploadWorkChanges(context: Context): Flow<Unit> {
    val manager =
        try {
            WorkManager.getInstance(context)
        } catch (_: IllegalStateException) {
            return emptyFlow()
        }
    return uploadWorkChanges(
        BackupWorkNames.UPLOADS.map { name ->
            manager.getWorkInfosForUniqueWorkFlow(name).map { infos -> infos.map(WorkInfo::state) }
        },
    )
}

/** Lo mismo, a partir de los estados de cada trabajo, para probarlo sin la biblioteca. */
internal fun uploadWorkChanges(states: List<Flow<List<WorkInfo.State>>>): Flow<Unit> =
    combine(states) { lists -> uploadWorkOf(lists.flatMap { it }) }
        .distinctUntilChanged()
        .map {}

/** Lo mismo, a partir de los estados de cada trabajo. */
internal fun uploadWorkOf(states: List<WorkInfo.State>): BackupUploadWork = when {
    WorkInfo.State.RUNNING in states -> BackupUploadWork.RUNNING

    states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } ->
        BackupUploadWork.ENQUEUED

    else -> BackupUploadWork.NONE
}

/**
 * La red activa, como la ve el estado del respaldo: si el sistema comprobÃ³ que llega a internet, si
 * cobra por datos y si el ahorro de datos no deja usarla en segundo plano.
 *
 * El permiso de leer el estado de la red llega con la biblioteca de trabajos, como en [isMetered].
 * Si faltara, no hay red que mostrar.
 */
@SuppressLint("MissingPermission")
internal fun backupNetworkOf(context: Context): BackupNetwork {
    val connectivity =
        context.getSystemService(ConnectivityManager::class.java) ?: return BackupNetwork.NONE
    val capabilities =
        try {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        } catch (_: SecurityException) {
            null
        } ?: return BackupNetwork.NONE
    return BackupNetwork(
        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        metered = isMetered(context),
        dataSaverRestricted =
            connectivity.restrictBackgroundStatus ==
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
    )
}
