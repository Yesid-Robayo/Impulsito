package io.github.yesidrobayo.shopledger.ui.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.AttachmentTarget
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptPhoto
import io.github.yesidrobayo.shopledger.domain.usecase.document.AttachReceiptPhotoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ImportReceiptPhotoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveReceiptPhotosUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.RemoveReceiptPhotoUseCase
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PhotoState
import io.github.yesidrobayo.shopledger.ui.components.PhotoThumb
import io.github.yesidrobayo.shopledger.ui.components.PhotoThumbSize
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Las fotos del comprobante de [target] en la navegación: arma su ViewModel y abre el selector de
 * fotos del sistema, que no pide permisos. Va dentro del detalle de un gasto o de una compra.
 *
 * @param canAdd si se ofrece agregar una: solo en un documento activo. Las que ya tiene se leen y
 *   se pueden quitar siempre, también si se anuló.
 */
@Composable
fun ReceiptPhotosRoute(target: AttachmentTarget, canAdd: Boolean) {
    val model =
        rememberAppViewModel(key = "receipt-photos-$target") { dependencies ->
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val import = ImportReceiptPhotoUseCase(dependencies.container.data.imageImporter)
            val attach = AttachReceiptPhotoUseCase(
                ports.transactions,
                ports.documentAttachments,
                ports.mediaAssets,
                clock,
            )
            val remove = RemoveReceiptPhotoUseCase(
                ports.transactions,
                ports.documentAttachments,
                ports.mediaAssets,
                clock,
            )
            ReceiptPhotosViewModel(
                photos = ObserveReceiptPhotosUseCase(ports.documentAttachments, ports.mediaAssets)(
                    target,
                ),
                importPhoto = { import(it) },
                attach = { attach(target, it) },
                remove = { remove(it) },
            )
        }
    val viewModel = (model as? ScreenModel.Ready)?.viewModel ?: return
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Lo elegido se lee una sola vez, al recodificarlo, y nunca se guarda su dirección.
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) viewModel.onAction(ReceiptPhotosAction.Picked(uri.toString()))
        }
    // La caché de fotos del contenedor: volver al detalle no lee otra vez los mismos archivos.
    val container = (LocalContext.current.applicationContext as ShopLedgerApp).container
    val photos = PhotoSource(LocalPhotoSource.current.index, container.photoCache)
    CompositionLocalProvider(LocalPhotoSource provides photos) {
        ReceiptPhotosSection(
            uiState = uiState,
            canAdd = canAdd,
            onAction = { action ->
                viewModel.onAction(action)
                if (action == ReceiptPhotosAction.Choose) {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
            },
        )
    }
}

/**
 * Las miniaturas de las fotos del comprobante, `Agregar foto del comprobante` si se puede, y el
 * error de la última vez. Tocar una miniatura la abre a lo ancho para leerla, con `Quitar foto`,
 * que pide confirmar. Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun ReceiptPhotosSection(
    uiState: ReceiptPhotosUiState,
    canAdd: Boolean,
    onAction: (ReceiptPhotosAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val photos = uiState.photos ?: return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        if (photos.isNotEmpty()) {
            val label = stringResource(R.string.attachment_photo)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.s8),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                photos.forEach { photo ->
                    ReceiptThumb(
                        photo = photo,
                        description = label,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(
                                onClickLabel = label,
                                role = Role.Image,
                            ) { onAction(ReceiptPhotosAction.Open(photo)) },
                    )
                }
            }
        }
        if (canAdd) {
            PrimaryActionButton(
                text = stringResource(R.string.attachment_add_action),
                onClick = { onAction(ReceiptPhotosAction.Choose) },
                variant = ActionVariant.OUTLINED,
                state = if (uiState.adding) ActionState.WORKING else ActionState.IDLE,
            )
        }
        uiState.error?.let { NoticeCard(stringResource(errorText(it)), NoticeVariant.ERROR) }
    }
    uiState.viewing?.let { PhotoViewer(it, uiState, onAction) }
    if (uiState.confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.attachment_remove_title),
            body = stringResource(R.string.attachment_remove_body),
            confirmLabel = stringResource(R.string.product_photo_remove_action),
            onConfirm = { onAction(ReceiptPhotosAction.ConfirmRemove) },
            onDismiss = { onAction(ReceiptPhotosAction.CancelRemove) },
            variant = ConfirmVariant.DESTRUCTIVE,
        )
    }
}

/** La miniatura de una foto, o su lugar con `Foto no disponible` si su imagen ya no está. */
@Composable
private fun ReceiptThumb(photo: ReceiptPhoto, description: String, modifier: Modifier) {
    val image = photo.image
    val source = LocalPhotoSource.current
    val state = if (image == null) {
        PhotoState.Unavailable
    } else {
        rememberPhoto(image, PhotoThumbSize.DETAIL, source.cache) ?: PhotoState.Unavailable
    }
    val spoken = if (state is PhotoState.Ready) description else photoDescription(state)
    PhotoThumb(state, spoken, modifier, PhotoThumbSize.DETAIL)
}

