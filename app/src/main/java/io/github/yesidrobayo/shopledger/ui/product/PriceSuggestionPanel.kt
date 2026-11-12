package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.PercentField
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La sugerencia de precio: el margen objetivo, que se puede cambiar sin guardarlo, el precio que lo
 * alcanza y el margen que deja de verdad, con el paso y el impuesto. El precio y su margen se ocultan
 * en modo discreto, porque con el objetivo dejarían ver el costo.
 *
 * Sin costo no hay sugerencia: lo dice, y con [onFixCost] ofrece corregir el costo. Con [onUse], un
 * botón de texto pone el precio sugerido en el campo de precio; sin él, quien lo dibuja lo ofrece
 * como su acción principal.
 */
@Composable
fun PriceSuggestionPanel(
    draft: PriceSuggestionDraft,
    onTargetChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onUse: (() -> Unit)? = null,
    onFixCost: (() -> Unit)? = null,
) {
    val outcome = draft.outcome
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        if (outcome == SuggestionOutcome.NoCost) {
            Text(
                stringResource(R.string.product_suggestion_no_cost),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            onFixCost?.let { InlineTextButton(stringResource(R.string.product_fix_cost), it) }
            return@Column
        }
        PercentField(
            value = draft.targetText,
            onValueChange = onTargetChange,
            label = stringResource(R.string.product_target_margin),
            error = targetError(outcome),
            imeAction = ImeAction.Done,
        )
        if (outcome is SuggestionOutcome.Suggested) {
            val formats = LocalAppFormats.current
            val price = rememberMoneyDisplay(outcome.price, formats.money)
            val margin = when (val value = outcome.margin) {
                is Margin.Defined -> formats.percent.format(value.value)
                Margin.NoData, Margin.OutOfRange -> NO_VALUE_TEXT
            }
            DiscreetValue(style = MaterialTheme.typography.titleMedium) {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(
                        formatAnnotated(
                            stringResource(R.string.product_suggested_price),
                            price.text,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.product_price_margin, margin),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            onUse?.let { InlineTextButton(stringResource(R.string.product_use_price), it) }
        }
    }
}

/** El error del margen escrito, si lo tiene. Vacío no es un error: todavía se está escribiendo. */
@Composable
private fun targetError(outcome: SuggestionOutcome): String? = when (outcome) {
    is SuggestionOutcome.InvalidTarget -> when (outcome.problem) {
        TargetMarginProblem.NOT_A_NUMBER -> stringResource(R.string.field_error_number)

        TargetMarginProblem.TOO_MANY_DECIMALS -> stringResource(R.string.error_percent_decimals)

        TargetMarginProblem.OUT_OF_RANGE -> {
            val percent = LocalAppFormats.current.percent
            stringResource(
                R.string.error_percent_range,
                percent.format(BasisPoints.ZERO),
                percent.format(BasisPoints.MAX_TARGET_MARGIN),
            )
        }
    }

    SuggestionOutcome.AbovePriceLimit -> stringResource(R.string.error_value_high)

    is SuggestionOutcome.Suggested, SuggestionOutcome.NoCost, SuggestionOutcome.Blank -> null
}
