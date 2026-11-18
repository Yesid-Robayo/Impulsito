package io.github.yesidrobayo.shopledger.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.yesidrobayo.shopledger.BuildConfig
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.notification.AndroidUpdateNotifier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// La búsqueda automática de versión: un trabajo periódico del teléfono, una vez al día y solo con
// red. Es el mismo GET sin datos de `Buscar actualización`. Sin servidor que despierte a la app: el
// sistema decide la hora, y un día sin red simplemente no corre.

/**
 * Programa o cancela la búsqueda automática. Un solo trabajo con nombre, que se encola con
 * `KEEP`: abrir la app no reinicia su cuenta de 24 horas.
 */
class UpdateCheckScheduler(private val app: Context) {
    /** Lo deja programado si [enabled], o lo cancela si no. */
    fun sync(enabled: Boolean) {
        val work = WorkManager.getInstance(app)
        if (!enabled) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        /** El nombre del trabajo, en inglés y para siempre. */
        const val WORK_NAME = "update-check"

        /** Cada cuánto corre. */
        const val PERIOD_HOURS = 24L
    }
}

/**
 * Sigue la búsqueda permitida mientras la base está abierta: al abrir la app y con cada cambio del
 * interruptor `Permitir buscar actualizaciones`, programa el trabajo si también está encendida la
 * automática, o lo cancela. Una falla al programar se registra por su clase.
 *
 * @param scope el alcance de la aplicación: sigue mientras viva el proceso.
 * @param automaticEnabled si la búsqueda automática está encendida en este teléfono.
 */
class UpdateCheckDelivery(
    private val scope: CoroutineScope,
    private val scheduler: UpdateCheckScheduler,
    private val automaticEnabled: suspend () -> Boolean,
    private val log: EventLog,
) {
    private val started = AtomicBoolean(false)

    /** Empieza a seguir los ajustes de [ports]. Solo la primera llamada cuenta. */
    fun follow(ports: DataPorts) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            ports.settings
                .observe()
                .map { it.preferences.updateCheckEnabled }
                .distinctUntilChanged()
                .collect { allowed -> sync(allowed) }
        }
    }

    /**
     * Programa o cancela según [allowed], la búsqueda permitida, y la automática. Lo llama también
     * la pantalla de actualizaciones al cambiar el interruptor de la automática.
     */
    suspend fun sync(allowed: Boolean) {
        log.contain(UpdateCheckEvent.SCHEDULING_FAILED) {
            scheduler.sync(allowed && automaticEnabled())
        }
    }
}

/**
 * El trabajo de la búsqueda automática. Lo arma la fábrica por defecto de la biblioteca de
 * trabajos, que toma lo que necesita del contenedor de la app; las pruebas le pasan su propia
 * búsqueda.
 *
 * **Termina siempre** (`success`), también tras un fallo: un trabajo periódico sigue programado, y
 * la del día siguiente lo intenta otra vez. Nunca pide reintentar antes: un fallo no insiste.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
    private val provided: AutomaticUpdateCheck?,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, null)

    override suspend fun doWork(): Result {
        val check = provided ?: AutomaticUpdateChecks.of(applicationContext as ShopLedgerApp)
        check.run()
        return Result.success()
    }
}

/** Arma la búsqueda automática de la app, con sus textos, su reloj y su registro. */
internal object AutomaticUpdateChecks {
    fun of(app: ShopLedgerApp): AutomaticUpdateCheck {
        val container = app.container
        val data = container.data
        val texts = container.localizedContext
        val notifier = AndroidUpdateNotifier(app, texts)
        return AutomaticUpdateCheck(
            enabled = { searchAllowed(data) && data.automaticUpdateCheckEnabled() },
            check = {
                val resources = texts.context().resources
                data.checkForUpdate(
                    manifestUrl = resources.getString(R.string.update_manifest_url),
                    publicKey = resources.getString(R.string.update_manifest_public_key),
                    builtAt = builtAt(),
                )
            },
            lastNotified = data::lastNotifiedUpdate,
            notify = notifier::show,
            markNotified = data::markUpdateNotified,
            log = container.log,
        )
    }

    /**
     * Si `Permitir buscar actualizaciones` está encendido. Sin la base, no se sabe: no se busca, y
     * el trabajo de mañana lo intenta otra vez.
     */
    private suspend fun searchAllowed(data: DataGraph): Boolean {
        val ports = (data.openForWork() as? DataAccess.Ready)?.ports ?: return false
        return ports.settings.get().preferences.updateCheckEnabled
    }

    /**
     * La fecha de compilación, la del último commit del código, o nula si se compiló sin Git:
     * entonces ningún aviso se descarta por su fecha.
     */
    fun builtAt(): Instant? =
        BuildConfig.SOURCE_DATE_MILLIS.takeIf { it > 0 }?.let(Instant::fromEpochMilliseconds)
}
