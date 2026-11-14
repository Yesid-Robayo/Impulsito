package io.github.yesidrobayo.shopledger.ui.scanner

import android.Manifest
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El escáner con la cámara de verdad: la del emulador, que muestra una imagen inventada con un
 * código. Mide cuánto tarda desde pedir la cámara hasta tener el código, sin red.
 *
 * Solo corre si se le pasa el código que muestra la cámara, con
 * `-e scannerScene <código>`: un emulador con su cámara de siempre no tiene código que leer.
 */
@RunWith(AndroidJUnit4::class)
class ScannerCameraDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val expected: String? =
        InstrumentationRegistry.getArguments().getString(SCENE_ARGUMENT)

    @Before
    fun grantCamera() {
        assumeTrue("Sin escena de cámara", expected != null)
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )
    }

    @Test
    fun theBackCameraReadsTheInventedCode() {
        val delivered = CopyOnWriteArrayList<String>()
        val telemetry = RecordingTelemetry()
        val model =
            ScannerViewModel(
                continuous = false,
                deliver = { delivered += it },
                telemetry = telemetry,
            )

        compose.setContent {
            PreviewTheme {
                ScannerCamera(model, attempt = 0, torchOn = false, Modifier.fillMaxSize())
            }
        }
        compose.waitUntil(TIMEOUT_MILLIS) { delivered.isNotEmpty() }

        Log.i(
            TAG,
            "cámara abierta en ${telemetry.openedMillis} ms, primera lectura a los " +
                "${telemetry.firstReadMillis} ms, en el cuadro ${telemetry.firstReadFrame}",
        )
        assertEquals(expected, delivered.first())
    }

    /** Guarda los tiempos de la sesión, que es lo único que el escáner cuenta. */
    private class RecordingTelemetry : ScannerTelemetry {
        @Volatile var openedMillis: Long? = null

        @Volatile var firstReadMillis: Long? = null

        @Volatile var firstReadFrame: Int? = null

        override fun permissionAnswered(granted: Boolean) = Unit

        override fun cameraOpened(millis: Long) {
            openedMillis = millis
        }

        override fun cameraFailed(failure: Throwable) = Unit

        override fun cameraMissing() = Unit

        override fun firstRead(millis: Long, frame: Int, format: ScanFormat) {
            firstReadMillis = millis
            firstReadFrame = frame
        }

        override fun sessionClosed(frames: Int, reads: Int, maxDecodeMillis: Long) = Unit
    }

    private companion object {
        const val TAG = "ScannerCameraDeviceTest"
        const val SCENE_ARGUMENT = "scannerScene"
        const val TIMEOUT_MILLIS = 15_000L
    }
}
