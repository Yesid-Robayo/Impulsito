package io.github.yesidrobayo.shopledger.ui.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ScannerOverlay
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El escáner: la cámara a pantalla completa, sin barra inferior, con la ventana en el centro y un
 * panel abajo que siempre ofrece `Escribir el código`, la salida que funciona sin cámara.
 *
 * Sin permiso, una hoja explica para qué se usa antes de pedirlo; negado, se explica cómo
 * activarlo y escribir el código sigue a mano. La cámara la dibuja [cameraContent], aparte para
 * que la pantalla se pruebe y se vea sin cámara.
 */
@Composable
fun ScannerScreen(
    uiState: ScannerUiState,
    onAction: (ScannerAction) -> Unit,
    cameraContent: @Composable (Modifier) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(uiState.readPulse) {
        if (uiState.readPulse > 0) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim) {
        when (uiState.permission) {
            // Todavía no se sabe: el fondo oscuro, sin nada que parpadee.
            CameraPermission.UNKNOWN -> Unit

            CameraPermission.NOT_REQUESTED -> {
                Box(Modifier.fillMaxSize())
                PermissionSheet(onAction)
            }

            CameraPermission.DENIED ->
                ScannerMessage(
                    icon = R.drawable.ic_no_photography,
                    title =
                        stringResource(
                            R.string.scanner_denied_title,
                            stringResource(R.string.app_name),
                        ),
                    body = stringResource(R.string.scanner_denied_body),
                    primary = stringResource(R.string.action_open_settings),
                    onPrimary = { onAction(ScannerAction.OpenSettings) },
                    onAction = onAction,
                )

            CameraPermission.GRANTED ->
                when (uiState.camera) {
                    CameraPhase.FAILED ->
                        ScannerMessage(
                            icon = R.drawable.ic_no_photography,
                            title = stringResource(R.string.scanner_error_title),
                            body = null,
                            primary = stringResource(R.string.action_retry),
                            onPrimary = { onAction(ScannerAction.Retry) },
                            onAction = onAction,
                        )

                    CameraPhase.OPENING, CameraPhase.RUNNING ->
                        CameraLayout(uiState, onAction, cameraContent)
                }
        }
        if (uiState.typingCode) TypeCodeSheet(onAction)
        uiState.sheet?.let { CodeSheet(it, onAction) }
    }
}

/** La cámara con su ventana, la barra de arriba y el panel; en una ventana ancha, lado a lado. */
@Composable
private fun CameraLayout(
    uiState: ScannerUiState,
    onAction: (ScannerAction) -> Unit,
    cameraContent: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidePanel = maxWidth >= EXPANDED_WIDTH
        val preview = stringResource(R.string.a11y_scanner_preview)
        val camera: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier) {
                cameraContent(Modifier.fillMaxSize().semantics { contentDescription = preview })
                ScannerOverlay(label = overlayLabel(uiState), readPulse = uiState.readPulse)
                ScannerTopBar(uiState, onAction)
            }
        }
        if (sidePanel) {
            Row(Modifier.fillMaxSize()) {
                camera(Modifier.weight(1f).fillMaxHeight())
                ScannerPanel(
                    uiState,
                    onAction,
                    Modifier.width(AppTheme.sizes.scannerSidePanel).fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                camera(Modifier.weight(1f).fillMaxWidth())
                ScannerPanel(uiState, onAction, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun overlayLabel(uiState: ScannerUiState): String {
    val lastRead = uiState.lastRead
    val sheet = uiState.sheet
    return when {
        uiState.camera == CameraPhase.OPENING -> stringResource(R.string.scanner_opening)
        sheet is ScanSheet.Unknown -> stringResource(R.string.a11y_scanner_unknown, sheet.code)
        uiState.slowHint -> stringResource(R.string.scanner_slow_hint)
        lastRead != null -> stringResource(R.string.a11y_scanner_read, lastRead)
        else -> stringResource(R.string.scanner_aim)
    }
}

/** Cerrar, el título y la linterna, sobre una franja del velo que los deja leer. */
@Composable
private fun ScannerTopBar(uiState: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    val onImage = MaterialTheme.colorScheme.inverseOnSurface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = BAR_ALPHA))
                .statusBarsPadding()
                .padding(horizontal = AppTheme.spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onAction(ScannerAction.Close) }) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.a11y_close),
                tint = onImage,
            )
        }
        Text(
            text = stringResource(R.string.scanner_title),
            style = MaterialTheme.typography.titleLarge,
            color = onImage,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (uiState.hasTorch && uiState.camera == CameraPhase.RUNNING) {
            IconButton(onClick = { onAction(ScannerAction.ToggleTorch) }) {
                Icon(
                    painterResource(
                        if (uiState.torchOn) R.drawable.ic_flash_off else R.drawable.ic_flash_on,
                    ),
                    contentDescription =
                        stringResource(
                            if (uiState.torchOn) {
                                R.string.a11y_scanner_flash_off
                            } else {
                                R.string.a11y_scanner_flash_on
                            },
                        ),
                    tint = onImage,
                )
            }
        }
    }
}

/**
 * El panel de abajo: en lectura continua, cuántos van con `Terminar` y el último; siempre,
 * `Escribir el código`. Con la letra grande crece hacia arriba, y la ventana se achica.
 */
