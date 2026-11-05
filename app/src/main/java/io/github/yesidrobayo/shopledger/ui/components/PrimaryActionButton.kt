package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppMotion
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

/** Lo que está haciendo un botón después del toque. */
enum class ActionState {
    /** Listo para tocar. */
    IDLE,

    /** Trabajando: un indicador a la izquierda, el texto se mantiene y no atiende más toques. */
    WORKING,

    /** Terminó bien: un check breve. Quien lo usa lo devuelve a `IDLE`. */
    DONE,
}

/**
 * El botón de acción: ancho completo, 56 dp de alto y las esquinas de una tarjeta, nunca una
 * píldora. Sin sombra ni elevación tonal. El texto pasa a dos líneas antes que cortarse.
 *
 * Antes de deshabilitarlo, conviene dejarlo activo y decir qué falta al tocarlo.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    variant: ActionVariant,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: ActionState = ActionState.IDLE,
) {
    PrimaryActionButton(AnnotatedString(text), onClick, variant, modifier, enabled, state)
}

/**
 * El mismo botón con un texto armado, como `Registrar venta · ¤ 25.000`, donde el monto conserva la
 * fuente de su símbolo. Se arma con [formatAnnotated] y un recurso que recibe el monto.
 */
@Composable
fun PrimaryActionButton(
    text: AnnotatedString,
    onClick: () -> Unit,
    variant: ActionVariant,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: ActionState = ActionState.IDLE,
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
        // Mientras trabaja ignora los toques sin cambiar de color: deshabilitarlo parecería un error.
        onClick = { if (state == ActionState.IDLE) onClick() },
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
        enabled = enabled,
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
        when (state) {
            ActionState.IDLE -> Unit

            ActionState.WORKING ->
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .padding(end = AppTheme.spacing.s8)
                            .size(AppTheme.sizes.buttonProgress)
                            .clearAndSetSemantics {},
                    color = LocalContentColor.current,
                    strokeWidth = AppTheme.sizes.progressStroke,
                    trackColor = Color.Transparent,
                )

            ActionState.DONE ->
                AnimatedVisibility(visible = true, enter = fadeIn(tween(AppMotion.SHORT))) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.padding(
                            end = AppTheme.spacing.s8,
                        ).size(AppTheme.sizes.buttonProgress),
                    )
                }
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/**
 * Un botón de texto compacto, para la acción de un aviso, de un encabezado o de un diálogo: mide lo
 * que su texto, con 48 dp de alto como mínimo, y usa el color de acción.
 */
@Composable
fun InlineTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = AppTheme.sizes.touchTarget),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
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
            val amount =
                rememberMoneyDisplay(PreviewSamples.LONG_AMOUNT, LocalAppFormats.current.money)
            PrimaryActionButton(
                formatAnnotated(stringResource(R.string.sale_register_amount), amount.text),
                {},
                ActionVariant.PRIMARY,
                state = ActionState.WORKING,
            )
            PrimaryActionButton(stringResource(R.string.empty_home_action), {
            }, ActionVariant.TONAL, state = ActionState.DONE)
            PrimaryActionButton(stringResource(R.string.empty_home_action), {
            }, ActionVariant.PRIMARY, enabled = false)
            InlineTextButton(stringResource(R.string.action_retry), {})
        }
    }
}
