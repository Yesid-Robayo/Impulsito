package io.github.yesidrobayo.shopledger.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.rule.surface.QuickSaleWidgetRules
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.home.ObserveTodaySalesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.localization.LocalizedSurface
import io.github.yesidrobayo.shopledger.surface.SurfaceEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Redibuja los widgets puestos cuando cambia algo que muestran.
 *
 * - **Al bloquear y al desbloquear**, desde el receptor de pantalla: el monto se oculta y vuelve.
 * - **Con cada cambio del día o de los ajustes**, mientras la base está abierta: una venta, el modo
 *   discreto, la paleta, el formato regional o el estado del respaldo. Así la paleta nueva llega a
 *   todos los widgets con `updateAll()` en cuanto se elige.
 * - **Al cambiar de idioma**, como cualquier texto que vive fuera de la app.
 *
 * Sin ningún widget puesto no hace nada: ni lee la base ni despierta a Glance.
 */
class QuickSaleWidgetUpdater(
    private val app: Context,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val log: EventLog,
) : LocalizedSurface {
    private val refreshes = MutableStateFlow(0L)
    private val started = AtomicBoolean(false)

    /**
     * Cuántas veces se pidió redibujar en este proceso. Un widget con su sesión abierta vuelve a
     * leer la base cuando cambia; uno sin sesión la lee al abrirla.
     */
    val refreshCount: StateFlow<Long> = refreshes.asStateFlow()

    /** Vuelve a leer y dibujar todos los widgets puestos. */
    suspend fun refresh() {
        val placed = GlanceAppWidgetManager(app).getGlanceIds(QuickSaleWidget::class.java)
        if (placed.isEmpty()) return
        refreshes.update { it + 1 }
        QuickSaleWidget().updateAll(app)
    }

    /**
     * Los textos salen del idioma de la interfaz al dibujar: basta con redibujar. Una falla del
     * widget queda en el registro y no detiene a las demás superficies ni a su marca.
     */
    override suspend fun refresh(texts: Context) {
        log.contain(SurfaceEvent.WIDGET_REFRESH_FAILED) { refresh() }
    }

    /** Empieza a seguir lo que muestra el widget en [ports]. Solo la primera llamada cuenta. */
    fun follow(ports: DataPorts) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // La primera emisión es lo que ya está dibujado: se redibuja desde el primer cambio.
            shownFacts(ports).drop(1).collect {
                log.contain(SurfaceEvent.WIDGET_REFRESH_FAILED) { refresh() }
            }
        }
    }

    // Lo que cambia el dibujo, sin leer el inventario entero en cada cambio: lo que se acaba se
    // vuelve a contar al redibujar.
    private fun shownFacts(ports: DataPorts): Flow<List<Any?>> {
        val calendar = BusinessCalendarSource(ports.settings)
        val today = ObserveTodaySalesUseCase(calendar, ports.salesReports, clock)(flowOf(Unit))
        val backupFailed =
            ObserveBackupOverviewUseCase(ports.backupStatus, clock)(flowOf(Unit)).map {
                QuickSaleWidgetRules.backupFailed(it.report.status)
            }
        val look =
            ports.settings.observe().map {
                val preferences = it.preferences
                listOf(preferences.colorPalette, preferences.discreetModeEnabled, it.regional)
            }
        val products = ports.catalogPresence.observeHasProducts()
        return combine(look, today, products, backupFailed) { settings, day, any, failed ->
            listOf(settings, day, any, failed)
        }.distinctUntilChanged()
    }
}
