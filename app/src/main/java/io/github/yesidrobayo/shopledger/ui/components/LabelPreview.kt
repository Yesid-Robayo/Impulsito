package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La etiqueta tal como sale en papel, dibujada por el mismo motor que imprime, sobre
 * `surfaceContainerHigh` y con el borde del papel en `cardBorder`. Es papel: no sigue el tema, y
 * en oscuro se ve igual que en claro.
 *
 * Se muestra a escala, sin suavizar: cada punto de la térmica es un cuadro nítido, como en el
 * papel. Sin [image], mientras se dibuja, ocupa su lugar con un esqueleto. [description] es lo que
 * lee TalkBack.
 */
@Composable
fun LabelPreview(image: ImageBitmap?, description: String, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .padding(AppTheme.spacing.s16),
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            SkeletonBlock(
                Modifier
                    .widthIn(max = AppTheme.sizes.labelPreviewMaxWidth)
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRow),
            )
            return@Box
        }
        // Dos puntos de la térmica por dp: una etiqueta de 58 mm mide 192 dp, cerca de su tamaño
        // real en la mano.
        val natural = (image.width / DOTS_PER_DP).dp
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier =
                Modifier
                    .widthIn(max = minOf(natural, AppTheme.sizes.labelPreviewMaxWidth))
                    .fillMaxWidth()
                    .aspectRatio(image.width.toFloat() / image.height)
                    .border(AppTheme.sizes.hairline, AppTheme.colors.cardBorder)
                    .semantics { contentDescription = description },
        )
    }
}

private const val DOTS_PER_DP = 2

@ThemePreviews
@Composable
private fun LabelPreviewLoadingPreview() {
    PreviewTheme {
        LabelPreview(image = null, description = PreviewSamples.SHORT_NAME)
    }
}
