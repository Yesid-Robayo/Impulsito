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

/** Lo que el riel muestra, en orden: las cuatro pestañas y Negocio. Ajustes va aparte, al pie. */
private val railKeys: List<AppNavKey> =
    TopLevelDestination.entries.map { it.root } + AppNavKey.BusinessHub

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
 * @param stack la pila que dibuja la navegación, la misma de [navigator].
 */
@Composable
internal fun RailHost(
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
    val tabs = TopLevelDestination.entries.map {
        BottomBarItem(stringResource(it.label), it.icon, it.selectedIcon)
    }
    val section = navigator.railSection
    AppNavigationRail(
        items = tabs + BottomBarItem(
            stringResource(R.string.hub_title),
            R.drawable.ic_business_center,
            R.drawable.ic_business_center,
        ),
        settings = BottomBarItem(
            stringResource(R.string.settings_title),
            R.drawable.ic_settings,
            R.drawable.ic_settings,
        ),
        selected =
            if (section == AppNavKey.Settings) {
                RailMark.Settings
            } else {
                railKeys.indexOf(section).takeIf { it >= 0 }?.let(RailMark::Entry)
            },
        onSelect = { go(railKeys[it]) },
        onSettings = { go(AppNavKey.Settings) },
        sellLabel = stringResource(R.string.phrase_action_say),
        onSell = { go(AppNavKey.Phrase()) },
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
