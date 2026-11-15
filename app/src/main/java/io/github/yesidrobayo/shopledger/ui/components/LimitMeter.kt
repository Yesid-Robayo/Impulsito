package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.report.RevenueLimitView
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.rule.report.RevenueLimitRules
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.LocalDate

/**
 * El límite de ingresos que escribió la persona: `Acumulado en 12 meses` y el porcentaje usado,
 * la misma barra de [GoalProgress] con marcas fijas al 80 % y al 95 %, lo acumulado frente al
 * límite y, si la historia es más corta que la ventana, desde cuándo suma.
 *
 * - **Color con palabras.** La barra pasa a `warning` al 80 % y a `loss` al 95 %, y el aviso de
 *   cada nivel va en su propia fila, con icono y frase, para no depender del color.
 * - **Modo discreto:** montos y porcentaje se ocultan y la barra pierde su relleno. El aviso
 *   sigue, porque dice un umbral y no una cifra del negocio.
 * - **TalkBack:** un solo foco con `a11y_limit_meter`. Las marcas no se leen aparte.
 */
@Composable
fun LimitMeter(view: RevenueLimitView, modifier: Modifier = Modifier) {
    val discreet = LocalDiscreetMode.current
    val formats = LocalAppFormats.current
    val percentText = goalPercentText(view.used)
    val (accumulatedText, accumulatedSpoken) = amountInText(view.accumulated)
    val (limitText, limitSpoken) = amountInText(view.limit.amount)
    val label = stringResource(R.string.limit_section)
    val description =
        if (discreet) {
            stringResource(
                R.string.a11y_goal_progress_hidden,
                label,
                stringResource(R.string.a11y_discreet_hidden),
            )
        } else {
            stringResource(
                R.string.a11y_limit_meter,
                view.used?.let { percentText } ?: stringResource(R.string.a11y_no_value),
                accumulatedSpoken,
                limitSpoken,
            )
        }
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = AppTheme.spacing.s8)
                .clearAndSetSemantics { contentDescription = description },
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(
                        R.plurals.limit_accumulated,
                        view.limit.months,
                        formats.quantity.formatCount(view.limit.months.toLong()),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (discreet) {
                    DiscreetValue(style = AppTheme.amounts.small) {}
                } else {
                    Text(
                        percentText,
                        style = AppTheme.amounts.small,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            MeterTrack(
                fraction = if (discreet) 0f else limitFraction(view.used),
                fill = levelColor(view.level),
                marks = LIMIT_MARKS,
            )
            Text(
                formatAnnotated(
                    stringResource(R.string.limit_progress),
                    accumulatedText,
                    limitText,
                ),
                style = AppTheme.amounts.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            view.since?.let {
                Text(
                    stringResource(R.string.limit_since, formats.date.dayMonthYear(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LimitNotice(view.level)
    }
}

/** El aviso de cada nivel, con icono y frase. Por debajo del primero no hay aviso. */
@Composable
private fun LimitNotice(level: LimitLevel) {
    val percent = LocalAppFormats.current.percent
    val text = when (level) {
        LimitLevel.BELOW -> return

        LimitLevel.FIRST_NOTICE ->
            stringResource(
                R.string.limit_notice_reached,
                percent.format(RevenueLimitRules.FIRST_NOTICE),
            )

        LimitLevel.SECOND_NOTICE ->
            stringResource(
                R.string.limit_notice_reached,
                percent.format(RevenueLimitRules.SECOND_NOTICE),
            )

        LimitLevel.EXCEEDED -> stringResource(R.string.limit_notice_over)
    }
    NoticeCard(text, NoticeVariant.WARNING)
}

/** El color de la barra por nivel: tinta de gráfica, `warning` al 80 % y `loss` al 95 %. */
@Composable
private fun levelColor(level: LimitLevel): Color = when (level) {
    LimitLevel.BELOW -> AppTheme.colors.chartBar
    LimitLevel.FIRST_NOTICE -> AppTheme.colors.warning
    LimitLevel.SECOND_NOTICE, LimitLevel.EXCEEDED -> AppTheme.colors.loss
}

/** Cuánto de la barra se llena: hasta el límite, nunca más ni menos de la pista. */
private fun limitFraction(used: BasisPoints?): Float {
    val bp = used?.bp ?: return 0f
    return (bp.toFloat() / BasisPoints.ONE_HUNDRED_PERCENT.bp.toFloat()).coerceIn(0f, 1f)
}

/** Las marcas fijas de la barra: 80 % y 95 %. La proporción solo dibuja. */
private val LIMIT_MARKS: List<Float> = listOf(
    RevenueLimitRules.FIRST_NOTICE.bp / BasisPoints.ONE_HUNDRED_PERCENT.bp.toFloat(),
    RevenueLimitRules.SECOND_NOTICE.bp / BasisPoints.ONE_HUNDRED_PERCENT.bp.toFloat(),
)

private fun previewView(accumulated: Long, used: Long, level: LimitLevel) = RevenueLimitView(
    limit = RevenueLimit(Money(10_000_000), 12),
    window = ReportPeriod.Range(
        BusinessDate(LocalDate(2025, 12, 1)),
        BusinessDate(LocalDate(2026, 11, 30)),
    ),
    accumulated = Money(accumulated),
    used = BasisPoints(used),
    level = level,
    since = null,
)

@ThemePreviews
@Composable
private fun LimitMeterPreview() {
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            LimitMeter(previewView(4_000_000, 4_000, LimitLevel.BELOW))
            LimitMeter(previewView(8_200_000, 8_200, LimitLevel.FIRST_NOTICE))
            LimitMeter(
                previewView(10_400_000, 10_400, LimitLevel.EXCEEDED).copy(
                    since = BusinessDate(LocalDate(2026, 3, 9)),
                ),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun LimitMeterDiscreetPreview() {
    PreviewTheme(discreet = true) {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            LimitMeter(previewView(8_200_000, 8_200, LimitLevel.FIRST_NOTICE))
        }
    }
}
