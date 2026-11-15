package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Una ubicación que se puede elegir, con el nombre ya resuelto en el idioma de la interfaz. */
data class LocationChoice(val id: Long, val name: String)

/**
 * Dónde se trabaja: un chip con `location_on` y el nombre de la ubicación, que abre una hoja para
 * elegir otra.
 *
 * **No existe con una sola ubicación:** con menos de dos no dibuja nada, y quien lo usa no tiene
 * que preguntarlo. Así ninguna pantalla menciona ubicaciones a quien tiene un solo lugar.
 *
 * @param selectedId la elegida, o `null` con [allowAll] para `Todas las ubicaciones`.
 * @param allowAll si ofrece ver todas juntas, como el inventario. La venta y el ajuste no lo
 *   ofrecen: el stock sale o entra en un lugar.
 * @param onManage abre la pantalla de ubicaciones desde el pie de la hoja, o `null` si aquí no se
 *   ofrece.
 */
@Composable
fun LocationSwitcher(
    locations: List<LocationChoice>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    allowAll: Boolean = false,
    onManage: (() -> Unit)? = null,
) {
    if (locations.size < 2) return
    var open by rememberSaveable { mutableStateOf(false) }
    val selected = locations.firstOrNull { it.id == selectedId }
    val label = selected?.name ?: stringResource(R.string.location_switcher_all)
    MenuChip(
        label = label,
        onClick = { open = true },
        active = selected != null && allowAll,
        modifier = modifier,
        spokenLabel = stringResource(R.string.a11y_location_switcher, label),
        leadingIcon = R.drawable.ic_location_on,
    )
    if (open) {
        AppBottomSheet(
            onDismissRequest = { open = false },
            title = stringResource(R.string.term_location),
        ) {
            LocationSwitcherOptions(
                locations = locations,
                selectedId = selected?.id,
                allowAll = allowAll,
                onSelect = { id ->
                    open = false
                    onSelect(id)
                },
                onManage = onManage?.let { manage ->
                    {
                        open = false
                        manage()
                    }
                },
            )
        }
    }
}

/** Las opciones de la hoja, aparte para poder verlas sin abrirla. */
@Composable
fun ColumnScope.LocationSwitcherOptions(
    locations: List<LocationChoice>,
    selectedId: Long?,
    allowAll: Boolean,
    onSelect: (Long?) -> Unit,
    onManage: (() -> Unit)?,
) {
    // Un grupo de opciones excluyentes: TalkBack dice cuál es y de cuántas, como `1 de 3`.
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .selectableGroup(),
    ) {
        if (allowAll) {
            OptionRow(
                text = stringResource(R.string.location_switcher_all),
                selected = selectedId == null,
                onClick = { onSelect(null) },
            )
        }
        locations.forEach { location ->
            OptionRow(
                text = location.name,
                selected = location.id == selectedId,
                onClick = { onSelect(location.id) },
            )
        }
    }
    if (onManage != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .clickable(role = Role.Button, onClick = onManage)
                .padding(vertical = AppTheme.spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AppTheme.sizes.iconTag),
            )
            Text(
                stringResource(R.string.location_manage_action),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Una ubicación de la hoja: un toque la elige, y la elegida lleva la marca. */
@Composable
private fun OptionRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = AppTheme.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        Icon(
            painter = painterResource(
                if (selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked,
            ),
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(AppTheme.sizes.iconTag),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@ThemePreviews
@Composable
private fun LocationSwitcherPreview() {
    val locations = listOf(
        LocationChoice(1L, PreviewSamples.LOCATION_MAIN),
        LocationChoice(2L, PreviewSamples.LOCATION_SECOND),
        LocationChoice(3L, PreviewSamples.LONG_NAME),
    )
    PreviewTheme {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            LocationSwitcher(locations, selectedId = 2L, onSelect = {})
            LocationSwitcher(locations, selectedId = null, onSelect = {}, allowAll = true)
            LocationSwitcher(locations, selectedId = 3L, onSelect = {}, allowAll = true)
            // Con una sola ubicación no dibuja nada.
            LocationSwitcher(locations.take(1), selectedId = 1L, onSelect = {})
            Column {
                LocationSwitcherOptions(
                    locations = locations,
                    selectedId = 2L,
                    allowAll = true,
                    onSelect = {},
                    onManage = {},
                )
            }
        }
    }
}