@Composable
private fun ScannerPanel(
    uiState: ScannerUiState,
    onAction: (ScannerAction) -> Unit,
    modifier: Modifier,
) {
    val spacing = AppTheme.spacing
    Surface(
        modifier = modifier.keepsMessagesAbove(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalArrangement = Arrangement.spacedBy(spacing.s8, Alignment.Bottom),
        ) {
            val lastRead = uiState.lastRead
            if (uiState.continuous) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(
                            R.plurals.scanner_read_count,
                            uiState.readCount,
                            uiState.readCount,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    InlineTextButton(
                        text = stringResource(R.string.scanner_finish_action),
                        onClick = { onAction(ScannerAction.Close) },
                    )
                }
                if (lastRead != null) {
                    Text(
                        stringResource(R.string.scanner_last, lastRead),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PrimaryActionButton(
                text = stringResource(R.string.scanner_type_code_action),
                onClick = { onAction(ScannerAction.TypeCode) },
                variant = ActionVariant.OUTLINED,
            )
        }
    }
}

/** La hoja que explica la cámara antes de pedir el permiso. Cerrarla cierra el escáner. */
@Composable
private fun PermissionSheet(onAction: (ScannerAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(ScannerAction.Close) },
        title = stringResource(R.string.permission_camera_title),
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                PrimaryActionButton(
                    text = stringResource(R.string.permission_camera_allow_action),
                    onClick = { onAction(ScannerAction.AllowCamera) },
                    variant = ActionVariant.PRIMARY,
                )
                PrimaryActionButton(
                    text = stringResource(R.string.scanner_type_code_action),
                    onClick = { onAction(ScannerAction.TypeCode) },
                    variant = ActionVariant.OUTLINED,
                )
            }
        },
    ) {
        Text(
            stringResource(R.string.permission_camera_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Un estado sin cámara, sobre el papel: qué pasó, qué hacer y `Escribir el código`. */
@Composable
private fun ScannerMessage(
    icon: Int,
    title: String,
    body: String?,
    primary: String,
    onPrimary: () -> Unit,
    onAction: (ScannerAction) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.padding(horizontal = AppTheme.spacing.s4)) {
            IconButton(onClick = { onAction(ScannerAction.Close) }) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.a11y_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        EmptyState(
            icon = icon,
            title = title,
            body = body,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        )
        Column(
            Modifier.keepsMessagesAbove().padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            PrimaryActionButton(primary, onPrimary, ActionVariant.PRIMARY)
            PrimaryActionButton(
                text = stringResource(R.string.scanner_type_code_action),
                onClick = { onAction(ScannerAction.TypeCode) },
                variant = ActionVariant.OUTLINED,
            )
        }
    }
}

/**
 * La hoja para escribir el código a mano. Con un lector externo también sirve: escribe aquí y
 * termina con Enter.
 */
@Composable
private fun TypeCodeSheet(onAction: (ScannerAction) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AppBottomSheet(
        onDismissRequest = { onAction(ScannerAction.CodeTyped(null)) },
        title = stringResource(R.string.scanner_type_code_action),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.action_continue),
                onClick = { onAction(ScannerAction.CodeTyped(code)) },
                variant = ActionVariant.PRIMARY,
                enabled = code.isNotBlank(),
            )
        },
    ) {
        AppTextField(
            value = code,
            onValueChange = { code = it },
            label = stringResource(R.string.scanner_barcode_label),
            modifier = Modifier.focusRequester(focus),
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Ascii,
            autoCorrect = false,
            keyboardActions =
                KeyboardActions(onDone = {
                    if (code.isNotBlank()) onAction(ScannerAction.CodeTyped(code))
                }),
        )
    }
}

// Desde la clase de ventana ancha, la cámara y el panel van lado a lado.
private val EXPANDED_WIDTH = 840.dp

// La franja de la barra de arriba: lo justo para leer el título sobre una imagen clara.
private const val BAR_ALPHA = 0.6f

@ThemePreviews
@Composable
private fun ScannerReadingPreview() {
    PreviewTheme {
        ScannerScreen(
            uiState =
                ScannerUiState(
                    continuous = true,
                    permission = CameraPermission.GRANTED,
                    camera = CameraPhase.RUNNING,
                    hasTorch = true,
                    readCount = 3,
                    lastRead = PreviewSamples.LONG_NAME,
                ),
            onAction = {},
            cameraContent = { Box(it.background(MaterialTheme.colorScheme.inverseSurface)) },
        )
    }
}

@ThemePreviews
@Composable
private fun ScannerDeniedPreview() {
    PreviewTheme {
        ScannerScreen(
            uiState =
                ScannerUiState(continuous = false, permission = CameraPermission.DENIED),
            onAction = {},
            cameraContent = {},
        )
    }
}

@ThemePreviews
@Composable
private fun ScannerFailedPreview() {
    PreviewTheme {
        ScannerScreen(
            uiState =
                ScannerUiState(
                    continuous = false,
                    permission = CameraPermission.GRANTED,
                    camera = CameraPhase.FAILED,
                ),
            onAction = {},
            cameraContent = {},
        )
    }
}
