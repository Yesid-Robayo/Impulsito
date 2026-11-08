package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** El tamaño de una miniatura: en una fila, en una fila de tableta o en el detalle. */
enum class PhotoThumbSize(val side: Dp) {
    ROW(40.dp),
    TABLET(48.dp),
    DETAIL(96.dp),
}

/** Lo que muestra una miniatura. Una cosa sin foto no dibuja ninguna: nunca hay relleno. */
sealed interface PhotoState {
    /** La foto, ya decodificada. */
    data class Ready(val bitmap: ImageBitmap) : PhotoState

    /** Todavía no llega del respaldo. */
    data object Pending : PhotoState

    /** No está en el teléfono ni en el respaldo. No es un error: no se avisa. */
    data object Unavailable : PhotoState
}

/**
 * La miniatura de una foto o una imagen, recortada al centro, con forma `medium` y un filete de 1 dp
 * como el de las tarjetas. Sin la foto, un icono sobre `surfaceContainerHigh` dice si está pendiente
 * o no disponible. [description] es lo que lee TalkBack.
 */
@Composable
fun PhotoThumb(
    state: PhotoState,
    description: String,
    modifier: Modifier = Modifier,
    size: PhotoThumbSize = PhotoThumbSize.ROW,
) {
    val shape = MaterialTheme.shapes.medium
    val frame =
        modifier
            .size(size.side)
            .clip(shape)
            .border(AppTheme.sizes.hairline, AppTheme.colors.cardBorder, shape)
            .semantics { contentDescription = description }
    when (state) {
        is PhotoState.Ready ->
            Image(
                bitmap = state.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = frame,
            )

        PhotoState.Pending -> PlaceholderIcon(R.drawable.ic_hourglass_empty, frame)

        PhotoState.Unavailable -> PlaceholderIcon(R.drawable.ic_image_not_supported, frame)
    }
}

@Composable
private fun PlaceholderIcon(icon: Int, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
    }
}

@ThemePreviews
@Composable
private fun PhotoThumbPreview() {
    PreviewTheme {
        Row(
            Modifier.padding(AppTheme.spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            PhotoThumb(PhotoState.Pending, PreviewSamples.SHORT_NAME)
            PhotoThumb(PhotoState.Unavailable, PreviewSamples.SHORT_NAME)
            PhotoThumb(
                PhotoState.Unavailable,
                PreviewSamples.SHORT_NAME,
                size = PhotoThumbSize.DETAIL,
            )
        }
    }
}
