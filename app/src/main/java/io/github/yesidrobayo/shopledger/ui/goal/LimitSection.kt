package io.github.yesidrobayo.shopledger.ui.goal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.report.RevenueLimitView
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.rule.report.RevenueLimitRules
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LimitMeter
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.WholeNumberField
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.datetime.LocalDate

/**
 * `Límite de ingresos`: lo acumulado frente al tope que escribió la persona, con su aviso al 80 %,
 * al 95 % y al pasarlo, y el texto fijo de que es una referencia suya. Sin límite, un estado vacío
 * lleva a definirlo. La app no conoce ningún régimen: nada aquí viene de una ley.
 */
@Composable
internal fun LimitSection(limit: RevenueLimitView?, onAction: (GoalsAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column {
        SectionHeader(stringResource(R.string.limit_section))
        Column(Modifier.padding(horizontal = spacing.s16)) {
            if (limit == null) {
                EmptyStateInline(
                    title = stringResource(R.string.limit_empty_title),
                    body = stringResource(R.string.limit_empty_body),
                    action = {
                        PrimaryActionButton(
                            text = stringResource(R.string.limit_define_action),
                            onClick = { onAction(GoalsAction.StartLimit) },
                            variant = ActionVariant.TONAL,
                            modifier = Modifier.padding(top = spacing.s8),
                        )
                    },
                )
            } else {
                LimitMeter(limit)
                InlineTextButton(
                    text = stringResource(R.string.limit_edit_action),
                    onClick = { onAction(GoalsAction.StartLimit) },
                )
            }
            Text(
                stringResource(R.string.limit_disclaimer, stringResource(R.string.app_name)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s8),
            )
        }
    }
}

/**
 * La hoja del límite: `Monto del límite` y `Meses que suma`, de 1 a 60, con la ayuda de que
 * incluye el mes en curso. Con un límite guardado, `Quitar límite`. Una falla se dice en la hoja y
 * lo escrito se conserva.
 */
@Composable
internal fun LimitSheetHost(sheet: LimitSheet, onAction: (GoalsAction) -> Unit) {
    val quantity = LocalAppFormats.current.quantity
    AppBottomSheet(
        onDismissRequest = { onAction(GoalsAction.DismissSheet) },
        title = stringResource(
            if (sheet.editing) R.string.limit_edit_action else R.string.limit_define_action,
        ),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.limit_save_action),
                onClick = { onAction(GoalsAction.SaveLimit) },
                variant = ActionVariant.PRIMARY,
                state = if (sheet.saving) ActionState.WORKING else ActionState.IDLE,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        MoneyField(
            value = sheet.amountText,
            onValueChange = { onAction(GoalsAction.LimitAmountText(it)) },
            label = stringResource(R.string.limit_amount_field),
            error = sheet.amountProblem?.let {
                stringResource(
                    when (it) {
                        GoalFieldProblem.NOT_POSITIVE -> R.string.goal_error_value
                        GoalFieldProblem.NOT_A_NUMBER -> R.string.field_error_number
                        GoalFieldProblem.TOO_HIGH -> R.string.error_value_high
                    },
                )
            },
        )
        WholeNumberField(
            value = sheet.monthsText,
            onValueChange = { onAction(GoalsAction.LimitMonthsText(it)) },
            label = stringResource(R.string.limit_months_field),
            error = if (sheet.monthsInvalid) {
                stringResource(
                    R.string.limit_error_months,
                    quantity.formatCount(RevenueLimitRules.MIN_MONTHS.toLong()),
                    quantity.formatCount(RevenueLimitRules.MAX_MONTHS.toLong()),
                )
            } else {
                null
            },
            imeAction = ImeAction.Done,
        )
        Text(
            stringResource(R.string.limit_months_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sheet.saveFailed?.let {
            NoticeCard(stringResource(saveErrorText(it.outOfSpace)), NoticeVariant.ERROR)
        }
        if (sheet.editing) {
            InlineTextButton(
                text = stringResource(R.string.limit_remove_action),
                onClick = { onAction(GoalsAction.RemoveLimit) },
            )
        }
    }
}

@ThemePreviews
@Composable
private fun LimitSectionPreview() {
    PreviewTheme {
        Column {
            LimitSection(
                RevenueLimitView(
                    limit = RevenueLimit(Money(10_000_000), 12),
                    window = ReportPeriod.Range(
                        BusinessDate(LocalDate(2025, 12, 1)),
                        BusinessDate(LocalDate(2026, 11, 30)),
                    ),
                    accumulated = Money(8_200_000),
                    used = BasisPoints(8_200),
                    level = LimitLevel.FIRST_NOTICE,
                    since = null,
                ),
            ) {}
            LimitSection(null) {}
        }
    }
}
