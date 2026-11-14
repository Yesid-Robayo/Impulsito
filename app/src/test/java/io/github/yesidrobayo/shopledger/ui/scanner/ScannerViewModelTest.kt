package io.github.yesidrobayo.shopledger.ui.scanner

import io.github.yesidrobayo.shopledger.di.ScannerTelemetryLog
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El escáner pide la cámara solo tras explicarla, entrega cada código una vez a quien lo abrió,
 * deja escribir el código sin cámara y nunca registra el texto de un código.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val delivered = mutableListOf<String>()
    private val log = TextLog()
    private var now = 0L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(continuous: Boolean) = ScannerViewModel(
        continuous = continuous,
        deliver = { delivered += it },
        telemetry = ScannerTelemetryLog(log),
        nanoTime = { now },
    )

    @Test
    fun withoutPermissionTheSheetComesFirstAndNothingIsAsked() {
        val model = model(continuous = false)

        model.onPermissionChecked(granted = false)

        assertEquals(CameraPermission.NOT_REQUESTED, model.uiState.value.permission)
    }

    @Test
    fun aDenialStaysUntilTheSettingsChangeIt() {
        val model = model(continuous = false)
        model.onPermissionChecked(granted = false)

        model.onPermissionResult(granted = false)
        model.onPermissionChecked(granted = false)
        assertEquals(CameraPermission.DENIED, model.uiState.value.permission)

        // Volvió de los ajustes con la cámara activada.
        model.onPermissionChecked(granted = true)
        assertEquals(CameraPermission.GRANTED, model.uiState.value.permission)
    }

    @Test
    fun aSingleReadDeliversOnceAndCloses() = runTest(dispatcher) {
        val model = model(continuous = false)
        val frames = model.openFrameSink()
        model.onCameraBound(hasTorch = false)

        frames(frame(EAN))
        frames(frame(OWN))
        runCurrent()
        frames(frame(OWN))
        runCurrent()

        assertEquals(listOf(EAN.text), delivered)
        assertTrue(model.uiState.value.closed)
    }

    @Test
    fun continuousReadingCountsEachProductOnce() = runTest(dispatcher) {
        val model = model(continuous = true)
        val frames = model.openFrameSink()
        model.onCameraBound(hasTorch = false)

        // El mismo código quieto durante un segundo, y luego otro.
        repeat(10) {
            frames(frame(EAN))
            now += MS_100
        }
        frames(frame(OWN))
        runCurrent()

        val state = model.uiState.value
        assertEquals(listOf(EAN.text, OWN.text), delivered)
        assertEquals(2, state.readCount)
        assertEquals(OWN.text, state.lastRead)
        assertEquals(2, state.readPulse)
        assertFalse(state.closed)
    }

    @Test
    fun framesWithoutACodeDeliverNothing() = runTest(dispatcher) {
        val model = model(continuous = true)
        val frames = model.openFrameSink()

        repeat(5) { frames(frame(null)) }
        runCurrent()

        assertTrue(delivered.isEmpty())
        assertEquals(0, model.uiState.value.readCount)
    }

    @Test
    fun aClosedSessionDiscardsLateFrames() = runTest(dispatcher) {
        val model = model(continuous = true)
        val frames = model.openFrameSink()
        model.onCameraBound(hasTorch = false)

        model.closeSession()
        frames(frame(EAN))
        runCurrent()

        assertTrue(delivered.isEmpty())
        assertEquals(CameraPhase.OPENING, model.uiState.value.camera)
    }

    @Test
    fun aTypedCodeIsDeliveredTrimmed() = runTest(dispatcher) {
        val model = model(continuous = false)

        model.onAction(ScannerAction.TypeCode)
        assertTrue(model.uiState.value.typingCode)
        model.onAction(ScannerAction.CodeTyped("  P00000042 "))

        assertEquals(listOf("P00000042"), delivered)
        assertFalse(model.uiState.value.typingCode)
        assertTrue(model.uiState.value.closed)
    }

    @Test
    fun aBlankOrCancelledCodeDeliversNothing() {
        val model = model(continuous = false)

        model.onAction(ScannerAction.TypeCode)
        model.onAction(ScannerAction.CodeTyped("   "))
        model.onAction(ScannerAction.TypeCode)
        model.onAction(ScannerAction.CodeTyped(null))

        assertTrue(delivered.isEmpty())
        assertFalse(model.uiState.value.typingCode)
        assertFalse(model.uiState.value.closed)
    }

    @Test
    fun aLongWaitSuggestsMovingCloserAndAReadClearsIt() = runTest(dispatcher) {
        val model = model(continuous = true)
        val frames = model.openFrameSink()
        model.onCameraBound(hasTorch = true)

        advanceTimeBy(ScannerViewModel.SLOW_HINT_MILLIS - 1)
        assertFalse(model.uiState.value.slowHint)
        advanceTimeBy(2)
        assertTrue(model.uiState.value.slowHint)

        frames(frame(EAN))
        runCurrent()
        assertFalse(model.uiState.value.slowHint)
    }

    @Test
    fun aCameraThatDoesNotOpenOffersRetry() {
        val model = model(continuous = false)
        model.openFrameSink()

        model.onCameraFailed(IllegalStateException("cámara ocupada"))
        assertEquals(CameraPhase.FAILED, model.uiState.value.camera)
        // Soltar la sesión no borra el fallo: sigue en pantalla hasta reintentar.
        model.closeSession()
        assertEquals(CameraPhase.FAILED, model.uiState.value.camera)

        model.onAction(ScannerAction.Retry)
        assertEquals(CameraPhase.OPENING, model.uiState.value.camera)
        assertEquals(1, model.uiState.value.cameraAttempt)
    }

    @Test
    fun theTorchOnlyTurnsOnWhenTheCameraHasOne() = runTest(dispatcher) {
        val withoutTorch = model(continuous = false)
        withoutTorch.openFrameSink()
        withoutTorch.onCameraBound(hasTorch = false)
        withoutTorch.onAction(ScannerAction.ToggleTorch)
        assertFalse(withoutTorch.uiState.value.torchOn)

        val withTorch = model(continuous = false)
        withTorch.openFrameSink()
        withTorch.onCameraBound(hasTorch = true)
        withTorch.onAction(ScannerAction.ToggleTorch)
        assertTrue(withTorch.uiState.value.torchOn)
        // Soltar la cámara la apaga.
        withTorch.closeSession()
        assertFalse(withTorch.uiState.value.torchOn)
    }

    @Test
    fun theLogKeepsTimesAndCountsButNeverTheCode() = runTest(dispatcher) {
        val model = model(continuous = true)
        val frames = model.openFrameSink()
        now += 120 * MS
        model.onCameraBound(hasTorch = false)
        frames(frame(EAN, decodeNanos = 3 * MS))
        runCurrent()
        model.onAction(ScannerAction.CodeTyped(OWN.text))
        model.onCameraFailed(IllegalStateException(EAN.text))
        model.closeSession()

        assertFalse(log.text.contains(EAN.text))
        assertFalse(log.text.contains(OWN.text))
        assertTrue(log.text, log.text.contains("Cámara del escáner abierta: desde_pedir=120 ms"))
        assertTrue(log.text, log.text.contains("Primera lectura del escáner: desde_pedir=123 ms"))
        assertTrue(log.text, log.text.contains("cuadros=1, lecturas=1, lectura_maxima=3 ms"))
    }

    private fun frame(code: ScannedCode?, decodeNanos: Long = MS) =
        FrameOutcome(arrivedAtNanos = now, decodeNanos = decodeNanos, code = code)

    private class TextLog : EventLog {
        private val lines = mutableListOf<String>()
        val text: String get() = lines.joinToString("\n")

        override fun write(level: LogLevel, line: LogLine) {
            lines += "$level ${line.text}"
        }
    }

    private companion object {
        val EAN = ScannedCode(ScanFormat.EAN_13, "2900000123458")
        val OWN = ScannedCode(ScanFormat.CODE_128, "P00000042")
        const val MS = 1_000_000L
        const val MS_100 = 100 * MS
    }
}
