package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** El peso de un botón. Un solo `PRIMARY` por pantalla: si todo resalta, nada resalta. */
enum class ActionVariant {
    /** La única acción principal de la pantalla, en el relleno de marca. */
    PRIMARY,

    /** Una acción secundaria importante. */
    TONAL,

    /** Una alternativa. */
    OUTLINED,

    /** Una acción menor. */
    TEXT,

    /** Confirmar algo que no se puede deshacer. */
    DESTRUCTIVE,
}

/**
 * El botón de acción: ancho completo, 56 dp de alto y las esquinas de una tarjeta, nunca una
 * píldora. Sin sombra ni elevación tonal. El texto pasa a dos líneas antes que cortarse.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    variant: ActionVariant,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val app = AppTheme.colors
    val colors =
        when (variant) {
            ActionVariant.PRIMARY -> ButtonDefaults.buttonColors(app.brandFill, app.onBrandFill)

            ActionVariant.TONAL -> ButtonDefaults.buttonColors(
                scheme.secondaryContainer,
                scheme.onSecondaryContainer,
            )

            ActionVariant.OUTLINED -> ButtonDefaults.outlinedButtonColors(
                contentColor = scheme.onSurface,
            )

            ActionVariant.TEXT -> ButtonDefaults.textButtonColors(contentColor = scheme.primary)

            ActionVariant.DESTRUCTIVE -> ButtonDefaults.buttonColors(scheme.error, scheme.onError)
        }
    // El botón de texto es una acción menor: basta el área táctil mínima.
    val minHeight =
        if (variant ==
            ActionVariant.TEXT
        ) {
            AppTheme.sizes.touchTarget
        } else {
            AppTheme.sizes.primaryButton
        }
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
        shape = MaterialTheme.shapes.large,
        colors = colors,
        elevation = null,
        border =
            if (variant == ActionVariant.OUTLINED) {
                BorderStroke(AppTheme.sizes.hairline, scheme.outline)
            } else {
                null
            },
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@ThemePreviews
@Composable
private fun PrimaryActionButtonPreview() {
    PreviewTheme {
        Column(
            modifier = Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            ActionVariant.entries.forEach { variant ->
                PrimaryActionButton(stringResource(R.string.empty_home_action), {}, variant)
            }
        }
    }
}
