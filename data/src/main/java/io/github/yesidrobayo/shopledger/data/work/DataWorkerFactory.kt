package io.github.yesidrobayo.shopledger.data.work

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import io.github.yesidrobayo.shopledger.data.backup.BackupEngine
import io.github.yesidrobayo.shopledger.data.backup.BackupScheduler
import io.github.yesidrobayo.shopledger.domain.port.EventLog

/**
 * Crea los trabajos en segundo plano de la capa de datos con sus dependencias, sin objetos
 * globales: el sistema le da el nombre de la clase, y esta le pasa al trabajo lo que necesita.
 *
 * Un nombre que no conoce lo deja a la fábrica por defecto de la biblioteca.
 *
 * @param backup el motor de respaldo, uno por proceso.
 * @param scheduler programa el respaldo después de cada ejecución y al vencer la espera. Sin él,
 *   como en las pruebas que corren el trabajo de subida solo, la espera queda a la fábrica por
 *   defecto y nada se programa.
 */
internal class DataWorkerFactory(
    private val backup: BackupEngine,
    private val log: EventLog,
    private val scheduler: BackupScheduler? = null,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        BackupWorker::class.java.name ->
            BackupWorker(
                appContext,
                workerParameters,
                backup,
                log,
                { isMetered(appContext) },
                afterRun = { run, name -> scheduler?.onRunFinished(run, name) },
            )

        BackupTriggerWorker::class.java.name ->
            scheduler?.let {
                BackupTriggerWorker(appContext, workerParameters, it::onDebounceElapsed, log)
            }

        else -> null
    }
}

/**
 * Si la red activa es medida, o si no hay ninguna. Una red cuenta como no medida si no se cobra
 * por datos, o si por ahora no se cobra, como algunas redes móviles rápidas desde Android 11.
 *
 * El permiso de leer el estado de la red lo declara la biblioteca de trabajos, que lo necesita para
 * esperar a la red: llega al manifiesto de la app al fusionarlo, y por eso esta capa no lo declara.
 * Si faltara, la red cuenta como medida, que es lo prudente.
 */
@SuppressLint("MissingPermission")
internal fun isMetered(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val capabilities =
        try {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        } catch (_: SecurityException) {
            null
        } ?: return true
    val unmetered =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED,
                    )
                )
    return !unmetered
}
