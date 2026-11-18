package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

// La sombra de la marca lleva su color, no negro: se ve como luz del botón y no como hueco.
private const val SELL_SHADOW_ALPHA = 0.4f

/**
 * Una pestaña de la barra inferior: su etiqueta y su icono de contorno y de relleno. [tag] es la
 * marca fija con que la encuentran las mediciones de rendimiento, o `null` si no la lleva.
 */
data class BottomBarItem(
    val label: String,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
    val tag: String? = null,
)

/**
 * La barra inferior: cinco entradas del mismo ancho, en orden.
 *
 * Es propia para medir y limitar sus etiquetas como pide el sistema de diseño. La entrada activa
 * cambia de contorno a relleno y lleva una píldora detrás del icono: no depende solo del color. Las
 * etiquetas crecen con la letra del sistema solo hasta un tope, porque cinco no caben de otra forma.
 * Una etiqueta que no cabe se achica hasta 10 sp; si aun así no cabe, pasa a dos líneas y la barra
 * crece un poco, y solo una más larga que eso lleva puntos suspensivos. Nunca se corta sin ellos.
 * La presión larga y TalkBack dan la etiqueta completa.
 *
 * `Decir` no va en la barra: es el botón redondo [SayFloatingButton], que la navegación pone encima,
 * abajo a la derecha.
 *
 * @param items las cinco entradas, de izquierda a derecha.
 * @param selected la entrada activa, o `null` si ninguna lo está.
 */
@Composable
fun AppBottomBar(
    items: List<BottomBarItem>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(items.size == BAR_ENTRIES) { "La barra tiene cinco entradas" }
    val sizes = AppTheme.sizes
    val density = LocalDensity.current
    // Las medidas de la barra no crecen con la letra: solo sus etiquetas, y solo hasta el tope.
    val cappedDensity =
        Density(density.density, density.fontScale.coerceAtMost(sizes.bottomBarMaxFontScale))
    CompositionLocalProvider(LocalDensity provides cappedDensity) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .navigationBarsPadding(),
        ) {
            HorizontalDivider(
                thickness = sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = sizes.bottomBar - sizes.hairline)
                        .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    BottomBarTab(item, selected == index) { onSelect(index) }
                }
            }
        }
    }
}

/** Cuántas entradas tiene la barra: `Inicio`, `Vender`, `Gastos`, `Productos` y `Números`. */
private const val BAR_ENTRIES = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.BottomBarTab(item: BottomBarItem, selected: Boolean, onClick: () -> Unit) {
    val sizes = AppTheme.sizes
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    val pill = if (selected) colors.secondaryContainer else Color.Transparent
    // El peso va en una caja propia: la del globo no es hija directa de la fila y no lo recibiría.
    Box(Modifier.weight(1f).heightIn(min = sizes.bottomBar - sizes.hairline)) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                TooltipAnchorPosition.Above,
            ),
            tooltip = { PlainTooltip { Text(item.label) } },
            state = rememberTooltipState(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier =
                    Modifier
                        .then(item.tag?.let { Modifier.performanceTag(it) } ?: Modifier)
                        .fillMaxWidth()
                        .heightIn(min = sizes.bottomBar - sizes.hairline)
                        .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                        .padding(horizontal = AppTheme.spacing.s2, vertical = AppTheme.spacing.s4),
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
                        // La etiqueta de abajo ya nombra la pestaña: TalkBack no la lee dos veces.
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(sizes.icon),
                    )
                }
                Spacer(Modifier.height(AppTheme.spacing.s4))
                BottomBarLabel(item.label, tint, selected)
            }
        }
    }
}

/**
 * La etiqueta de una pestaña: en una línea, achicándose de 12 a 10 sp si hace falta. Si a 10 sp no
 * cabe en una línea, va en dos a 10 sp, y solo si tampoco cabe en dos lleva puntos suspensivos.
 */