/** La foto abierta a lo ancho, para leer el comprobante, con `Quitar foto`. */
@Composable
private fun PhotoViewer(
    photo: ReceiptPhoto,
    uiState: ReceiptPhotosUiState,
    onAction: (ReceiptPhotosAction) -> Unit,
) {
    val title = stringResource(R.string.attachment_photo)
    AppBottomSheet(
        onDismissRequest = { onAction(ReceiptPhotosAction.Close) },
        title = title,
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.product_photo_remove_action),
                onClick = { onAction(ReceiptPhotosAction.AskRemove) },
                variant = ActionVariant.OUTLINED,
                state = if (uiState.removing) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sidePx = with(LocalDensity.current) { maxWidth.roundToPx() }
            val image = photo.image
            val state = if (image == null) {
                PhotoState.Unavailable
            } else {
                rememberFullPhoto(image, sidePx)
            }
            when (state) {
                is PhotoState.Ready -> Image(
                    bitmap = state.bitmap,
                    contentDescription = title,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium),
                )

                PhotoState.Pending, PhotoState.Unavailable -> Text(
                    photoDescription(state),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = AppTheme.sizes.listRow),
                )

                null -> Unit
            }
        }
        uiState.error?.let { NoticeCard(stringResource(errorText(it)), NoticeVariant.ERROR) }
    }
}

/**
 * La foto [image] decodificada para llenar [sidePx] de ancho, o `null` mientras se lee. Un
 * comprobante debe leerse, así que se pide casi entero; la caché lo suelta si hace falta espacio.
 */
@Composable
private fun rememberFullPhoto(image: StoredImage, sidePx: Int): PhotoState? {
    val cache = LocalPhotoSource.current.cache ?: return PhotoState.Unavailable
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

private fun errorText(error: ReceiptPhotoError): Int = when (error) {
    ReceiptPhotoError.UNREADABLE -> R.string.product_photo_error
    ReceiptPhotoError.OUT_OF_SPACE -> saveErrorText(outOfSpace = true)
    ReceiptPhotoError.SAVE_FAILED -> saveErrorText(outOfSpace = false)
}

@ThemePreviews
@Composable
private fun ReceiptPhotosSectionPreview() {
    val image = StoredImage("d".repeat(SHA256_LENGTH), "image/webp", 1_600, 1_200, 250_000)
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            ReceiptPhotosSection(
                uiState = ReceiptPhotosUiState(
                    photos = listOf(ReceiptPhoto(1L, image), ReceiptPhoto(2L, null)),
                    error = ReceiptPhotoError.UNREADABLE,
                ),
                canAdd = true,
                onAction = {},
            )
        }
    }
}

/** Largo de la suma de una imagen, en hexadecimal. */
private const val SHA256_LENGTH = 64
