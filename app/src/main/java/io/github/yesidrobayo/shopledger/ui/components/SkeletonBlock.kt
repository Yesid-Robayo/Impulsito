package io.github.yesidrobayo.shopledger.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.yesidrobayo.shopledger.ui.theme.AppMotion
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.coroutines.delay

/**
 * Un bloque de esqueleto con la forma de lo que viene, con un brillo lento. Con las animaciones del
 * sistema apagadas queda quieto: el movimiento no comunica nada que el bloque no diga.
 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val shine = MaterialTheme.colorScheme.surfaceContainerHighest
    val animated = remember { ValueAnimator.areAnimatorsEnabled() }
    val color =
        if (animated) {
            val transition = rememberInfiniteTransition(label = "skeleton")
            val value by transition.animateColor(
                initialValue = base,
                targetValue = shine,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(AppMotion.SKELETON_CYCLE / 2, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "skeletonColor",
            )
            value
        } else {
            base
        }
    Box(modifier.background(color, MaterialTheme.shapes.small))
}

/**
 * Muestra [content] solo si la carga pasa del umbral del esqueleto. Los datos son locales: lo
 * normal es que nunca se vea, y así una carga rápida no parpadea.
 */
@Composable
fun DelayedSkeleton(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(AppMotion.SKELETON_DELAY)
        visible = true
    }
    if (visible) content()
}

@ThemePreviews
@Composable
private fun SkeletonBlockPreview() {
    PreviewTheme {
        SkeletonBlock(
            Modifier
                .padding(AppTheme.spacing.s16)
                .fillMaxWidth()
                .height(AppTheme.sizes.touchTarget),
        )
    }
}
