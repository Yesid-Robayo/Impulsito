package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodNavigation
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.DayOfWeek

/**
 * El período anterior a [period], del mismo tipo: el día, la semana o el mes del calendario del
 * negocio que contiene el día anterior a su comienzo. Una semana es la del negocio, con su día de
 * inicio, y un mes nunca se arma con semanas.
 */
fun BusinessCalendar.previous(period: ReportPeriod): ReportPeriod =
    sameKind(period, period.start.plusDays(-1))

/** El período siguiente a [period], del mismo tipo: el que contiene el día después de su final. */
fun BusinessCalendar.next(period: ReportPeriod): ReportPeriod =
    sameKind(period, period.end.plusDays(1))

/**
 * Si se puede avanzar desde [period]: no en el período en curso, ni en el último con datos si hay
 * fechas posteriores. [latest] es el más tardío de los dos días.
 */
fun canGoForward(period: ReportPeriod, latest: BusinessDate): Boolean = latest > period.end

private fun BusinessCalendar.sameKind(period: ReportPeriod, date: BusinessDate): ReportPeriod =
    when (period) {
        is ReportPeriod.Day -> day(date)

        is ReportPeriod.Week -> week(date)

        is ReportPeriod.Month -> month(date)

        // Un rango se mueve entero, con su mismo largo.
        is ReportPeriod.Range ->
            if (date <
                period.start
            ) {
                PeriodNavigation.previous(period)
            } else {
                PeriodNavigation.next(period)
            }
    }

/**
 * El selector de período: flecha atrás, el período y flecha adelante, con áreas de 48 dp. No tiene
 * límite hacia atrás, y la flecha adelante se oculta cuando no hay a dónde ir, sin mover la etiqueta.
 *
 * La etiqueta sale del formateador de fechas: el mes como título, la semana como rango desde el día de
 * inicio del negocio, y el día con su nombre. TalkBack la anuncia al cambiar.
 *
 * @param today para decidir si una fecha lleva el año: solo las de otro año.
 */
@Composable
fun PeriodSelector(
    period: ReportPeriod,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    showNext: Boolean,
    today: BusinessDate,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TopBarAction(
            R.drawable.ic_arrow_back,
            stringResource(R.string.a11y_period_previous),
            onPrevious,
        )
        Text(
            text = periodLabel(period, today),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = AppTheme.spacing.s4)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (showNext) {
            TopBarAction(
                R.drawable.ic_arrow_forward,
                stringResource(R.string.a11y_period_next),
                onNext,
            )
        } else {
            Spacer(Modifier.size(AppTheme.sizes.touchTarget))
        }
    }
}

/** El nombre de un período, con las palabras del idioma y los números de la región. */
@Composable
fun periodLabel(period: ReportPeriod, today: BusinessDate): String {
    val date = LocalAppFormats.current.date
    return when (period) {
        is ReportPeriod.Day ->
            if (period.date.date.year ==
                today.date.year
            ) {
                date.weekdayDayMonth(period.date)
            } else {
                date.dayMonthYear(period.date)
            }

        is ReportPeriod.Week ->
            stringResource(
                R.string.format_date_range,
                date.shortDate(period.start, today),
                date.shortDate(period.end, today),
            )

        is ReportPeriod.Month -> date.monthYear(period.yearMonth)

        is ReportPeriod.Range ->
            stringResource(
                R.string.format_date_range,
                date.shortDate(period.start, today),
                date.shortDate(period.end, today),
            )
    }
}

@ThemePreviews
@Composable
private fun PeriodSelectorPreview() {
    val today = PreviewSamples.TODAY
    val calendar =
        BusinessCalendar(requireNotNull(BusinessTimeZone.of(PREVIEW_ZONE)), DayOfWeek.MONDAY)
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s8),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            PeriodSelector(calendar.day(today), {}, {}, showNext = false, today = today)
            val week = calendar.week(today)
            PeriodSelector(calendar.previous(week), {}, {}, showNext = true, today = today)
            val month = calendar.month(today)
            PeriodSelector(calendar.previous(calendar.previous(month)), {
            }, {}, showNext = true, today = today)
        }
    }
}

/** Una zona fija para las vistas previas: no representa a ningún negocio. */
private const val PREVIEW_ZONE = "Etc/UTC"
