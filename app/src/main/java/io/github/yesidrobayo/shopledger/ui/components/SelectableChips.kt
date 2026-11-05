package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.math.max

/**
 * Un chip que se elige. Sin elegir: papel de tarjeta y borde de contorno. Elegido: el contenedor de
 * pizarra, un borde de 1,5 dp y un check, para que el estado no dependa del color.
 */
@Composable
private fun SelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    role: Role,
    minHeight: Dp,
    modifier: Modifier = Modifier,
    trailingIcon: Int? = null,
    trailingLabel: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val sizes = AppTheme.sizes
    val shape = MaterialTheme.shapes.medium
    val border =
        if (selected) {
            BorderStroke(sizes.selectedBorder, scheme.onSecondaryContainer)
        } else {
            BorderStroke(sizes.hairline, scheme.outline)
        }
    val content = if (selected) scheme.onSecondaryContainer else scheme.onSurface
    val toggle =
        if (role == Role.Checkbox) {
            Modifier.toggleable(value = selected, role = role, onValueChange = { onClick() })
        } else {
            Modifier.selectable(selected = selected, role = role, onClick = onClick)
        }
    // El chip visible puede medir 40 dp, pero el área que se toca siempre mide 48.
    Box(
        modifier.heightIn(min = sizes.touchTarget).then(toggle),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = minHeight)
                    .clip(shape)
                    .background(
                        if (selected) scheme.secondaryContainer else AppTheme.colors.cardSurface,
                    )
                    .border(border, shape)
                    .padding(horizontal = AppTheme.spacing.s12, vertical = AppTheme.spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(sizes.iconTag),
                )
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = content)
            if (trailingIcon != null) {
                Icon(
                    painter = painterResource(trailingIcon),
                    contentDescription = trailingLabel,
                    tint = content,
                    modifier = Modifier.size(sizes.iconTag),
                )
            }
        }
    }
}

/**
 * Una fila de opciones en chips de 40 dp visibles dentro de 48 dp tocables. Selección única, como un
 * grupo de opciones, o múltiple, como casillas. Los chips pasan a la línea siguiente cuando no caben,
 * también con letra grande.
 */
@Composable
fun <T> CompactChipRow(
    options: List<T>,
    label: @Composable (T) -> String,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    multiple: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        options.forEach { option ->
            SelectChip(
                label = label(option),
                selected = option in selected,
                onClick = { onToggle(option) },
                role = if (multiple) Role.Checkbox else Role.RadioButton,
                minHeight = AppTheme.sizes.compactChip,
            )
        }
    }
}

// Se reparten en líneas si la fila mide más de vez y media el ancho visible.
private const val WRAP_NUM = 3
private const val WRAP_DEN = 2

/** Un método de pago activo, con el nombre que le puso la persona. Ninguna marca está en el código. */
@Immutable
data class PaymentMethodOption(val id: Long, val name: String, val hasQr: Boolean)

/**
 * Los métodos de pago activos, en su orden, con uno siempre elegido. Los chips miden 48 dp y se
 * desplazan en una fila; si la letra los hace más altos que eso, se reparten en varias líneas, para
 * que ninguno quede escondido fuera de la pantalla.
 *
 * Un método con QR o instrucciones lleva el icono `qr_code`, y TalkBack dice «Con imagen QR».
 */
@Composable
fun PaymentMethodChips(
    methods: List<PaymentMethodOption>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val qrLabel = stringResource(R.string.a11y_payment_method_has_qr)
    BoxWithConstraints(modifier) {
        val viewport = constraints.maxWidth
        val touch = AppTheme.sizes.touchTarget
        val gap = AppTheme.spacing.s8
        Box(Modifier.horizontalScroll(rememberScrollState()).selectableGroup()) {
            Layout(
                content = {
                    methods.forEach { method ->
                        SelectChip(
                            label = method.name,
                            selected = method.id == selectedId,
                            onClick = { onSelect(method.id) },
                            role = Role.RadioButton,
                            minHeight = touch,
                            trailingIcon = if (method.hasQr) R.drawable.ic_qr_code else null,
                            trailingLabel = if (method.hasQr) qrLabel else null,
                        )
                    }
                },
            ) { measurables, _ ->
                val gapPx = gap.roundToPx()
                val placeables = measurables.map { it.measure(Constraints(maxWidth = viewport)) }
                // Si el texto creció, los chips son más altos que el área táctil o piden mucho más que el
                // ancho, y en una sola fila habría que desplazar demasiado para ver el último: se
                // reparten en líneas. Con letra normal, la fila se desplaza.
                val total = placeables.sumOf { it.width } + gapPx * max(0, placeables.size - 1)
                val wrap =
                    placeables.any { it.height > touch.roundToPx() } ||
                        total * WRAP_DEN > viewport * WRAP_NUM
                if (!wrap) {
                    val width = placeables.sumOf { it.width } + gapPx * max(0, placeables.size - 1)
                    val height = placeables.maxOfOrNull { it.height } ?: 0
                    layout(width, height) {
                        var x = 0
                        placeables.forEach {
                            it.placeRelative(x, 0)
                            x += it.width + gapPx
                        }
                    }
                } else {
                    val positions = mutableListOf<Pair<Int, Int>>()
                    var x = 0
                    var y = 0
                    var lineHeight = 0
                    placeables.forEach {
                        if (x > 0 && x + it.width > viewport) {
                            x = 0
                            y += lineHeight + gapPx
                            lineHeight = 0
                        }
                        positions += x to y
                        x += it.width + gapPx
                        lineHeight = max(lineHeight, it.height)
                    }
                    layout(viewport, y + lineHeight) {
                        placeables.forEachIndexed { index, placeable ->
                            placeable.placeRelative(positions[index].first, positions[index].second)
                        }
                    }
                }
            }
        }
    }
}

@ThemePreviews
@Composable
private fun SelectableChipsPreview() {
    val s = PreviewSamples
    val methods =
        listOf(
            PaymentMethodOption(1, s.METHOD_CASH, false),
            PaymentMethodOption(2, s.METHOD_CARD, false),
            PaymentMethodOption(3, s.METHOD_TRANSFER, true),
            PaymentMethodOption(4, s.METHOD_WALLET, true),
        )
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
        ) {
            PaymentMethodChips(methods, selectedId = 1, onSelect = {})
            CompactChipRow(
                options = listOf(s.CATEGORY_A, s.CATEGORY_B, s.CATEGORY_C),
                label = { it },
                selected = setOf(s.CATEGORY_B),
                onToggle = {},
                multiple = true,
            )
        }
    }
}
