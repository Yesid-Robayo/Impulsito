package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una tarjeta de cifra que cuenta cosas, no dinero: la etiqueta y el número, con las cifras del
 * formato regional. Es la hermana de [StatTile] para conteos como `Por agotarse`, con la misma
 * tarjeta, el mismo estilo de cifra y un solo foco para TalkBack. Un conteo no es un dato sensible:
 * se ve también en modo discreto.
 *
 * @param attention si el número pide atención, como productos en stock negativo: va en el color de
 *   pérdida. El color nunca es la única señal: la etiqueta dice qué se cuenta.
 */
@Composable
fun CountTile(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
    attention: Boolean = false,
    loading: Boolean = false,
) {
    AppCard(modifier, contentPadding = PaddingValues(AppTheme.spacing.s12)) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (loading) {
                // El esqueleto no tiene texto: la tarjeta dice `Cargando` después de su etiqueta.
                val loadingText = stringResource(R.string.a11y_loading)
                SkeletonBlock(
                    Modifier
                        .padding(top = AppTheme.spacing.s4)
                        .width(AppTheme.spacing.s64)
                        .height(AppTheme.spacing.s20)
                        .semantics { contentDescription = loadingText },
                )
            } else {
                Text(
                    text = LocalAppFormats.current.quantity.format(
                        QuantityMilli.ofUnits(count.toLong()),
                        allowsFraction = false,
                    ),
                    style = AppTheme.amounts.tile,
                    color =
                        if (attention) {
                            AppTheme.colors.loss
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = 1,
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun CountTilePreview() {
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
                CountTile(stringResource(R.string.term_low_stock), 12, Modifier.weight(1f))
                CountTile(
                    stringResource(R.string.stock_filter_negative),
                    1_204,
                    Modifier.weight(1f),
                    attention = true,
                )
            }
            CountTile(stringResource(R.string.term_low_stock), 0, Modifier.fillMaxWidth())
            CountTile(
                stringResource(R.string.term_low_stock),
                0,
                Modifier.fillMaxWidth(),
                loading = true,
            )
        }
    }
}
