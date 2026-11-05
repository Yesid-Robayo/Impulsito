package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.ui.theme.AppMotion
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Opacidad del contenido de un botón deshabilitado, la estándar de Material. */
private const val DISABLED_CONTENT_ALPHA = 0.38f

/** Opacidad del fondo de un botón deshabilitado, la estándar de Material. */
private const val DISABLED_CONTAINER_ALPHA = 0.12f

/**
 * El selector de cantidad: menos, el valor y más, con dos botones de 48 dp y el valor en medio para
 * que no se toquen por error.
 *
 * Mantener presionado repite cada 120 ms tras 400 ms. Si la unidad admite fracciones, los pasos no
 * aplican: el valor es un botón que abre la hoja de cantidad. Sin stock no limita nada: el aviso lo
 * da quien lo usa, sin diálogo.
 *
 * @param removesLine si quitar una unidad deja la línea en cero: el menos pasa a la papelera y quita
 *   la línea, y quien lo usa ofrece deshacer.
 * @param canIncrement falso en el tope de cantidad de una línea.
 * @param onValueClick abre la hoja de cantidad; con unidades fraccionarias es la única forma de
 *   cambiarla.
 */
@Composable
fun QuantityStepper(
    quantity: QuantityMilli,
    unit: UnitLabel,
    productName: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    removesLine: Boolean = false,
    canIncrement: Boolean = true,
    onValueClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val app = AppTheme.colors
    val stepsApply = !unit.allowsFraction
    val value = quantityText(quantity, unit)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        if (stepsApply) {
            StepButton(
                icon = if (removesLine) R.drawable.ic_delete else R.drawable.ic_remove,
                label =
                    if (removesLine) {
                        stringResource(R.string.a11y_stepper_remove_line, productName)
                    } else {
                        stringResource(R.string.a11y_stepper_remove_one, productName)
                    },
                container = scheme.secondaryContainer,
                content = scheme.onSecondaryContainer,
                enabled = true,
                repeats = !removesLine,
                onClick = onDecrement,
            )
        }
        Text(
            text = value,
            style = AppTheme.amounts.row,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier =
                Modifier
                    .widthIn(min = AppTheme.sizes.stepperValue)
                    .then(
                        if (onValueClick != null) {
                            // Sin pasos, el valor es el botón: lleva el borde de un campo para que se vea tocable.
                            Modifier
                                .heightIn(min = AppTheme.sizes.touchTarget)
                                .clip(MaterialTheme.shapes.medium)
                                .background(AppTheme.colors.cardSurface)
                                .border(
                                    AppTheme.sizes.hairline,
                                    scheme.outline,
                                    MaterialTheme.shapes.medium,
                                )
                                .clickable(role = Role.Button, onClick = onValueClick)
                                .padding(
                                    horizontal = AppTheme.spacing.s16,
                                    vertical = AppTheme.spacing.s12,
                                )
                        } else {
                            Modifier
                        },
                    ).semantics { stateDescription = value },
        )
        if (stepsApply) {
            StepButton(
                icon = R.drawable.ic_add,
                label = stringResource(R.string.a11y_stepper_add_one, productName),
                container = app.brandFill,
                content = app.onBrandFill,
                enabled = canIncrement,
                repeats = true,
                onClick = onIncrement,
            )
        }
    }
}

/**
 * Un botón de 48 dp del selector. Un toque suma uno al soltar; mantenerlo repite. Para TalkBack es
 * un botón común, con su nombre completo.
 */
@Composable
private fun StepButton(
    @DrawableRes icon: Int,
    label: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    repeats: Boolean,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val currentClick by rememberUpdatedState(onClick)
    val shownContainer = if (enabled) {
        container
    } else {
        MaterialTheme.colorScheme.onSurface.copy(
            alpha = DISABLED_CONTAINER_ALPHA,
        )
    }
    val shownContent = if (enabled) {
        content
    } else {
        MaterialTheme.colorScheme.onSurface.copy(
            alpha = DISABLED_CONTENT_ALPHA,
        )
    }
    Box(
        modifier =
            Modifier
                .size(AppTheme.sizes.touchTarget)
                .clip(MaterialTheme.shapes.medium)
                .background(shownContainer)
                .indication(interactions, ripple())
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = label
                    if (enabled) {
                        onClick {
                            currentClick()
                            true
                        }
                    } else {
                        disabled()
                    }
                }.pointerInput(enabled, repeats) {
                    if (!enabled) return@pointerInput
                    coroutineScope {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val press = PressInteraction.Press(down.position)
                            interactions.tryEmit(press)
                            var repeated = false
                            val repeat =
                                if (repeats) {
                                    launch {
                                        delay(AppMotion.REPEAT_DELAY)
                                        while (isActive) {
                                            repeated = true
                                            currentClick()
                                            delay(AppMotion.REPEAT_INTERVAL)
                                        }
                                    }
                                } else {
                                    null
                                }
                            val up = waitForUpOrCancellation()
                            repeat?.cancel()
                            if (up != null) {
                                up.consume()
                                if (!repeated) currentClick()
                                interactions.tryEmit(PressInteraction.Release(press))
                            } else {
                                interactions.tryEmit(PressInteraction.Cancel(press))
                            }
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = shownContent,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
    }
}

@ThemePreviews
@Composable
private fun QuantityStepperPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            QuantityStepper(s.ONE, s.UNIT, s.SHORT_NAME, {}, {}, removesLine = true)
            QuantityStepper(s.TWO, s.UNIT, s.SHORT_NAME, {}, {})
            QuantityStepper(s.TWO, s.BOX, s.SHORT_NAME, {}, {}, canIncrement = false)
            QuantityStepper(s.WEIGHT, s.KILOGRAM, s.SECOND_NAME, {}, {}, onValueClick = {})
        }
    }
}
