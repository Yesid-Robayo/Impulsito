package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La barra de filtros de una lista: el período a la izquierda y el botón de filtros a la derecha,
 * cada uno un [MenuChip]. Con letra grande, si no caben juntos, el segundo baja entero a la línea
 * siguiente, alineado al inicio: nada queda fuera de la pantalla ni hay que desplazar.
 *
 * Separa lo que se recorre, el período, de lo que se filtra, que vive en una hoja con su conteo.
 */
@Composable
fun FilterBar(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

/**
 * Un chip que abre una hoja: dice lo elegido y lleva la flecha `expand_more`, para que no se
 * confunda con un chip que se marca. Activo, como un filtro puesto, va en el contenedor de pizarra
 * con borde de 1,5 dp; sin elegir, en papel de tarjeta con borde de contorno. El estado no depende
 * solo del color: lo dice el texto.
 *
 * Mide 40 dp visibles dentro de 48 dp tocables. Un nombre largo se parte en líneas, nunca se
 * corta.
 *
 * @param label lo que se ve: lo elegido o el nombre del filtro.
 * @param spokenLabel lo que lee TalkBack, si debe decir más que [label], como el nombre del filtro
 *   y lo elegido.
 * @param leadingIcon un icono antes del texto, como el calendario del período.
 */
@Composable
fun MenuChip(
    label: String,
    onClick: () -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
    spokenLabel: String? = null,
    @DrawableRes leadingIcon: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val sizes = AppTheme.sizes
    val shape = MaterialTheme.shapes.medium
    val border =
        if (active) {
            BorderStroke(sizes.selectedBorder, scheme.onSecondaryContainer)
        } else {
            BorderStroke(sizes.hairline, scheme.outline)
        }
    val content = if (active) scheme.onSecondaryContainer else scheme.onSurface
    val container = if (active) scheme.secondaryContainer else AppTheme.colors.cardSurface
    Box(
        modifier
            .heightIn(min = sizes.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                spokenLabel?.let { contentDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = sizes.compactChip)
                    .clip(shape)
                    .background(container)
                    .border(border, shape)
                    .padding(horizontal = AppTheme.spacing.s12, vertical = AppTheme.spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            if (leadingIcon != null) {
                Icon(
                    painter = painterResource(leadingIcon),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(sizes.iconTag),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(sizes.iconTag),
            )
        }
    }
}

/**
 * El texto del botón de filtros: sin filtros, `Filtros`; con uno, lo elegido, para que siga a la
 * vista, como el proveedor con que se abrió la lista; con más, `Filtros · 2`.
 *
 * @param single lo elegido cuando hay un solo filtro puesto.
 */
@Composable
fun filtersLabel(count: Int, single: String?): String = when {
    count <= 0 -> stringResource(R.string.filter_more)
    count == 1 && single != null -> single
    else -> stringResource(R.string.filter_more_count, count)
}

/** Lo que TalkBack lee del botón de filtros: `Filtros` y lo que dice el botón, si es distinto. */
@Composable
fun filtersSpokenLabel(label: String): String {
    val name = stringResource(R.string.filter_more)
    return if (label == name) name else stringResource(R.string.a11y_label_value, name, label)
}

@ThemePreviews
@Composable
private fun FilterBarPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            FilterBar {
                MenuChip(
                    label = stringResource(R.string.period_all),
                    onClick = {},
                    active = false,
                    leadingIcon = R.drawable.ic_calendar_month,
                )
                MenuChip(label = filtersLabel(0, null), onClick = {}, active = false)
            }
            FilterBar {
                MenuChip(
                    label = stringResource(R.string.period_week),
                    onClick = {},
                    active = true,
                    leadingIcon = R.drawable.ic_calendar_month,
                )
                MenuChip(label = filtersLabel(1, s.METHOD_CASH), onClick = {}, active = true)
            }
            FilterBar {
                MenuChip(
                    label = stringResource(R.string.period_month),
                    onClick = {},
                    active = true,
                    leadingIcon = R.drawable.ic_calendar_month,
                )
                MenuChip(label = filtersLabel(1, s.LONG_NAME), onClick = {}, active = true)
            }
            FilterBar {
                MenuChip(
                    label = stringResource(R.string.period_day),
                    onClick = {},
                    active = true,
                    leadingIcon = R.drawable.ic_calendar_month,
                )
                MenuChip(label = filtersLabel(2, null), onClick = {}, active = true)
            }
        }
    }
}
