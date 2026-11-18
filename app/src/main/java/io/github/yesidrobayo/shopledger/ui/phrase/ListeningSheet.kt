package io.github.yesidrobayo.shopledger.ui.phrase

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheetContent
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.theme.AppMotion
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Lleva [ListeningMachine] con un [SpeechEngine]: cada aviso del motor o toque de la persona pasa
 * por la máquina, y lo que ella pide se hace en el motor. Al terminar, avisa una sola vez a
 * [onEnd].
 */
@Stable
internal class ListeningDriver(
    private val engine: SpeechEngine,
    private val onEnd: (VoiceResult) -> Unit,
) {
    private val machine = ListeningMachine(engine.plans)

    var state: ListeningState by mutableStateOf(ListeningState.Idle)
        private set

    /** El volumen de la voz, de 0 a 1, solo mientras se escucha. */
    var level: Float by mutableFloatStateOf(0f)
        private set

    /** Se tocó `Escribir`: terminar por ahí abre el teclado, no cierra todo. */
    private var typing = false

    fun dispatch(event: ListenEvent) {
        val before = state
        val step = machine.next(before, event)
        if (step.state == before && step.command == ListenCommand.None) return
        state = step.state
        if (step.state !is ListeningState.Listening) level = 0f
        when (val command = step.command) {
            ListenCommand.None -> Unit

            is ListenCommand.Listen ->
                engine.listen(command.plan, ::dispatch) { heard ->
                    if (state is ListeningState.Listening) level = heard
                }

            ListenCommand.Stop -> engine.stop()

            ListenCommand.Release -> engine.release()
        }
        if (!before.isFinal && step.state.isFinal) onEnd(resultOf(step.state))
    }

    /** `Escribir`: suelta el micrófono y abre la hoja con el teclado. */
    fun type() {
        typing = true
        dispatch(ListenEvent.Cancel)
    }

    private fun resultOf(state: ListeningState): VoiceResult = when (state) {
        is ListeningState.Heard -> VoiceResult.Heard(state.text)

        // El permiso de la app ya se dio antes de escuchar: si falta uno, es el de algún servicio.
        is ListeningState.Failed ->
            VoiceResult.Keyboard(
                if (state.micDenied) VoiceNotice.SERVICE_NO_MIC else VoiceNotice.FAILED,
            )

        ListeningState.Cancelled ->
            if (typing) VoiceResult.Keyboard(null) else VoiceResult.Cancelled

        // Nunca llegan aquí: no son finales.
        ListeningState.Idle,
        is ListeningState.Starting,
        is ListeningState.Listening,
        is ListeningState.Finishing,
        is ListeningState.Silent,
        -> VoiceResult.Cancelled
    }
}

/**
 * La pantalla de escuchar: una hoja propia de la app, sin la ventana del servicio de voz. Un
 * micrófono grande que late con la voz, lo que se va entendiendo debajo, y `Listo` y `Cancelar`.
 *
 * Empieza a escuchar al aparecer. El micrófono se usa solo mientras se ve: cerrar la hoja, atrás o
 * salir de la app lo apagan. Lo que termina va a [onEnd] una sola vez: la frase oída, el teclado
 * con un aviso si no se pudo, o nada si la persona canceló.
 *
 * @param offerTyping ofrece `Escribir`, que abre la hoja con el teclado. Desde la hoja misma no
 *   hace falta: cancelar ya deja el teclado.
 */
