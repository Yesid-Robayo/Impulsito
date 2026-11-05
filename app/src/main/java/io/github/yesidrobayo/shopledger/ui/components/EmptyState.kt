package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Un estado vacío de pantalla completa: un círculo con el icono, un título, un texto corto y sus
 * acciones. Sin ilustraciones: el círculo y un buen texto bastan, y el APK sigue liviano.
 *
 * Va en el tercio superior del espacio libre, para que la acción quede a la vista sin buscarla.
 *
 * @param actions los botones del estado, uno debajo del otro. Un solo `Primary` por pantalla.
 */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val spacing = AppTheme.spacing
    val sizes = AppTheme.sizes
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.s16)
                .padding(top = spacing.s64, bottom = spacing.s40),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(sizes.emptyStateCircle)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(sizes.emptyStateIcon),
            )
        }
        Column(
            modifier = Modifier.widthIn(max = sizes.maxContentWidth).padding(top = spacing.s24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(
            modifier = Modifier.widthIn(
                max = sizes.maxContentWidth,
            ).fillMaxWidth().padding(top = spacing.s24),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
            content = actions,
        )
    }
}

/**
 * El estado vacío dentro de una tarjeta o de una sección: sin círculo, con el título, el texto y una
 * acción opcional, alineados a la izquierda como el resto de la tarjeta.
 */
@Composable
fun EmptyStateInline(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) action()
    }
}

@ThemePreviews
@Composable
private fun EmptyStatePreview() {
    PreviewTheme {
        EmptyState(
            icon = R.drawable.ic_inventory_2,
            title = stringResource(R.string.empty_catalog_title),
            body = stringResource(R.string.empty_catalog_body),
        ) {
            PrimaryActionButton(stringResource(R.string.empty_catalog_action), {
            }, ActionVariant.PRIMARY)
        }
    }
}

@ThemePreviews
@Composable
private fun EmptyStateInlinePreview() {
    PreviewTheme {
        AppCard(Modifier.padding(AppTheme.spacing.s16)) {
            EmptyStateInline(
                title = stringResource(R.string.empty_sales_title),
                body = stringResource(R.string.empty_sales_body),
            ) {
                InlineTextButton(stringResource(R.string.empty_sales_action), {})
            }
        }
    }
}
