package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Cómo está una combinación en [VariantMatrix]. */
enum class VariantCellState {
    /** Se va a crear. */
    CHECKED,

    /** La persona la desmarcó: no se crea. */
    UNCHECKED,

    /** Ya es una variante: no se toca desde aquí. */
    CREATED,
}

/**
 * La matriz de combinaciones de las variantes: el primer eje en filas, el segundo en columnas y
 * una casilla por combinación. Con un solo eje es una lista de casillas.
 *
 * Si no cabe a lo ancho se desplaza en horizontal, con la columna del primer eje fija. Cada celda
 * es un solo foco de 48 dp, y TalkBack la lee con sus dos valores y su estado. Una ya creada se ve
 * con su marca de creada, sin casilla: no se desmarca.
 *
 * @param rows los valores del primer eje, en su orden.
 * @param columns los del segundo eje, o vacío si hay un solo eje.
 */
@Composable
fun VariantMatrix(
    rows: List<String>,
    columns: List<String>,
    stateOf: (row: String, column: String?) -> VariantCellState,
    onToggle: (row: String, column: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    if (columns.isEmpty()) {
        Column(modifier.fillMaxWidth()) {
            rows.forEach { row ->
                when (val state = stateOf(row, null)) {
                    VariantCellState.CREATED -> CreatedRow(row)

                    VariantCellState.CHECKED, VariantCellState.UNCHECKED ->
                        CheckboxRow(
                            row,
                            state == VariantCellState.CHECKED,
                            { onToggle(row, null) },
                        )
                }
            }
        }
        return
    }
    Row(modifier.fillMaxWidth()) {
        // La columna del primer eje queda fija mientras las celdas se desplazan.
        Column(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth / LABEL_SHARE)) {
            Box(Modifier.heightIn(min = AppTheme.sizes.touchTarget))
            rows.forEach { row ->
                Box(
                    Modifier.heightIn(min = AppTheme.sizes.touchTarget),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = row,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Column(
            Modifier
                .padding(start = spacing.s8)
                .horizontalScroll(rememberScrollState()),
        ) {
            Row {
                columns.forEach { column ->
                    Box(
                        Modifier
                            .heightIn(min = AppTheme.sizes.touchTarget)
                            .widthIn(min = AppTheme.sizes.touchTarget),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = column,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = spacing.s4),
                        )
                    }
                }
            }
            rows.forEach { row ->
                Row {
                    columns.forEach { column ->
                        VariantCell(
                            label = stringResource(R.string.variant_cell_description, row, column),
                            state = stateOf(row, column),
                            columnLabel = column,
                        ) { onToggle(row, column) }
                    }
                }
            }
        }
    }
}

/**
 * Una celda: una casilla de 48 dp, o la marca de creada. [label] es lo que lee TalkBack; con
 * [columnLabel], la celda mide al menos lo que su columna, para quedar debajo de su encabezado.
 */
@Composable
private fun VariantCell(
    label: String,
    state: VariantCellState,
    columnLabel: String? = null,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val created = stringResource(R.string.variant_created_status)
    val sizes = AppTheme.sizes
    Box(
        modifier =
            Modifier
                .heightIn(min = sizes.touchTarget)
                .widthIn(min = sizes.touchTarget)
                .then(
                    when (state) {
                        VariantCellState.CREATED ->
                            Modifier.semantics(mergeDescendants = true) {
                                contentDescription = label
                                stateDescription = created
                            }

                        VariantCellState.CHECKED, VariantCellState.UNCHECKED ->
                            Modifier
                                .toggleable(
                                    value = state == VariantCellState.CHECKED,
                                    role = Role.Checkbox,
                                    onValueChange = { onToggle() },
                                ).semantics { contentDescription = label }
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        // El texto invisible del encabezado iguala el ancho de la celda al de su columna.
        columnLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier
                        .padding(horizontal = AppTheme.spacing.s4)
                        .alpha(0f)
                        .clearAndSetSemantics {},
            )
        }
        when (state) {
            VariantCellState.CREATED ->
                Icon(
                    painter = painterResource(R.drawable.ic_check_circle),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(sizes.iconInline),
                )

            VariantCellState.CHECKED, VariantCellState.UNCHECKED ->
                Checkbox(
                    checked = state == VariantCellState.CHECKED,
                    onCheckedChange = null,
                    colors =
                        CheckboxDefaults.colors(
                            checkedColor = scheme.primary,
                            checkmarkColor = scheme.onPrimary,
                            uncheckedColor = scheme.outline,
                        ),
                )
        }
    }
}

/** Una variante ya creada en la lista de un solo eje: su valor y la marca de creada. */
@Composable
private fun CreatedRow(value: String) {
    val created = stringResource(R.string.variant_created_status)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .semantics(mergeDescendants = true) { stateDescription = created },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        Box(Modifier.size(AppTheme.sizes.touchTarget), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppTheme.sizes.iconInline),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// La columna fija del primer eje ocupa a lo sumo un tercio del ancho del contenido.
private const val LABEL_SHARE = 3

@ThemePreviews
@Composable
private fun VariantMatrixPreview() {
    PreviewTheme {
        var unchecked by remember { mutableStateOf(setOf("L" to "Azul")) }
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16)) {
            VariantMatrix(
                rows = listOf("S", "M", "L"),
                columns = listOf("Rojo", "Azul", "Verde oliva"),
                stateOf = { row, column ->
                    when {
                        row == "S" && column == "Rojo" -> VariantCellState.CREATED
                        (row to column) in unchecked -> VariantCellState.UNCHECKED
                        else -> VariantCellState.CHECKED
                    }
                },
                onToggle = { row, column ->
                    val key = row to requireNotNull(column)
                    unchecked = if (key in unchecked) unchecked - key else unchecked + key
                },
            )
            VariantMatrix(
                rows = listOf("Corto", PreviewSamples.LONG_NAME.take(40)),
                columns = emptyList(),
                stateOf = { _, _ -> VariantCellState.CHECKED },
                onToggle = { _, _ -> },
            )
        }
    }
}