@Composable
private fun BottomBarLabel(label: String, color: Color, selected: Boolean) {
    val sizes = AppTheme.sizes
    val style =
        MaterialTheme.typography.labelSmall.copy(
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = constraints.maxWidth
        val fitsOneLine =
            measurer
                .measure(
                    text = label,
                    style = style.copy(fontSize = sizes.bottomBarMinLabel),
                    maxLines = 1,
                    softWrap = false,
                ).size.width <= width
        if (fitsOneLine) {
            Text(
                text = label,
                color = color,
                style = style,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = sizes.bottomBarMinLabel,
                    maxFontSize = sizes.bottomBarMaxLabel,
                ),
            )
        } else {
            Text(
                text = label,
                color = color,
                style = style.copy(
                    fontSize = sizes.bottomBarMinLabel,
                    lineHeight =
                        sizes.bottomBarMinLabel * 1.2f,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * El bloque de la marca del riel, hoy `Decir`: con su palabra siempre visible y, como el botón
 * redondo de la barra, la única sombra de la app.
 */
@Composable
fun SellButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = R.drawable.ic_sell,
) {
    val sizes = AppTheme.sizes
    val colors = AppTheme.colors
    val shape = MaterialTheme.shapes.large
    Column(
        modifier =
            modifier
                .size(sizes.sellButtonWidth, sizes.sellButtonHeight)
                .shadow(
                    elevation = sizes.sellButtonShadow,
                    shape = shape,
                    ambientColor = colors.brandFill.copy(alpha = SELL_SHADOW_ALPHA),
                    spotColor = colors.brandFill.copy(alpha = SELL_SHADOW_ALPHA),
                ).clip(shape)
                .background(colors.brandFill)
                .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = colors.onBrandFill,
            modifier = Modifier.size(sizes.icon),
        )
        Text(
            text = label,
            color = colors.onBrandFill,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            autoSize =
                TextAutoSize.StepBased(
                    minFontSize = sizes.bottomBarMinLabel,
                    maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
                ),
        )
    }
}

/**
 * `Decir` sobre la barra inferior: un círculo de la marca con el micrófono, abajo a la derecha, al
 * alcance del pulgar. Es el único botón flotante redondo de la app y, con el bloque del riel, lo
 * único con sombra: así se distingue de los botones flotantes tonales de cada lista, como
 * `Nuevo gasto`, que van encima de él.
 *
 * Mide 56 dp, más que el mínimo táctil de 48 dp. Sin texto a la vista, [label] es lo que lee
 * TalkBack y lo que muestra la presión larga.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SayFloatingButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sizes = AppTheme.sizes
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier
                    .size(sizes.sayButton)
                    .shadow(
                        elevation = sizes.sellButtonShadow,
                        shape = shape,
                        ambientColor = colors.brandFill.copy(alpha = SELL_SHADOW_ALPHA),
                        spotColor = colors.brandFill.copy(alpha = SELL_SHADOW_ALPHA),
                    ).clip(shape)
                    .background(colors.brandFill)
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                // La descripción va en el botón entero: el icono no se lee aparte.
                contentDescription = null,
                tint = colors.onBrandFill,
                modifier = Modifier.size(sizes.icon),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun AppBottomBarPreview() {
    PreviewTheme {
        Column(horizontalAlignment = Alignment.End) {
            SayFloatingButton(
                label = stringResource(R.string.phrase_action_say),
                onClick = {},
                modifier = Modifier.padding(AppTheme.spacing.s16),
            )
            AppBottomBar(
                items =
                    listOf(
                        BottomBarItem(
                            stringResource(R.string.nav_home),
                            R.drawable.ic_home,
                            R.drawable.ic_home_filled,
                        ),
                        BottomBarItem(
                            stringResource(R.string.nav_sell),
                            R.drawable.ic_sell,
                            R.drawable.ic_sell,
                        ),
                        BottomBarItem(
                            stringResource(R.string.nav_expenses),
                            R.drawable.ic_payments,
                            R.drawable.ic_payments,
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
                    ),
                selected = 2,
                onSelect = {},
            )
        }
    }
}
