package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El comprobante tal como sale, dibujado por el mismo motor que escribe el PDF y la imagen, sobre
 * `surfaceContainerHigh` y con el borde del papel en `cardBorder`. Es papel: no sigue el tema, y
 * en oscuro se ve igual que en claro.
 *
 * Sin [image], mientras se dibuja, ocupa su lugar con un esqueleto del papel. Para TalkBack es un
 * solo foco con [description], el resumen del documento: leer el papel línea a línea no aporta, y
 * el texto completo sale al compartirlo.
 */
@Composable
fun ReceiptPreview(image: ImageBitmap?, description: String, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .padding(AppTheme.spacing.s16)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.TopCenter,
    ) {
        val paper = Modifier
            .widthIn(max = AppTheme.sizes.receiptPreviewMaxWidth)
            .fillMaxWidth()
            .border(AppTheme.sizes.hairline, AppTheme.colors.cardBorder)
        if (image == null) {
            SkeletonBlock(paper.height(AppTheme.sizes.receiptPreviewMaxWidth))
            return@Box
        }
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = paper.aspectRatio(image.width.toFloat() / image.height),
        )
    }
}

@ThemePreviews
@Composable
private fun ReceiptPreviewLoadingPreview() {
    PreviewTheme {
        ReceiptPreview(image = null, description = PreviewSamples.SHORT_NAME)
    }
}
