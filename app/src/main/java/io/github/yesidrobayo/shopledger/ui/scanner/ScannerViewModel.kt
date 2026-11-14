package io.github.yesidrobayo.shopledger.ui.scanner

import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * El estado del escáner.
 *
 * Cada apertura de la cámara es una sesión, con su propio reloj desde que se pide la cámara. Cada
 * código tomado, de la cámara o escrito a mano, sale por [deliver] hacia la pantalla que abrió el
 * escáner. A [telemetry] solo van tiempos, conteos y estados: nunca el texto de un código.
 *
 * Los cuadros llegan por el hilo del análisis; el resto, por el principal. El estado es un
 * `StateFlow`, así que los dos hilos lo actualizan sin pisarse.
 *
 * @param continuous si sigue leyendo después del primer código.
 * @param deliver recibe cada código tomado, en orden.
 * @param telemetry recibe los tiempos y conteos de cada sesión, para el registro.
 * @param nanoTime el reloj monótono de las sesiones.
 * @param slowHintMillis cuánto espera sin leer antes de sugerir acercar el código.
 */
class ScannerViewModel(
    continuous: Boolean,
    private val deliver: (String) -> Unit,
    private val telemetry: ScannerTelemetry,
    private val nanoTime: () -> Long = System::nanoTime,
    private val slowHintMillis: Long = SLOW_HINT_MILLIS,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ScannerUiState(continuous = continuous))
    val uiState: StateFlow<ScannerUiState> = mutableState.asStateFlow()

    private val gate = DuplicateGate()
    private var session: Session? = null
    private var slowHint: Job? = null

    /** Al entrar y al volver de los ajustes del sistema, donde el permiso pudo cambiar. */
    fun onPermissionChecked(granted: Boolean) {
        mutableState.update { state ->
            val permission =
                when {
                    granted -> CameraPermission.GRANTED

                    // Negado en esta visita: sigue negado hasta que los ajustes lo cambien.
                    state.permission == CameraPermission.DENIED -> CameraPermission.DENIED

                    else -> CameraPermission.NOT_REQUESTED
                }
            state.copy(permission = permission)
        }
    }

    fun onPermissionResult(granted: Boolean) {
        telemetry.permissionAnswered(granted)
        mutableState.update {
            val permission = if (granted) CameraPermission.GRANTED else CameraPermission.DENIED
            it.copy(permission = permission)
        }
    }

    fun onAction(action: ScannerAction) {
        when (action) {
            ScannerAction.Close -> mutableState.update { it.copy(closed = true) }

            ScannerAction.Retry ->
                mutableState.update {
                    it.copy(camera = CameraPhase.OPENING, cameraAttempt = it.cameraAttempt + 1)
                }

            ScannerAction.ToggleTorch ->
                mutableState.update { it.copy(torchOn = it.hasTorch && !it.torchOn) }

            ScannerAction.TypeCode -> mutableState.update { it.copy(typingCode = true) }

            is ScannerAction.CodeTyped -> {
                mutableState.update { it.copy(typingCode = false) }
                action.code?.trim()?.takeIf { it.isNotEmpty() }?.let(::take)
            }

            // Los resuelve la ruta, que tiene el lanzador del permiso y el contexto.
            ScannerAction.AllowCamera, ScannerAction.OpenSettings -> Unit
        }
    }

    /**
     * Empieza una sesión: su reloj corre desde aquí, antes de pedir la cámara. Devuelve el
     * analizador de los cuadros de esta sesión.
     */
    fun openSession(): ImageAnalysis.Analyzer =
        BarcodeFrameAnalyzer(onFrame = openFrameSink(), nanoTime = nanoTime)

    /**
     * Empieza una sesión y devuelve a dónde van sus cuadros. Los de una sesión ya cerrada se
     * descartan: la cámara pudo entregar uno más mientras se soltaba.
     */
    internal fun openFrameSink(): (FrameOutcome) -> Unit {
        val opened = Session(openedAtNanos = nanoTime())
        synchronized(this) { session = opened }
        mutableState.update { it.copy(camera = CameraPhase.OPENING, torchOn = false) }
        return { onFrame(opened, it) }
    }

    /** La cámara quedó atada y entrega cuadros. [hasTorch] dice si se ofrece la linterna. */
    fun onCameraBound(hasTorch: Boolean) {
        val elapsed = synchronized(this) { session?.let { nanoTime() - it.openedAtNanos } }
        elapsed?.let {
            telemetry.cameraOpened(it / NANOS_PER_MILLI)
        }
        mutableState.update { it.copy(camera = CameraPhase.RUNNING, hasTorch = hasTorch) }
        restartSlowHint()
    }

    /** La cámara no abrió. Solo se registra la clase de [failure], si la hay. */
    fun onCameraFailed(failure: Throwable?) {
        if (failure != null) {
            telemetry.cameraFailed(failure)
        } else {
            telemetry.cameraMissing()
        }
        mutableState.update { it.copy(camera = CameraPhase.FAILED, torchOn = false) }
        slowHint?.cancel()
    }

    /** La cámara se soltó: el escáner dejó de verse o la persona salió. */
    fun closeSession() {
        val closed = synchronized(this) { session.also { session = null } } ?: return
        val maxDecode = closed.maxDecodeNanos / NANOS_PER_MILLI
        telemetry.sessionClosed(closed.frames, closed.reads, maxDecode)
        slowHint?.cancel()
        mutableState.update { state ->
            // Un fallo se queda en pantalla hasta que la persona reintente.
            val camera =
                when (state.camera) {
                    CameraPhase.OPENING, CameraPhase.RUNNING -> CameraPhase.OPENING
                    CameraPhase.FAILED -> CameraPhase.FAILED
                }
            state.copy(camera = camera, torchOn = false, slowHint = false)
        }
    }

    /** Un cuadro leído, en el hilo del análisis. */
    private fun onFrame(owner: Session, outcome: FrameOutcome) {
        val code = outcome.code
        val accepted =
            synchronized(this) {
                if (session !== owner) return
                owner.frames++
                owner.maxDecodeNanos = maxOf(owner.maxDecodeNanos, outcome.decodeNanos)
                if (code == null || mutableState.value.closed) return
                gate.accept(code, outcome.arrivedAtNanos + outcome.decodeNanos).also { taken ->
                    if (taken && owner.reads++ == 0) {
                        val readAt = outcome.arrivedAtNanos + outcome.decodeNanos
                        val elapsed = readAt - owner.openedAtNanos
                        telemetry.firstRead(elapsed / NANOS_PER_MILLI, owner.frames, code.format)
                    }
                }
            }
        if (accepted && code != null) viewModelScope.launch { take(code.text) }
    }

    /** Toma un código: lo entrega, lo cuenta y, si no es lectura continua, cierra el escáner. */
    private fun take(code: String) {
        if (mutableState.value.closed) return
        deliver(code)
        mutableState.update {
            it.copy(
                readCount = it.readCount + 1,
                lastRead = code,
                readPulse = it.readPulse + 1,
                slowHint = false,
                closed = !it.continuous,
            )
        }
        restartSlowHint()
    }

    private fun restartSlowHint() {
        slowHint?.cancel()
        if (mutableState.value.camera != CameraPhase.RUNNING) return
        slowHint =
            viewModelScope.launch {
                delay(slowHintMillis)
                mutableState.update { it.copy(slowHint = true) }
            }
    }

    /** Los conteos de una apertura de la cámara. Los cambia solo el hilo del análisis. */
    private class Session(val openedAtNanos: Long) {
        var frames = 0
        var reads = 0
        var maxDecodeNanos = 0L
    }

    companion object {
        /** Ocho segundos sin leer antes de sugerir acercar el código: un valor de diseño. */
        const val SLOW_HINT_MILLIS = 8_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
