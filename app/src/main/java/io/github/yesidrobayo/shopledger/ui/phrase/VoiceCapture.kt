package io.github.yesidrobayo.shopledger.ui.phrase

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheetContent
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Un pedido de escuchar una frase, que [VoiceCaptureHost] atiende: un pedido a la vez, y un toque
 * repetido mientras se atiende no abre otro.
 */
@Stable
class VoiceCapture {
    internal var pending: ((VoiceResult) -> Unit)? by mutableStateOf(null)
        private set

    /** Cambia con cada pedido, para que el anfitrión empiece de cero. */
    internal var request: Int by mutableIntStateOf(0)
        private set

    /** Si hay un pedido en curso. */
    val active: Boolean get() = pending != null

    /** Escucha una frase y da el resultado a [onResult], una sola vez. */
    fun start(onResult: (VoiceResult) -> Unit) {
        if (pending != null) return
        pending = onResult
        request++
    }

    internal fun finish(result: VoiceResult) {
        val callback = pending ?: return
        pending = null
        callback(result)
    }
}

/** El paso en que va un pedido de escuchar. */
private enum class CapturePhase {
    /** Mirando el ajuste, el servicio de voz y el permiso, sin dibujar nada. */
    CHECKING,

    /** La explicación propia antes de pedir el micrófono. */
    EXPLAIN,

    /** Esperando la respuesta de Android al permiso. */
    ASKING,

    /** La pantalla de escuchar. */
    LISTENING,
}

/** La fase de un pedido: la de uno anterior nunca se hereda, y el nuevo empieza mirando. */
private data class CaptureStep(val request: Int, val phase: CapturePhase)

/**
 * Atiende los pedidos de [capture]: decide si se puede escuchar, pide el micrófono si hace falta y
 * muestra la pantalla de escuchar.
 *
 * - **Sin ajuste o sin servicio de voz,** termina de una vez con el teclado.
 * - **Sin permiso, la primera vez,** explica antes con una hoja propia y luego pide el de Android.
 *   Si la persona lo niega, queda el teclado con un aviso. Si Android ya no deja preguntar, no
 *   pregunta: el aviso ofrece abrir los ajustes del teléfono, solo si la persona lo toca.
 * - **Con permiso,** escucha.
 *
 * @param offerTyping la pantalla de escuchar ofrece `Escribir`.
 */
@Composable
fun VoiceCaptureHost(capture: VoiceCapture, offerTyping: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext
    val container = (app as ShopLedgerApp).container
    val preference = container.voiceInput
    val languageTag = LocalAppFormats.current.uiLocale.toLanguageTag()
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(CaptureStep(0, CapturePhase.CHECKING)) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!capture.active) return@rememberLauncherForActivityResult
            if (granted) {
                step = CaptureStep(capture.request, CapturePhase.LISTENING)
            } else {
                capture.finish(VoiceResult.Keyboard(VoiceNotice.NO_PERMISSION))
            }
        }
    val request = capture.request
    val active = capture.active
    LaunchedEffect(request, active) {
        if (!active) return@LaunchedEffect
        val available =
            withContext(container.dispatchers.io) { PhoneSpeechEngine.isAvailable(app) }
        if (!available || !preference.load()) {
            capture.finish(VoiceResult.Keyboard(null))
            return@LaunchedEffect
        }
        if (context.hasMicrophone()) {
            step = CaptureStep(request, CapturePhase.LISTENING)
            return@LaunchedEffect
        }
        // Sin explicación de Android y ya preguntado: la persona eligió no volver a preguntar.
        val activity = context.findActivity()
        val rationale =
            activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.RECORD_AUDIO,
                )
        if (preference.micAsked() && !rationale) {
            capture.finish(VoiceResult.Keyboard(VoiceNotice.PERMISSION_BLOCKED))
        } else {
            step = CaptureStep(request, CapturePhase.EXPLAIN)
        }
    }
    if (!active) return
    val phase = if (step.request == request) step.phase else CapturePhase.CHECKING
    when (phase) {
        CapturePhase.CHECKING, CapturePhase.ASKING -> Unit

        CapturePhase.EXPLAIN ->
            MicPermissionSheet(
                onAllow = {
                    step = CaptureStep(request, CapturePhase.ASKING)
                    scope.launch { preference.markMicAsked() }
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onType = { capture.finish(VoiceResult.Keyboard(null)) },
                onDismiss = { capture.finish(VoiceResult.Cancelled) },
            )

        CapturePhase.LISTENING -> {
            val engine = remember(request) { PhoneSpeechEngine(app, languageTag) }
            ListeningSheet(engine, offerTyping, onEnd = capture::finish)
        }
    }
}

/**
 * La hoja que explica el micrófono antes de que Android lo pida: para qué se usa, cuándo, y que el
 * servicio de voz es del teléfono y puede usar internet. `Escribir` deja el teclado sin pedir nada.
 */
@Composable
internal fun MicPermissionSheet(onAllow: () -> Unit, onType: () -> Unit, onDismiss: () -> Unit) {
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.permission_mic_title),
        action = { MicPermissionActions(onAllow, onType) },
    ) {
        MicPermissionBody()
    }
}

@Composable
private fun MicPermissionBody() {
    Text(
        stringResource(R.string.permission_mic_body, stringResource(R.string.app_name)),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MicPermissionActions(onAllow: () -> Unit, onType: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        PrimaryActionButton(
            text = stringResource(R.string.permission_mic_allow_action),
            onClick = onAllow,
            variant = ActionVariant.PRIMARY,
        )
        PrimaryActionButton(
            text = stringResource(R.string.listening_type),
            onClick = onType,
            variant = ActionVariant.OUTLINED,
        )
    }
}

/** Los ajustes del sistema de esta app, donde la persona da el micrófono. Solo por su toque. */
internal fun Context.openMicrophoneSettings() {
    try {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    } catch (_: ActivityNotFoundException) {
        // Un teléfono sin esa pantalla: el aviso sigue a la vista y el teclado funciona.
    }
}

private fun Context.hasMicrophone(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@ThemePreviews
@Composable
private fun MicPermissionPreview() {
    PreviewTheme {
        AppBottomSheetContent(
            title = stringResource(R.string.permission_mic_title),
            action = { MicPermissionActions({}, {}) },
        ) {
            MicPermissionBody()
        }
    }
}
