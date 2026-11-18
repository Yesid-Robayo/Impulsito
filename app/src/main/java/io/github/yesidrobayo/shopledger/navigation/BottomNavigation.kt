package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.AppBottomBar
import io.github.yesidrobayo.shopledger.ui.components.BottomBarItem
import io.github.yesidrobayo.shopledger.ui.components.PerformanceTags
import io.github.yesidrobayo.shopledger.ui.components.SayFloatingButton
import io.github.yesidrobayo.shopledger.ui.theme.AppMotion
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La barra inferior del teléfono y, encima, abajo a la derecha, el botón redondo `Decir`, si
 * [visible]: solo en las raíces, porque en detalles y formularios dan espacio y foco.
 *
 * La barra muestra `Inicio`, `Vender`, `Gastos`, `Productos` y `Números`. `Vender` no es una
 * pestaña: abre una venta a mano encima de lo visible. `Registros` sigue siendo una pestaña por
 * dentro, pero se abre desde Inicio: mientras se ve, la barra marca Inicio. `Decir` hace lo mismo
 * desde cualquier raíz, con [onSay]: escucha de una vez o abre la hoja, según el permiso y el
 * ajuste.
 *
 * Las dos siguen el paso de las pantallas: se apagan con la raíz que sale y vuelven cuando se
 * enciende la que entra. Sin animaciones en el teléfono, Compose las cambia de golpe.
 */
@Composable
internal fun BottomNavigation(
    visible: Boolean,
    navigator: AppNavigator,
    onSay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter =
            fadeIn(
                tween(
                    durationMillis = AppMotion.LONG - AppMotion.SCREEN_EXIT,
                    delayMillis = AppMotion.SCREEN_EXIT,
                    easing = AppMotion.EnterEasing,
                ),
            ),
        exit = fadeOut(tween(AppMotion.SCREEN_EXIT, easing = AppMotion.ExitEasing)),
    ) {
        // Mientras se funde, todo se dibuja en una capa del tamaño de esta columna, y lo que se
        // sale se recortaría. Por eso `Decir` lleva su margen dentro, también arriba para su
        // sombra. Lo que la columna deja libre a la izquierda del botón es transparente y no
        // recibe toques: la pantalla de abajo los sigue recibiendo.
        val spacing = AppTheme.spacing
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            SayFloatingButton(
                label = stringResource(R.string.phrase_action_say),
                onClick = onSay,
                modifier =
                    Modifier.padding(
                        top = AppTheme.sizes.sellButtonShadow,
                        end = spacing.s16,
                        bottom = spacing.s16,
                    ),
            )
            val shown = BAR_SLOTS.map { slot ->
                when (slot) {
                    null ->
                        BottomBarItem(
                            stringResource(R.string.nav_sell),
                            R.drawable.ic_sell,
                            R.drawable.ic_sell,
                            tag = PerformanceTags.tab(SELL_SLOT_TAG),
                        )

                    else ->
                        BottomBarItem(
                            stringResource(slot.label),
                            slot.icon,
                            slot.selectedIcon,
                            tag = PerformanceTags.tab(slot.name),
                        )
                }
            }
            AppBottomBar(
                items = shown,
                selected = BAR_SLOTS.indexOf(barTabOf(navigator.currentTab)),
                onSelect = { index ->
                    BAR_SLOTS[index]?.let(navigator::selectTab) ?: navigator.sell()
                },
            )
        }
    }
}

/** Lo que la barra marca con [tab] abierta: Registros, que no está en ella, marca Inicio. */
internal fun barTabOf(tab: TopLevelDestination): TopLevelDestination = when (tab) {
    TopLevelDestination.RECORDS -> TopLevelDestination.HOME

    TopLevelDestination.HOME,
    TopLevelDestination.EXPENSES,
    TopLevelDestination.PRODUCTS,
    TopLevelDestination.NUMBERS,
    -> tab
}

/** Lo que muestra la barra de abajo, en orden; `null` es `Vender`, que abre una venta a mano. */
private val BAR_SLOTS: List<TopLevelDestination?> =
    listOf(
        TopLevelDestination.HOME,
        null,
        TopLevelDestination.EXPENSES,
        TopLevelDestination.PRODUCTS,
        TopLevelDestination.NUMBERS,
    )

/** La marca de rendimiento del botón `Vender` de la barra. */
private const val SELL_SLOT_TAG = "SELL"
