package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Lo que el riel marca como abierto: una de sus entradas de [AppNavigationRail], o Ajustes. */
sealed interface RailMark {
    /** La entrada número [index] de la lista del riel. */
    data class Entry(val index: Int) : RailMark

    /** Ajustes, al pie. */
    data object Settings : RailMark
}

/**
 * El riel lateral de una ventana mediana o ancha: reemplaza a la barra inferior y se ve también en
 * los detalles.
 *
 * Arriba va Vender, el mismo bloque de la marca que en la barra; debajo, las entradas con la misma
 * píldora de la pestaña activa, y Ajustes al pie. La entrada abierta cambia de contorno a relleno,
 * de color y lleva la píldora: no depende solo del color. Las etiquetas crecen con la letra solo
 * hasta el tope de la barra, en dos líneas como mucho; TalkBack lee la etiqueta entera. Si la
 * ventana es baja, como un teléfono acostado, el riel se desplaza.
 *
 * @param items las cuatro pestañas y Negocio, en ese orden.
 * @param selected lo abierto, o `null` si nada del riel lo está.
 */
@Composable
fun AppNavigationRail(
    items: List<BottomBarItem>,
    settings: BottomBarItem,
    selected: RailMark?,
    onSelect: (Int) -> Unit,
    onSettings: () -> Unit,
    sellLabel: String,
    onSell: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes sellIcon: Int = R.drawable.ic_sell,
) {
    val sizes = AppTheme.sizes
    val spacing = AppTheme.spacing
    val density = LocalDensity.current
    // Las medidas del riel no crecen con la letra: solo sus etiquetas, y solo hasta el tope.
    val cappedDensity =
        Density(density.density, density.fontScale.coerceAtMost(sizes.bottomBarMaxFontScale))
    CompositionLocalProvider(LocalDensity provides cappedDensity) {
        Row(
            modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            BoxWithConstraints(
                // Las barras del sistema quedan fuera de los 80 dp: el riel nunca se angosta.
                Modifier
                    .fillMaxHeight()
                    .windowInsetsPadding(RailInsets)
                    .width(sizes.navigationRail),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(vertical = spacing.s12),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SellButton(label = sellLabel, onClick = onSell, icon = sellIcon)
                        Spacer(Modifier.height(spacing.s16))
                        Column(Modifier.selectableGroup()) {
                            items.forEachIndexed { index, item ->
                                RailEntry(
                                    item = item,
                                    selected = selected == RailMark.Entry(index),
                                    onClick = { onSelect(index) },
                                )
                            }
                        }
                    }
                    RailEntry(
                        item = settings,
                        selected = selected == RailMark.Settings,
                        onClick = onSettings,
                        modifier = Modifier.padding(top = spacing.s16),
                    )
                }
            }
            VerticalDivider(
                thickness = sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * Lo que el riel deja libre: la barra de estado arriba, la del sistema abajo y, si queda a la
 * izquierda, también la de navegación o el recorte de la cámara. El recorte solo cuenta a la
 * izquierda: arriba ya lo cubre la barra de estado, que la banda de demostración puede descontar.
 */
val RailInsets: WindowInsets
    @Composable get() =
        WindowInsets.systemBars
            .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)
            .union(WindowInsets.displayCutout.only(WindowInsetsSides.Start))

@Composable
private fun RailEntry(
    item: BottomBarItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizes = AppTheme.sizes
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    val pill = if (selected) colors.secondaryContainer else Color.Transparent
    Column(
        modifier =
            modifier
                .width(sizes.navigationRail)
                // Un destino del riel mide 56 dp de alto, más que el mínimo táctil de 48 dp.
                .heightIn(min = sizes.listRowSingle)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                .padding(horizontal = AppTheme.spacing.s4, vertical = AppTheme.spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(sizes.tabIndicatorWidth, sizes.tabIndicatorHeight)
                    .background(pill, RoundedCornerShape(percent = 50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (selected) item.selectedIcon else item.icon),
                // La etiqueta de abajo ya nombra la entrada: TalkBack no la lee dos veces.
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(sizes.icon),
            )
        }
        Spacer(Modifier.height(AppTheme.spacing.s4))
        Text(
            text = item.label,
            color = tint,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@ThemePreviews
@Composable
private fun AppNavigationRailPreview() {
    PreviewTheme {
        AppNavigationRail(
            items =
                listOf(
                    BottomBarItem(
                        stringResource(R.string.nav_home),
                        R.drawable.ic_home,
                        R.drawable.ic_home_filled,
                    ),
                    BottomBarItem(
                        stringResource(R.string.nav_records),
                        R.drawable.ic_receipt_long,
                        R.drawable.ic_receipt_long_filled,
                    ),
                    BottomBarItem(
                        stringResource(R.string.nav_products),
                        R.drawable.ic_inventory_2,
                        R.drawable.ic_inventory_2_filled,
                    ),
                    BottomBarItem(
                        stringResource(R.string.nav_numbers),
                        R.drawable.ic_bar_chart,
                        R.drawable.ic_bar_chart_filled,
                    ),
                    BottomBarItem(
                        stringResource(R.string.hub_title),
                        R.drawable.ic_business_center,
                        R.drawable.ic_business_center,
                    ),
                ),
            settings =
                BottomBarItem(
                    stringResource(R.string.settings_title),
                    R.drawable.ic_settings,
                    R.drawable.ic_settings,
                ),
            selected = RailMark.Entry(2),
            onSelect = {},
            onSettings = {},
            sellLabel = stringResource(R.string.nav_sell),
            onSell = {},
        )
    }
}
