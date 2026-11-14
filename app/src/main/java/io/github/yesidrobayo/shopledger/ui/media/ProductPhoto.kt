package io.github.yesidrobayo.shopledger.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveStoredImagesUseCase
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.PhotoState
import io.github.yesidrobayo.shopledger.ui.components.PhotoThumb
import io.github.yesidrobayo.shopledger.ui.components.PhotoThumbSize

/**
 * De dónde sacan sus fotos las pantallas: [index] son las imágenes anotadas por su id, o `null`
 * mientras se leen, y [cache] las decodifica. Sin [cache], como en una vista previa o una prueba
 * de pantalla, ninguna foto se lee del disco.
 */
@Immutable
class PhotoSource(val index: Map<Long, StoredImage>?, val cache: PhotoCache?) {
    companion object {
        /** Sin fotos: lo que ve una pantalla fuera de la app. */
        val NONE = PhotoSource(index = emptyMap(), cache = null)
    }
}

/** Las fotos de la pantalla. La ruta las da con [ProvidePhotos]. */
val LocalPhotoSource = compositionLocalOf { PhotoSource.NONE }

/**
 * Da a [content] las fotos de la app: observa las imágenes anotadas, así una foto nueva o quitada
 * en otra pantalla llega sola, y comparte la caché de miniaturas del contenedor. Si la base no
 * abre, ninguna foto se muestra.
 */
@Composable
fun ProvidePhotos(content: @Composable () -> Unit) {
    if (LocalInspectionMode.current) {
        content()
        return
    }
    val container = (LocalContext.current.applicationContext as ShopLedgerApp).container
    val model =
        rememberAppViewModel(key = PHOTO_INDEX_KEY) { dependencies ->
            PhotoIndexViewModel(ObserveStoredImagesUseCase(dependencies.ports.mediaAssets))
        }
    val index =
        when (model) {
            ScreenModel.Opening -> null
            ScreenModel.Unavailable -> emptyMap()
            is ScreenModel.Ready -> model.viewModel.index.collectAsStateWithLifecycle().value
        }
    CompositionLocalProvider(
        LocalPhotoSource provides PhotoSource(index, container.photoCache),
        content = content,
    )
}

/**
 * La miniatura de la foto [mediaId], o nada si es `null`: un producto sin foto no dibuja ninguna.
 * Mientras se lee del disco ocupa su lugar en blanco, para que la fila no cambie de forma. Si el
 * archivo falta, dice `Foto pendiente` o `Foto no disponible`, nunca un error, y TalkBack lo lee.
 */
@Composable
fun ProductPhoto(
    mediaId: Long?,
    modifier: Modifier = Modifier,
    size: PhotoThumbSize = PhotoThumbSize.ROW,
) {
    if (mediaId == null) return
    val source = LocalPhotoSource.current
    val image = source.index?.get(mediaId)
    val state =
        when {
            source.index == null -> null
            image == null -> PhotoState.Unavailable
            else -> rememberPhoto(image, size, source.cache)
        }
    if (state == null) {
        LoadingFrame(stringResource(R.string.product_photo), modifier, size)
    } else {
        PhotoThumb(state, photoDescription(state), modifier, size)
    }
}

/** Lo que TalkBack lee de una foto: que la hay, o por qué no se ve. */
@Composable
fun photoDescription(state: PhotoState): String = stringResource(
    when (state) {
        is PhotoState.Ready -> R.string.product_photo
        PhotoState.Pending -> R.string.media_pending
        PhotoState.Unavailable -> R.string.media_unavailable
    },
)

/**
 * La foto [image] decodificada para [size], o `null` mientras se lee. La primera composición ya la
 * trae si estaba en la caché: así una fila que vuelve a la pantalla no parpadea. Sin [cache], la
 * foto no se lee.
 */
@Composable
fun rememberPhoto(image: StoredImage, size: PhotoThumbSize, cache: PhotoCache?): PhotoState? {
    if (cache == null) return PhotoState.Unavailable
    val sidePx = with(LocalDensity.current) { size.side.roundToPx() }
    val state by produceState<PhotoState?>(
        cache.peek(image.sha256, sidePx),
        cache,
        image.sha256,
        sidePx,
    ) {
        if (value == null) value = cache.load(image.sha256, sidePx)
    }
    return state
}

/** El lugar de una miniatura que todavía se lee: el mismo cuadro, sin icono. */
@Composable
private fun LoadingFrame(description: String, modifier: Modifier, size: PhotoThumbSize) {
    Box(
        modifier
            .size(size.side)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = description },
    )
}

/** La clave del ViewModel de las fotos, uno por entrada de la pila junto al de la pantalla. */
private const val PHOTO_INDEX_KEY = "photo-index"
