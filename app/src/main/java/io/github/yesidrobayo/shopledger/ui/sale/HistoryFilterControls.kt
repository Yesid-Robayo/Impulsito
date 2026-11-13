package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.FilterBar
import io.github.yesidrobayo.shopledger.ui.components.MenuChip
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.filtersLabel
import io.github.yesidrobayo.shopledger.ui.components.filtersSpokenLabel

/**
 * La barra de filtros de un historial de Registros: el período a la izquierda, que abre su hoja, y
 * `Filtros` a la derecha, que abre la de los demás filtros. Las flechas del período van debajo,
 * aparte, solo con un período elegido.
 *
 * @param sheetFilterCount cuántos filtros de la hoja están puestos.
 * @param singleFilter lo elegido cuando hay uno solo, para mostrarlo en el botón.
 */
@Composable
internal fun HistoryFilterBar(
    periodKind: HistoryPeriodKind?,
    onOpenPeriods: () -> Unit,
    sheetFilterCount: Int,
    singleFilter: String?,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val periodName = stringResource(R.string.filter_period)
    val periodValue = stringResource(periodKindLabel(periodKind))
    val filters = filtersLabel(sheetFilterCount, singleFilter)
    FilterBar(modifier.fillMaxWidth()) {
        MenuChip(
            label = periodValue,
            onClick = onOpenPeriods,
            active = periodKind != null,
            spokenLabel = stringResource(R.string.a11y_label_value, periodName, periodValue),
            leadingIcon = R.drawable.ic_calendar_month,
        )
        MenuChip(
            label = filters,
            onClick = onOpenFilters,
            active = sheetFilterCount > 0,
            spokenLabel = filtersSpokenLabel(filters),
        )
    }
}

/** Las opciones de la hoja del período, en orden: sin límite primero. */
internal val HISTORY_PERIOD_OPTIONS: List<HistoryPeriodKind?> =
    listOf(null, HistoryPeriodKind.DAY, HistoryPeriodKind.WEEK, HistoryPeriodKind.MONTH)

/** La hoja del período: todas las fechas, un día, una semana o un mes. Elegir uno la cierra. */
@Composable
internal fun HistoryPeriodSheet(
    selected: HistoryPeriodKind?,
    onSelect: (HistoryPeriodKind?) -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.filter_period)) {
        CompactChipRow(
            options = HISTORY_PERIOD_OPTIONS,
            label = { stringResource(periodKindLabel(it)) },
            selected = setOf(selected),
            onToggle = onSelect,
            multiple = false,
        )
    }
}

/**
 * La hoja de filtros de un historial: cada elección se aplica al tocarla y la lista de detrás
 * cambia al instante. `Quitar filtros` aparece con algún filtro puesto y deja el período.
 */
@Composable
internal fun HistoryFilterSheet(
    activeCount: Int,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.filter_more),
        action =
            if (activeCount > 0) {
                {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_clear_filters),
                        onClick = onClear,
                        variant = ActionVariant.OUTLINED,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                null
            },
    ) { content() }
}

/** El nombre de un grupo dentro de la hoja de filtros, como `Método de pago`. */
@Composable
internal fun FilterSheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** El texto de cada tipo de período, y `Todas las fechas` sin período. */
internal fun periodKindLabel(kind: HistoryPeriodKind?): Int = when (kind) {
    null -> R.string.period_all
    HistoryPeriodKind.DAY -> R.string.period_day
    HistoryPeriodKind.WEEK -> R.string.period_week
    HistoryPeriodKind.MONTH -> R.string.period_month
}
