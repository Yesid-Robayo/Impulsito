package io.github.yesidrobayo.shopledger.widget

import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.model.surface.WidgetFacts
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.Contained
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.rule.surface.QuickSaleWidgetRules
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.home.ObserveTodaySalesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.home.TodaySales
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveInventoryUseCase
import io.github.yesidrobayo.shopledger.surface.SurfaceEvent
import io.github.yesidrobayo.shopledger.ui.theme.DefaultColorPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * Lo que el widget leyó de la base para dibujarse.
 *
 * @property facts el día en conteos, o `null` si la base no está lista.
 * @property palette la paleta elegida, o la predeterminada si la base no abre.
 * @property discreet si el modo discreto está encendido.
 * @property regional el formato regional y la moneda del negocio, para el monto opcional.
 */
internal data class QuickSaleWidgetData(
    val facts: WidgetFacts?,
    val palette: ColorPalette,
    val discreet: Boolean,
    val regional: RegionalSettings?,
) {
    companion object {
        /** La base no está lista: solo `Vender`, con la paleta predeterminada y sin montos. */
        val Unavailable =
            QuickSaleWidgetData(
                facts = null,
                palette = DefaultColorPalette,
                discreet = true,
                regional = null,
            )
    }
}

/**
 * Lee lo que dibuja el widget, solo de `shopledger.db` y por la compuerta de la base: nunca la de
 * la demostración, nunca una migración. Con una migración o una restauración pendiente, la base en
 * rescate o el proceso en modo seguro, devuelve [QuickSaleWidgetData.Unavailable] sin tocar Room.
 *
 * Nunca lanza, salvo la cancelación: una falla queda en el registro con su clase y el widget queda
 * neutro.
 */
internal suspend fun loadQuickSaleWidgetData(app: ShopLedgerApp): QuickSaleWidgetData {
    // En modo seguro la base no se abre: ni la app ni el widget.
    if (app.safeMode) return QuickSaleWidgetData.Unavailable
    val container = app.container
    val outcome =
        container.log.contain(SurfaceEvent.WIDGET_READ_FAILED) {
            when (val access = container.data.openForWork()) {
                is DataAccess.Ready -> readFrom(access.ports, container.clock)
                else -> QuickSaleWidgetData.Unavailable
            }
        }
    return when (outcome) {
        is Contained.Done -> outcome.value
        is Contained.Failed -> QuickSaleWidgetData.Unavailable
    }
}

/** Una sola lectura de cada cosa, con los mismos casos de uso y conteos que Inicio. */
private suspend fun readFrom(ports: DataPorts, clock: Clock): QuickSaleWidgetData {
    val settings = ports.settings.get()
    val todaySales =
        ObserveTodaySalesUseCase(BusinessCalendarSource(ports.settings), ports.salesReports, clock)
    val today = todaySales(flowOf(Unit)).first()
    val inventory = ObserveInventoryUseCase(ports.products, ports.units)().first()
    val backup = ObserveBackupOverviewUseCase(ports.backupStatus, clock)(flowOf(Unit)).first()
    val summary = (today as? TodaySales.Ready)?.summary
    return QuickSaleWidgetData(
        facts =
            WidgetFacts(
                hasProducts = ports.catalogPresence.observeHasProducts().first(),
                salesCount = summary?.salesCount,
                netSales = summary?.netSales,
                lowStockCount = inventory.lowStock.size,
                backupFailed = QuickSaleWidgetRules.backupFailed(backup.report.status),
            ),
        palette = settings.preferences.colorPalette,
        discreet = settings.preferences.discreetModeEnabled,
        regional = settings.regional,
    )
}
