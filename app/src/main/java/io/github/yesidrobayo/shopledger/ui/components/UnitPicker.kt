package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La unidad de un producto o de una conversión: un [PickerField] con el nombre y el símbolo, que abre
 * [UnitPickerSheet]. Con [lockedReason], como la unidad base de un producto con historia, se ve
 * apagado y dice por qué.
 */
@Composable
fun UnitPicker(
    label: String,
    unit: MeasureUnit?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lockedReason: String? = null,
    error: String? = null,
) {
    val value =
        unit?.let { chosen ->
            val name = unitName(chosen)
            unitSymbol(chosen)?.takeIf { it != name }?.let {
                stringResource(R.string.unit_name_with_symbol, name, it)
            } ?: name
        }.orEmpty()
    Column(modifier) {
        PickerField(label = label, value = value, onClick = onClick, lockedReason = lockedReason)
        if (error != null) FieldError(error)
    }
}

/** Una fila de la hoja de unidades: el encabezado de un tipo o una unidad. */
private sealed interface PickerRow {
    data class Header(val kind: UnitKind) : PickerRow

    data class Choice(val unit: MeasureUnit) : PickerRow
}

/**
 * La hoja para elegir una unidad: búsqueda arriba, las vigentes agrupadas por tipo en el orden de
 * [UnitKind], con nombre y símbolo, y al final `Nueva unidad` y `Administrar unidades`, si quien la
 * abre los ofrece. Busca sin mayúsculas ni tildes, en el nombre y en el símbolo.
 *
 * @param excluded la unidad que no se ofrece, como la base al elegir una alterna.
 */
@Composable
fun UnitPickerSheet(
    units: List<MeasureUnit>,
    onPick: (MeasureUnit) -> Unit,
    onDismiss: () -> Unit,
    excluded: Long? = null,
    onNewUnit: (() -> Unit)? = null,
    onManage: (() -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val resources = LocalResources.current
    val normalized = TextNormalizer.normalize(query)
    val shown =
        units.filter { unit ->
            unit.id != excluded &&
                listOfNotNull(
                    SeedText.displayName(resources, SeedTable.UNITS, unit.systemKey, unit.name),
                    SeedText.unitSymbol(resources, unit.systemKey, unit.symbol),
                ).any { TextNormalizer.normalize(it).contains(normalized) }
        }
    val rows =
        UnitKind.entries.flatMap { kind ->
            val ofKind = shown.filter { it.kind == kind }
            if (ofKind.isEmpty()) {
                emptyList()
            } else {
                listOf(PickerRow.Header(kind)) + ofKind.map { PickerRow.Choice(it) }
            }
        }
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.term_unit),
        scrollable = false,
    ) {
        SelectionSheetContent(
            items = rows,
            key = { row ->
                when (row) {
                    is PickerRow.Header -> "kind-${row.kind.name}"
                    is PickerRow.Choice -> "unit-${row.unit.id}"
                }
            },
            query = query,
            onQueryChange = { query = it },
            searchHint = stringResource(R.string.unit_search_hint),
            itemContent = { row ->
                when (row) {
                    is PickerRow.Header ->
                        Text(
                            stringResource(unitKindLabel(row.kind)),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .padding(
                                        top = AppTheme.spacing.s16,
                                        bottom = AppTheme.spacing.s4,
                                    )
                                    .semantics { heading() },
                        )

                    is PickerRow.Choice ->
                        SettingsRow(
                            title = unitName(row.unit),
                            summary = unitSymbol(row.unit),
                            action =
                                SettingsRowAction.Navigation {
                                    onPick(row.unit)
                                    onDismiss()
                                },
                        )
                }
            },
        )
        if (onNewUnit != null) {
            PickerAction(R.drawable.ic_add, stringResource(R.string.unit_new_action)) {
                onDismiss()
                onNewUnit()
            }
        }
        if (onManage != null) {
            PickerAction(R.drawable.ic_chevron_right, stringResource(R.string.unit_manage_action)) {
                onDismiss()
                onManage()
            }
        }
    }
}

/** Una acción al pie de la hoja: una fila de 56 dp en el color de acción. */
@Composable
private fun PickerAction(icon: Int, text: String, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRowSingle)
                .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
