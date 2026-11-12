package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Hasta este número de segmentos reparten el ancho; con más, cada uno mide su texto y se desplazan. */
private const val MAX_EQUAL_SEGMENTS = 3

/**
 * El encabezado de una sección: el título, que TalkBack recorre como encabezado, y una acción de texto
 * opcional a la derecha.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = AppTheme.spacing.s24, bottom = AppTheme.spacing.s8)
                .padding(start = AppTheme.spacing.s16, end = AppTheme.spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (actionLabel != null) InlineTextButton(actionLabel, onAction)
    }
}

/**
 * Pestañas segmentadas: una barra de 48 dp en el papel más oscuro, con el segmento activo en papel de
 * tarjeta, texto en el color de acción y peso 600. El estado no depende del color: cambia el fondo y
 * el peso.
 *
 * Hasta tres segmentos reparten el ancho. Con cuatro o cinco, o si un texto no cabe en su parte, cada
 * uno mide su texto y la barra se desplaza, y el siguiente queda cortado a propósito, para mostrar que
 * hay más.
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Con más de tres, o si un texto no cabe entero en su parte del ancho, cada segmento mide lo que
    // su texto y la barra se desplaza: una palabra nunca se parte.
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val segmentPadding = with(LocalDensity.current) {
            (
                AppTheme.spacing.s12 * 2 +
                    AppTheme.spacing.s4 * 2
                ).roundToPx()
        }
        val share = constraints.maxWidth / options.size.coerceAtLeast(1)
        val tooNarrow =
            options.any { label ->
                measurer.measure(label, style, maxLines = 1, softWrap = false).size.width +
                    segmentPadding >
                    share
            }
        val scrollable = options.size > MAX_EQUAL_SEGMENTS || tooNarrow
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .then(
                        if (scrollable) {
                            Modifier.horizontalScroll(
                                rememberScrollState(),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(AppTheme.spacing.s4)
                    .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
        ) {
            options.forEachIndexed { index, label ->
                Segment(
                    label = label,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = if (scrollable) Modifier else Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    // En la barra desplazable, el segmento activo se trae a la vista: si es el último, como con letra
    // grande, quedaría cortado y no se sabría cuál está elegido.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) bringIntoView.bringIntoView() }
    Box(
        modifier =
            modifier
                .bringIntoViewRequester(bringIntoView)
                .heightIn(min = AppTheme.sizes.touchTarget - AppTheme.spacing.s8)
                .clip(MaterialTheme.shapes.small)
                .then(if (selected) Modifier.background(AppTheme.colors.cardSurface) else Modifier)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .padding(horizontal = AppTheme.spacing.s12, vertical = AppTheme.spacing.s8),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@ThemePreviews
@Composable
private fun SectionHeaderPreview() {
    PreviewTheme {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            SectionHeader(
                stringResource(R.string.nav_records),
                actionLabel = stringResource(R.string.action_add),
            )
            SegmentedTabs(
                listOf(
                    stringResource(R.string.records_tab_sales),
                    stringResource(R.string.records_tab_expenses),
                    stringResource(R.string.records_tab_purchases),
                ),
                selectedIndex = 0,
                onSelect = {},
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            )
            SegmentedTabs(
                listOf(
                    stringResource(R.string.records_tab_sales),
                    stringResource(R.string.records_tab_expenses),
                    stringResource(R.string.records_tab_purchases),
                    stringResource(R.string.term_inventory),
                    stringResource(R.string.nav_products),
                ),
                selectedIndex = 1,
                onSelect = {},
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            )
        }
    }
}
