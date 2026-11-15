package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El avance de una meta del mes: la etiqueta y el porcentaje arriba, una barra de 8 dp en
 * `chartBar` sobre su pista, y debajo lo logrado y la meta, `¤ 850.000 de ¤ 1.000.000`.
 *
 * - **Meta alcanzada:** la barra queda llena y una línea con `check` lo dice, sin celebración.
 * - **Ganancia negativa:** lo logrado va en `Loss`, con su signo, y la barra queda vacía.
 * - **Modo discreto:** montos y porcentaje se ocultan y la barra pierde su relleno, porque su
 *   largo también diría cuánto se lleva.
 * - **TalkBack:** un solo foco que dice la etiqueta, el porcentaje y los dos montos. La barra es
 *   decorativa. Con [onClick], el foco es un botón.
 *
 * @param advance lo logrado, la meta y el avance que calculó el dominio. Sin lo logrado, los
 *   montos y el porcentaje dicen «sin valor».
 */
@Composable
fun GoalProgress(
    label: String,
    advance: GoalAdvance,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val discreet = LocalDiscreetMode.current
    val percentText = goalPercentText(advance.progress)
    val (actualText, actualSpoken) = amountInText(advance.actual)
    val (goalText, goalSpoken) = amountInText(advance.goal)
    val description =
        if (discreet) {
            stringResource(
                R.string.a11y_goal_progress_hidden,
                label,
                stringResource(R.string.a11y_discreet_hidden),
            )
        } else {
            stringResource(
                R.string.a11y_goal_progress,
                label,
                advance.progress?.let { percentText } ?: stringResource(R.string.a11y_no_value),
                actualSpoken,
                goalSpoken,
            )
        }
    val reachedText = stringResource(R.string.goal_reached)
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            )
            .padding(vertical = AppTheme.spacing.s8)
            .clearAndSetSemantics {
                contentDescription = description
                if (advance.reached && !discreet) stateDescription = reachedText
            },
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
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
            fraction = if (discreet) 0f else goalFraction(advance),
            fill = AppTheme.colors.chartBar,
        )
        Text(
            formatAnnotated(stringResource(R.string.goal_progress), actualText, goalText),
            style = AppTheme.amounts.small,
            color =
                if (!discreet && isLoss(advance.actual)) {
                    AppTheme.colors.loss
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        if (advance.reached && !discreet) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
            ) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(AppTheme.sizes.iconInline),
                )
                Text(
                    reachedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * La pista de 8 dp con su relleno, del ancho de [fraction]. La comparten la meta y el límite de
 * ingresos. La proporción solo dibuja: ningún monto pasa por decimales binarios.
 *
 * @param marks posiciones de 0 a 1 donde va una marca vertical en `onSurface`, como el 80 % y el
 *   95 % del límite.
 */
@Composable
internal fun MeterTrack(
    fraction: Float,
    fill: Color,
    modifier: Modifier = Modifier,
    marks: List<Float> = emptyList(),
) {
    val shape = MaterialTheme.shapes.small
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(AppTheme.sizes.goalBar)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape),
    ) {
        val width = maxWidth
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(AppTheme.sizes.goalBar)
                    .background(fill, shape),
            )
        }
        marks.forEach { position ->
            Box(
                Modifier
                    .padding(start = width * position.coerceIn(0f, 1f))
                    .size(AppTheme.sizes.meterMark, AppTheme.sizes.goalBar)
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

/** Si lo logrado es una pérdida: entonces va en `Loss`, con su signo. */
private fun isLoss(actual: Money?): Boolean = actual != null && actual < Money.ZERO

/** Cuánto de la barra se llena: hasta la meta, nunca más ni menos de la pista. */
private fun goalFraction(advance: GoalAdvance): Float {
    val progress = advance.progress?.bp ?: return 0f
    return (progress.toFloat() / FULL.toFloat()).coerceIn(0f, 1f)
}

/** El porcentaje del avance, o «sin valor» si no se pudo calcular. */
@Composable
internal fun goalPercentText(progress: BasisPoints?): String =
    progress?.let { LocalAppFormats.current.percent.format(it) } ?: NO_VALUE_TEXT

/**
 * Un monto para ir dentro de una frase, con su lectura para TalkBack: oculto en modo discreto y
 * «sin valor» si falta.
 */
@Composable
internal fun amountInText(amount: Money?): Pair<CharSequence, String> {
    val discreet = LocalDiscreetMode.current
    val display = amount?.let { rememberMoneyDisplay(it, LocalAppFormats.current.money) }
    return when {
        discreet -> DISCREET_TEXT to stringResource(R.string.a11y_discreet_hidden)
        display == null -> NO_VALUE_TEXT to stringResource(R.string.a11y_no_value)
        else -> display.text to display.description
    }
}

/** El 100 % en puntos básicos. */
private const val FULL = 10_000L

@ThemePreviews
@Composable
private fun GoalProgressPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            GoalProgress(
                stringResource(R.string.goal_sales),
                GoalAdvance(Money(850_000), Money(1_000_000), BasisPoints(8_500)),
            )
            GoalProgress(
                s.LONG_NAME,
                GoalAdvance(s.LONG_AMOUNT, s.LONG_AMOUNT, BasisPoints(10_000)),
            )
            GoalProgress(
                stringResource(R.string.goal_profit),
                GoalAdvance(Money(-50_000), Money(300_000), BasisPoints(-1_667)),
            )
            GoalProgress(
                stringResource(R.string.goal_sales),
                GoalAdvance(null, Money(1_000_000), null),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun GoalProgressDiscreetPreview() {
    PreviewTheme(discreet = true) {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            GoalProgress(
                stringResource(R.string.goal_sales),
                GoalAdvance(Money(850_000), Money(1_000_000), BasisPoints(8_500)),
            )
        }
    }
}
