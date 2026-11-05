package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.rule.ReportPeriods
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth

/** Días de una semana: las columnas del calendario. */
private const val WEEK_DAYS = 7

/**
 * Un calendario de un mes, propio y no el de Material: así la semana empieza en el día que eligió el
 * negocio y las palabras salen del idioma de la interfaz con los números de la región, sin depender
 * del idioma ni de la región del teléfono.
 *
 * Cada día es un objetivo de 48 dp. El elegido va en el relleno de marca con esquinas de tarjeta, y
 * hoy lleva un borde: ninguno depende solo del color. Los días después de [latest] no se pueden elegir.
 *
 * @param latest el último día que se puede elegir, como hoy para una venta; `null` si la fecha es
 *   futura por naturaleza, como un vencimiento.
 */
@Composable
fun MonthCalendar(
    selected: BusinessDate?,
    onSelect: (BusinessDate) -> Unit,
    today: BusinessDate,
    weekStartDay: DayOfWeek,
    modifier: Modifier = Modifier,
    latest: BusinessDate? = today,
) {
    val formats = LocalAppFormats.current
    var shownMonthText by rememberSaveable {
        mutableStateOf((selected ?: today).date.yearMonth.toString())
    }
    val shownMonth = YearMonth.parse(shownMonthText)
    val canGoForward = latest == null || shownMonth < latest.date.yearMonth
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TopBarAction(R.drawable.ic_arrow_back, stringResource(R.string.a11y_month_previous)) {
                shownMonthText = shownMonth.minusMonth().toString()
            }
            Text(
                text = formats.date.monthYear(shownMonth),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
            )
            if (canGoForward) {
                TopBarAction(
                    R.drawable.ic_arrow_forward,
                    stringResource(R.string.a11y_month_next),
                ) {
                    shownMonthText = shownMonth.plusMonth().toString()
                }
            } else {
                Spacer(Modifier.size(AppTheme.sizes.touchTarget))
            }
        }
        val firstShown = ReportPeriods.weekStart(BusinessDate(shownMonth.firstDay), weekStartDay)
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            repeat(WEEK_DAYS) { index ->
                Text(
                    text = formats.date.weekdayShort(firstShown.plusDays(index).date.dayOfWeek),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(vertical = AppTheme.spacing.s8),
                )
            }
        }
        var weekStart = firstShown
        while (weekStart.date.yearMonth <= shownMonth) {
            Row(Modifier.fillMaxWidth()) {
                repeat(WEEK_DAYS) { index ->
                    val day = weekStart.plusDays(index)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (day.date.yearMonth == shownMonth) {
                            DayCell(
                                day = day,
                                selected = day == selected,
                                isToday = day == today,
                                enabled = latest == null || day <= latest,
                                onSelect = onSelect,
                            )
                        }
                    }
                }
            }
            weekStart = weekStart.plusDays(WEEK_DAYS)
        }
    }
}

@Composable
private fun DayCell(
    day: BusinessDate,
    selected: Boolean,
    isToday: Boolean,
    enabled: Boolean,
    onSelect: (BusinessDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val app = AppTheme.colors
    val sizes = AppTheme.sizes
    val shape = MaterialTheme.shapes.medium
    val description = LocalAppFormats.current.date.longDate(day)
    val todayLabel = stringResource(R.string.a11y_today)
    val color =
        when {
            selected -> app.onBrandFill
            !enabled -> scheme.onSurfaceVariant.copy(alpha = DISABLED_DAY_ALPHA)
            else -> scheme.onSurface
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = sizes.touchTarget)
                .padding(AppTheme.spacing.s2)
                .clip(shape)
                .then(if (selected) Modifier.background(app.brandFill) else Modifier)
                .then(
                    if (isToday &&
                        !selected
                    ) {
                        Modifier.border(sizes.hairline, scheme.primary, shape)
                    } else {
                        Modifier
                    },
                )
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                    onSelect(day)
                }
                .semantics {
                    contentDescription = description
                    if (isToday) stateDescription = todayLabel
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = day.date.day.toString(),
            style = AppTheme.amounts.row.copy(
                fontWeight = if (isToday ||
                    selected
                ) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Medium
                },
            ),
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Opacidad de un día que no se puede elegir. */
private const val DISABLED_DAY_ALPHA = 0.5f

@ThemePreviews
@Composable
private fun MonthCalendarPreview() {
    val today = PreviewSamples.TODAY
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s8),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            MonthCalendar(today.plusDays(-3), {}, today, DayOfWeek.MONDAY)
        }
    }
}
