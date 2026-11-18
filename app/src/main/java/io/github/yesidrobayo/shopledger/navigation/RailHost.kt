package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.AppNavigationRail
import io.github.yesidrobayo.shopledger.ui.components.BottomBarItem
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.RailMark

/**
 * Lo que el riel muestra, en orden: las mismas entradas que la barra del teléfono, con `Vender`
 * entre `Inicio` y `Gastos`, y después Negocio. Ajustes va aparte, al pie, y `Decir` arriba.
 * `Registros` no tiene entrada: se abre desde Inicio, y mientras se ve el riel marca Inicio.
 */
private val railKeys: List<AppNavKey> =
    listOf(
        AppNavKey.Home,
        AppNavKey.NewSale(),
        AppNavKey.ExpensesTab,
        AppNavKey.ProductList,
        AppNavKey.Statistics,
        AppNavKey.BusinessHub,
    )

/** Un toque del riel que espera la respuesta a la pregunta de descartar. */
private data class RailRequest(
    val key: AppNavKey,
    val dropped: List<NavKey>,
    val texts: DiscardTexts,
)

/**
 * El riel lateral, si [visible]. Sus entradas se abren con [AppNavigator.openFromRail], y marca lo
 * que está abierto con [AppNavigator.railSection].
 *
 * Si el cambio quitaría de la pila una pantalla con trabajo sin guardar, primero pregunta con el
 * mismo diálogo y los mismos textos de su atrás: descartar sigue, y seguir no cambia nada. Sin
 * trabajo sin guardar, cambia directo.
 *
 * @param say `Decir`: escucha encima de lo visible y luego abre la hoja con lo que recibe, que en
 *   el riel también pregunta antes de quitar trabajo sin guardar.
 * @param stack la pila que dibuja la navegación, la misma de [navigator].
 */
@Composable
internal fun RailHost(
    say: (open: () -> Unit) -> Unit,
    visible: Boolean,
    navigator: AppNavigator,
    stack: List<NavKey>,
    unsavedWork: UnsavedWork,
) {
    if (!visible) return
    var pending by remember { mutableStateOf<RailRequest?>(null) }
    val go: (AppNavKey) -> Unit = { key ->
        val dropped = navigator.railDrops(key)
        val texts = unsavedWork.questionFor(stack, dropped)
        if (texts == null) {
            navigator.openFromRail(key)
        } else {
            pending = RailRequest(key, dropped, texts)
        }
    }
    val items = railKeys.map { key ->
        val tab = TopLevelDestination.ofRoot(key)
        when {
            tab != null -> BottomBarItem(stringResource(tab.label), tab.icon, tab.selectedIcon)

            key is AppNavKey.NewSale ->
                BottomBarItem(
                    stringResource(R.string.nav_sell),
                    R.drawable.ic_sell,
                    R.drawable.ic_sell,
                )

            else ->
                BottomBarItem(
                    stringResource(R.string.hub_title),
                    R.drawable.ic_business_center,
                    R.drawable.ic_business_center,
                )
        }
    }
    AppNavigationRail(
        items = items,
        settings = BottomBarItem(
            stringResource(R.string.settings_title),
            R.drawable.ic_settings,
            R.drawable.ic_settings,
        ),
        selected = railMarkOf(navigator),
        onSelect = { go(railKeys[it]) },
        onSettings = { go(AppNavKey.Settings) },
        sellLabel = stringResource(R.string.phrase_action_say),
        onSell = { say { go(AppNavKey.Phrase()) } },
        sellIcon = R.drawable.ic_mic,
    )
    pending?.let { request ->
        val texts = request.texts
        ConfirmDialog(
            title = texts.title,
            body = texts.body,
            confirmLabel = texts.confirm,
            onConfirm = {
                pending = null
                unsavedWork.discard(request.dropped)
                navigator.openFromRail(request.key)
            },
            onDismiss = { pending = null },
            variant =
                if (texts.destructive) ConfirmVariant.DESTRUCTIVE else ConfirmVariant.STANDARD,
            dismissLabel = texts.dismiss,
        )
    }
}

/**
 * Lo que marca el riel: `Vender` mientras se ve una venta nueva, Ajustes o la sección de
 * [AppNavigator.railSection], y en Registros, Inicio, como la barra.
 */
private fun railMarkOf(navigator: AppNavigator): RailMark? {
    if (navigator.current is AppNavKey.NewSale) {
        return RailMark.Entry(railKeys.indexOfFirst { it is AppNavKey.NewSale })
    }
    val section = navigator.railSection
    if (section == AppNavKey.Settings) return RailMark.Settings
    val shown = TopLevelDestination.ofRoot(section)?.let { barTabOf(it).root } ?: section
    return railKeys.indexOf(shown).takeIf { it >= 0 }?.let(RailMark::Entry)
}
