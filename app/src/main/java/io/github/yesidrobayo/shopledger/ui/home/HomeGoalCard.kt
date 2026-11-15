package io.github.yesidrobayo.shopledger.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.ui.components.GoalProgress
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La meta del mes en Inicio: lo vendido en el mes frente a la meta de ventas, con su porcentaje y
 * su barra. Solo aparece si el mes tiene meta de ventas: quien no usa metas ve la pantalla de
 * siempre. Tocarla abre `Metas y límites`. En modo discreto se ocultan montos, porcentaje y
 * relleno.
 */
@Composable
internal fun HomeGoalCard(goal: GoalAdvance, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    GoalProgress(
        label = stringResource(R.string.home_goal_label),
        advance = goal,
        modifier = modifier,
        onClick = onOpen,
    )
}

@ThemePreviews
@Composable
private fun HomeGoalCardPreview() {
    PreviewTheme {
        HomeGoalCard(GoalAdvance(Money(850_000), Money(1_000_000), BasisPoints(8_500))) {}
    }
}
