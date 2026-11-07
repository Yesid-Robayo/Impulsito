package io.github.yesidrobayo.shopledger.data.work

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.yesidrobayo.shopledger.data.backup.BackupStatusEnvironment
import io.github.yesidrobayo.shopledger.data.backup.RestoreMarkers
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.safety.PolicyConsentGate
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNetwork
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.first

/**
 * Los nombres únicos de los trabajos de subida. No cambian nunca: el sistema los guarda entre
 * versiones de la app, y un nombre nuevo dejaría huérfano el trabajo que ya estaba encolado.
 */
internal object BackupWorkNames {
    /** La subida con cualquier red, con el intervalo de datos móviles como retraso. */
    const val UPLOAD = "backup-upload"

    /** La subida que solo corre con una red sin datos móviles. */
    const val UPLOAD_UNMETERED = "backup-upload-unmetered"

    /** Los dos, que el estado del respaldo mira juntos. */
    val UPLOADS = listOf(UPLOAD, UPLOAD_UNMETERED)

    /**
     * La espera que junta los cambios seguidos: cada cambio la vuelve a empezar, y al vencer encola
     * la subida. Es un trabajo aparte porque reemplazar una subida que corre la cortaría.
     */
    const val DEBOUNCE = "backup-debounce"

    /** Los tres que una restauración detiene antes de leer el destino. */
    val ALL = listOf(DEBOUNCE, UPLOAD_UNMETERED, UPLOAD)

    /** La otra subida de [name], la que se cancela cuando [name] deja una copia comprobada. */
    fun otherUpload(name: String): String? = when (name) {
        UPLOAD -> UPLOAD_UNMETERED
        UPLOAD_UNMETERED -> UPLOAD
        else -> null
    }
}

/**
 * Lo que el estado del respaldo lee del teléfono en la app: las marcas de una restauración en
 * [noBackupDirectory], la aceptación de la política en [consent], los trabajos de subida, la red y
 * el estado de las ranuras. Aceptar la política vuelve a calcular el estado.
 *
 * Nada de esto usa la red ni espera a ella.
 */
internal fun productionBackupStatusEnvironment(
    context: Context,
    noBackupDirectory: File,
    consent: PolicyConsentGate,
    ioContext: CoroutineContext,
): BackupStatusEnvironment {
    val slots = FileSlotStateStore(noBackupDirectory, ioContext)
    return BackupStatusEnvironment(
        restorePending = { RestoreMarkers(noBackupDirectory).present() },
        policyAccepted = consent::allowsUploads,
        uploadWork = { uploadWorkOf(context) },
        network = { backupNetworkOf(context) },
        firstHandoffAt = { (slots.load() as? SlotStateLoad.Loaded)?.state?.firstHandoffAt },
        changes = consent.changes,
    )
}

/**
 * Cómo están los dos trabajos de subida: corriendo si alguno corre, encolado si alguno espera.
 *
 * Si la biblioteca de trabajos todavía no está lista en este proceso, no hay ninguno que mirar.
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

/** Lo mismo, a partir de los estados de cada trabajo. */
internal fun uploadWorkOf(states: List<WorkInfo.State>): BackupUploadWork = when {
    WorkInfo.State.RUNNING in states -> BackupUploadWork.RUNNING

    states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } ->
        BackupUploadWork.ENQUEUED

    else -> BackupUploadWork.NONE
}

/**
 * La red activa, como la ve el estado del respaldo: si el sistema comprobó que llega a internet, si
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
