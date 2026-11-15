package io.github.yesidrobayo.shopledger.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.di.AppEvent
import io.github.yesidrobayo.shopledger.domain.model.report.DailySummarySchedule
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.rule.report.DailySummaryRules
import io.github.yesidrobayo.shopledger.domain.usecase.report.BuildDailySummaryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveDailySummaryScheduleUseCase
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// El resumen del día lo lanza el propio teléfono con un trabajo de una sola vez, programado a la
// hora elegida en la zona del negocio. Sin red ni servidor: puede retrasarse, y al correr programa
// el del día siguiente. Nula la hora, no queda nada programado.

/**
 * Programa o cancela el trabajo del resumen del día. Un solo trabajo con nombre: programar otra
 * hora reemplaza la anterior.
 */
class DailySummaryScheduler(private val app: Context) {
    /** Programa [schedule] desde [now], en lugar de lo que hubiera, o lo cancela si es nulo. */
    fun sync(schedule: DailySummarySchedule?, now: Instant) {
        enqueue(schedule, now, ExistingWorkPolicy.REPLACE)
    }

    /**
     * Programa el siguiente desde dentro del trabajo que está corriendo: se encola detrás de él,
     * para no cancelarlo.
     */
    fun scheduleNext(schedule: DailySummarySchedule?, now: Instant) {
        enqueue(schedule, now, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueue(
        schedule: DailySummarySchedule?,
        now: Instant,
        policy: ExistingWorkPolicy,
    ) {
        val work = WorkManager.getInstance(app)
        if (schedule == null) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = (DailySummaryRules.nextRun(now, schedule) - now).inWholeMilliseconds
        val request =
            OneTimeWorkRequestBuilder<DailySummaryWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
        work.enqueueUniqueWork(WORK_NAME, policy, request)
    }

    companion object {
        /** El nombre del trabajo, en inglés y para siempre. */
        const val WORK_NAME = "daily-summary"
    }
}

/**
 * Sigue la hora del resumen mientras la base está abierta: al abrir la app y con cada cambio de la
 * hora o de la zona, programa el trabajo o lo cancela. Así también vuelve a quedar programado tras
 * restaurar una copia.
 *
 * Una falla al programar se registra por su clase y no detiene a las siguientes.
 *
 * @param scope el alcance de la aplicación: sigue mientras viva el proceso.
 */
class DailySummaryDelivery(
    private val scope: CoroutineScope,
    private val scheduler: DailySummaryScheduler,
    private val clock: Clock,
    private val log: EventLog,
) {
    private val started = AtomicBoolean(false)

    /** Empieza a seguir los ajustes de [ports]. Solo la primera llamada cuenta. */
    fun follow(ports: DataPorts) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            ObserveDailySummaryScheduleUseCase(ports.settings)().collect { schedule ->
                log.contain(AppEvent.DAILY_SUMMARY_FAILED) {
                    scheduler.sync(schedule, clock.now())
                }
            }
        }
    }
}

/**
 * El trabajo que muestra el resumen del día y programa el siguiente. Lo arma la fábrica por
 * defecto de la biblioteca de trabajos: toma lo que necesita del contenedor de la app.
 *
 * - **Termina siempre** (`success`): si la base no abre sin riesgo, o el resumen se apagó, no
 *   muestra nada; el siguiente se programa igual si sigue encendido.
 * - **Ninguna excepción sale con su mensaje:** todo corre dentro de [EventLog.contain], y una
 *   falla queda en el registro solo con su clase.
 * - **Siempre la base del negocio:** abre como todo trabajo, nunca la base de ejemplo de una
 *   demostración, y nunca migra. Si la base espera una migración, no muestra nada: la app la
 *   programa otra vez al abrir.
 */
class DailySummaryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ShopLedgerApp).container
        container.log.contain(AppEvent.DAILY_SUMMARY_FAILED) {
            val access = container.data.openForWork()
            if (access is DataAccess.Ready) {
                val ports = access.ports
                val clock = container.clock
                BuildDailySummaryUseCase(ports.settings, ports.periodReports, clock)()
                    ?.let { summary ->
                        AndroidDailySummaryNotifier(applicationContext, container.localizedContext)
                            .show(summary, ports.settings.get().regional)
                    }
                DailySummaryScheduler(applicationContext).scheduleNext(
                    DailySummaryRules.scheduleOf(ports.settings.get()),
                    clock.now(),
                )
            }
        }
        return Result.success()
    }
}
