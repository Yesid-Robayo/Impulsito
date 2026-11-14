package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.yesidrobayo.shopledger.ui.theme.AppMotion
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.coroutines.delay

/**
 * Lo que va encima de la vista de la cámara: un velo con una ventana sin velo en el centro, sus
 * cuatro esquinas y una etiqueta debajo.
 *
 * - Velo `scrim` al 60 %. La ventana mide 280 × 160 dp y se achica, con la misma forma, si no
 *   cabe, como con la letra grande.
 * - Las esquinas van en el color del texto sobre el héroe, con un filo de 1 dp del velo alrededor:
 *   se ven sobre una imagen muy clara y sobre una muy oscura.
 * - La etiqueta va en su propia píldora, nunca directo sobre la imagen. TalkBack la anuncia al
 *   cambiar, como con `Leído: {product}`.
 * - Buscando, las esquinas quedan fijas: ninguna línea se mueve sin parar. Con cada lectura,
 *   [readPulse] sube y las esquinas se cierran 8 dp un instante; sin animaciones del sistema, solo
 *   cambia el texto.
 */
@Composable
fun ScannerOverlay(label: String, readPulse: Int, modifier: Modifier = Modifier) {
    val sizes = AppTheme.sizes
    val spacing = AppTheme.spacing
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(readPulse) {
        if (readPulse == 0) return@LaunchedEffect
        closing = true
        delay(AppMotion.SHORT.toLong())
        closing = false
    }
    val inset by animateDpAsState(
        targetValue = if (closing) spacing.s8 else 0.dp,
        animationSpec = tween(AppMotion.SHORT, easing = AppMotion.StandardEasing),
        label = "scannerCorners",
    )
    val veil = MaterialTheme.colorScheme.scrim.copy(alpha = VEIL_ALPHA)
    val cornerColor = AppTheme.colors.onHero
    val edgeColor = MaterialTheme.colorScheme.scrim
    val shapeRadius = MaterialTheme.shapes.medium.topStart
    BoxWithConstraints(modifier.fillMaxSize()) {
        val window = windowSize(maxWidth - spacing.s24 * 2, maxHeight / 2)
        Canvas(Modifier.fillMaxSize()) {
            val windowPx = Size(window.width.toPx(), window.height.toPx())
            val topLeft =
                Offset((size.width - windowPx.width) / 2, (size.height - windowPx.height) / 2)
            val radius = shapeRadius.toPx(windowPx, this)
            drawVeil(veil, topLeft, windowPx, radius)
            drawCorners(
                topLeft = topLeft,
                window = windowPx,
                inset = inset.toPx(),
                stroke = sizes.scannerCornerStroke.toPx(),
                edge = sizes.hairline.toPx(),
                length = spacing.s24.toPx(),
                cornerColor = cornerColor,
                edgeColor = edgeColor,
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // La ventana ocupa el centro; la etiqueta va debajo, fuera de ella.
            Spacer(Modifier.size(window.width, window.height))
            Surface(
                modifier = Modifier.padding(top = spacing.s16),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .padding(horizontal = spacing.s12, vertical = spacing.s8)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** El tamaño de la ventana dentro de lo que cabe, con la forma de un código de barras. */
private data class WindowSize(val width: Dp, val height: Dp)

@Composable
private fun windowSize(availableWidth: Dp, availableHeight: Dp): WindowSize {
    val sizes = AppTheme.sizes
    val ratio = sizes.scannerWindowHeight / sizes.scannerWindowWidth
    val byHeight = availableHeight / ratio
    val width =
        min(sizes.scannerWindowWidth, min(availableWidth, byHeight))
            .coerceAtLeast(sizes.scannerWindowMinWidth)
    return WindowSize(width, width * ratio)
}

private fun DrawScope.drawVeil(veil: Color, topLeft: Offset, window: Size, radius: Float) {
    val path =
        Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(
                RoundRect(
                    left = topLeft.x,
                    top = topLeft.y,
                    right = topLeft.x + window.width,
                    bottom = topLeft.y + window.height,
                    cornerRadius = CornerRadius(radius),
                ),
            )
        }
    drawPath(path, veil)
}

/** Las cuatro esquinas en L, primero su filo oscuro y encima el trazo claro. */
private fun DrawScope.drawCorners(
    topLeft: Offset,
    window: Size,
    inset: Float,
    stroke: Float,
    edge: Float,
    length: Float,
    cornerColor: Color,
    edgeColor: Color,
) {
    val left = topLeft.x + inset
    val top = topLeft.y + inset
    val right = topLeft.x + window.width - inset
    val bottom = topLeft.y + window.height - inset
    val corners =
        listOf(
            Triple(Offset(left, top), 1f, 1f),
            Triple(Offset(right, top), -1f, 1f),
            Triple(Offset(left, bottom), 1f, -1f),
            Triple(Offset(right, bottom), -1f, -1f),
        )
    for ((color, width) in listOf(edgeColor to stroke + edge * 2, cornerColor to stroke)) {
        for ((corner, dx, dy) in corners) {
            drawLine(color, corner, corner + Offset(dx * length, 0f), width, StrokeCap.Round)
            drawLine(color, corner, corner + Offset(0f, dy * length), width, StrokeCap.Round)
        }
    }
}

private const val VEIL_ALPHA = 0.6f

@ThemePreviews
@Composable
private fun ScannerOverlayPreview() {
    PreviewTheme {
        Box(Modifier.size(width = 360.dp, height = 480.dp)) {
            ScannerOverlay(label = PreviewSamples.LONG_NAME, readPulse = 0)
        }
    }
}