@Composable
internal fun ListeningSheet(
    engine: SpeechEngine,
    offerTyping: Boolean,
    onEnd: (VoiceResult) -> Unit,
) {
    val latestOnEnd by rememberUpdatedState(onEnd)
    val driver = remember(engine) { ListeningDriver(engine) { latestOnEnd(it) } }
    LaunchedEffect(driver) { driver.dispatch(ListenEvent.Start) }
    DisposableEffect(driver) { onDispose { engine.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { driver.dispatch(ListenEvent.Cancel) }
    val state = driver.state
    AppBottomSheet(
        onDismissRequest = { driver.dispatch(ListenEvent.Cancel) },
        title = null,
        scrollable = true,
        action = {
            ListeningActions(
                state = state,
                onFinish = { driver.dispatch(ListenEvent.Finish) },
                onCancel = { driver.dispatch(ListenEvent.Cancel) },
                onAgain = { driver.dispatch(ListenEvent.Start) },
                onType = driver::type,
            )
        },
    ) {
        ListeningBody(state, driver.level, offerTyping, onType = driver::type)
    }
}

/** El micrófono, el estado y lo que se va entendiendo, centrados. */
@Composable
private fun ListeningBody(
    state: ListeningState,
    level: Float,
    offerTyping: Boolean,
    onType: () -> Unit,
) {
    val spacing = AppTheme.spacing
    val silent = state is ListeningState.Silent
    val partial =
        when (state) {
            is ListeningState.Listening -> state.partial

            is ListeningState.Finishing -> state.partial

            is ListeningState.Heard -> state.text

            ListeningState.Idle,
            is ListeningState.Starting,
            is ListeningState.Silent,
            is ListeningState.Failed,
            ListeningState.Cancelled,
            -> ""
        }
    Column(
        Modifier.fillMaxWidth().padding(vertical = spacing.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        Text(
            text =
                stringResource(
                    if (silent) R.string.listening_silent_title else R.string.listening_title,
                ),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier =
                Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
        )
        ListeningMic(
            active = state is ListeningState.Listening,
            muted = silent,
            level = level,
        )
        if (partial.isNotEmpty()) {
            // Lo que se va entendiendo, grande: se lee de un vistazo mientras se habla.
            Text(
                text = partial,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(PARTIAL_TAG),
            )
        } else {
            Text(
                text =
                    if (silent) {
                        stringResource(R.string.listening_silent_body)
                    } else {
                        stringResource(
                            R.string.listening_hint,
                            stringResource(R.string.phrase_example_sale),
                        )
                    },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (offerTyping && !silent) {
            InlineTextButton(text = stringResource(R.string.listening_type), onClick = onType)
        }
    }
}

/**
 * El micrófono en el relleno de marca, como `Decir` en la barra, con un halo que crece con la voz y
 * respira mientras se escucha. Preparándose queda quieto; sin frase, apagado. Con las animaciones
 * quitadas en el teléfono, solo el halo sigue la voz, sin respirar.
 */
@Composable
private fun ListeningMic(active: Boolean, muted: Boolean, level: Float) {
    val sizes = AppTheme.sizes
    val colors = AppTheme.colors
    val scheme = MaterialTheme.colorScheme
    val animated = remember { ValueAnimator.areAnimatorsEnabled() }
    val voice by animateFloatAsState(
        targetValue = if (active) level else 0f,
        animationSpec = tween(AppMotion.FAST),
        label = "listeningVoice",
    )
    val breath =
        if (active && animated) {
            val transition = rememberInfiniteTransition(label = "listeningBreath")
            val value by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(AppMotion.SKELETON_CYCLE, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "listeningBreathValue",
            )
            value
        } else {
            0f
        }
    val haloScale = HALO_REST + (1f - HALO_REST) * maxOf(voice, breath * BREATH_SHARE)
    Box(Modifier.size(sizes.listeningHalo), contentAlignment = Alignment.Center) {
        if (!muted) {
            Box(
                Modifier
                    .size(sizes.listeningHalo)
                    .scale(haloScale)
                    .clip(CircleShape)
                    .background(colors.brandFill.copy(alpha = HALO_ALPHA)),
            )
        }
        Box(
            Modifier
                .size(sizes.listeningMic)
                .clip(CircleShape)
                .background(if (muted) scheme.surfaceContainerHighest else colors.brandFill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                // El estado ya lo dice el título, que TalkBack lee al cambiar.
                contentDescription = null,
                tint = if (muted) scheme.onSurfaceVariant else colors.onBrandFill,
                modifier = Modifier.size(sizes.heroIcon),
            )
        }
    }
}

/** `Listo` y `Cancelar` mientras se escucha; `Escuchar otra vez` y `Escribir` sin frase. */
@Composable
private fun ListeningActions(
    state: ListeningState,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onAgain: () -> Unit,
    onType: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        if (state is ListeningState.Silent) {
            PrimaryActionButton(
                text = stringResource(R.string.listening_again),
                onClick = onAgain,
                variant = ActionVariant.PRIMARY,
            )
            PrimaryActionButton(
                text = stringResource(R.string.listening_type),
                onClick = onType,
                variant = ActionVariant.OUTLINED,
            )
        } else {
            PrimaryActionButton(
                text = stringResource(R.string.phrase_done),
                onClick = onFinish,
                variant = ActionVariant.PRIMARY,
                state =
                    if (state is ListeningState.Finishing) {
                        ActionState.WORKING
                    } else {
                        ActionState.IDLE
                    },
            )
            PrimaryActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onCancel,
                variant = ActionVariant.OUTLINED,
            )
        }
    }
}

/** La marca del texto que se va entendiendo, para las pruebas. */
internal const val PARTIAL_TAG = "listeningPartial"

/** El halo quieto, como parte de su tamaño completo. */
private const val HALO_REST = 0.62f

/** Cuánto del halo mueve la respiración sola, sin voz. */
private const val BREATH_SHARE = 0.35f

private const val HALO_ALPHA = 0.18f

@Composable
private fun ListeningPreview(
    state: ListeningState,
    level: Float = 0f,
    offerTyping: Boolean = true,
) {
    PreviewTheme {
        AppBottomSheetContent(
            title = null,
            action = { ListeningActions(state, {}, {}, {}, {}) },
        ) {
            ListeningBody(state, level, offerTyping, onType = {})
        }
    }
}

@ThemePreviews
@Composable
private fun ListeningStartingPreview() {
    ListeningPreview(ListeningState.Starting(0))
}

@ThemePreviews
@Composable
private fun ListeningPartialPreview() {
    ListeningPreview(
        ListeningState.Listening(0, PhraseSamples.LONG_HEARD),
        level = 0.7f,
    )
}

@ThemePreviews
@Composable
private fun ListeningFinishingPreview() {
    ListeningPreview(ListeningState.Finishing(0, PhraseSamples.LONG_HEARD), offerTyping = false)
}

@ThemePreviews
@Composable
private fun ListeningSilentPreview() {
    ListeningPreview(ListeningState.Silent(0))
}
