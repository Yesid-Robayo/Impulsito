package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.DayOfWeek

/**
 * Una fecha de negocio que se puede cambiar: la fila muestra la fecha con el formato regional y, al
 * tocarla, abre una hoja con [MonthCalendar]. Elegir un día lo aplica y cierra la hoja.
 *
 * La semana empieza en [weekStartDay], el del negocio, y las palabras salen del idioma de la
 * interfaz: nada sale del teléfono. Las fechas futuras se bloquean salvo donde son futuras por
 * naturaleza, como vencimientos y entregas, con [latest] en `null`.
 *
 * @param latest el último día que se puede elegir; por defecto, hoy.
 * @param error el mensaje que dice cómo arreglarlo, o `null`.
 */
@Composable
fun DateField(
    label: String,
    date: BusinessDate?,
    onDateChange: (BusinessDate) -> Unit,
    today: BusinessDate,
    weekStartDay: DayOfWeek,
    modifier: Modifier = Modifier,
    latest: BusinessDate? = today,
    error: String? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    DateFieldRow(label, date, today, error, modifier) { open = true }
    if (open) {
        AppBottomSheet(onDismissRequest = { open = false }, title = label) {
            MonthCalendar(
                selected = date,
                onSelect = {
                    onDateChange(it)
                    open = false
                },
                today = today,
                weekStartDay = weekStartDay,
                latest = latest,
            )
        }
    }
}

/** La fila de la fecha, con el rótulo arriba, la fecha abajo y el icono del calendario. */
@Composable
private fun DateFieldRow(
    label: String,
    date: BusinessDate?,
    today: BusinessDate,
    error: String?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sizes = AppTheme.sizes
    val shape = MaterialTheme.shapes.medium
    val text = date?.let { LocalAppFormats.current.date.shortDate(it, today) } ?: NO_VALUE_TEXT
    Column(modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = sizes.field)
                    .clip(shape)
                    .background(AppTheme.colors.cardSurface)
                    .border(
                        sizes.hairline,
                        if (error !=
                            null
                        ) {
                            scheme.error
                        } else {
                            scheme.outline
                        },
                        shape,
                    )
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(text, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            }
            Icon(
                painter = painterResource(R.drawable.ic_calendar_month),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(sizes.icon),
            )
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.padding(
                    start = AppTheme.spacing.s16,
                    top = AppTheme.spacing.s4,
                ),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun DateFieldPreview() {
    val today = PreviewSamples.TODAY
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            DateField(stringResource(R.string.form_date), today, {}, today, DayOfWeek.MONDAY)
            DateField(stringResource(R.string.form_date), today.plusDays(-400), {
            }, today, DayOfWeek.SUNDAY)
            DateField(stringResource(R.string.form_date), null, {
            }, today, DayOfWeek.MONDAY, error = stringResource(R.string.state_error_read))
        }
    }
}
