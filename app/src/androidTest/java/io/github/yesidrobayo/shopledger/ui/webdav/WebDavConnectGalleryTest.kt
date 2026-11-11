package io.github.yesidrobayo.shopledger.ui.webdav

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestFailure
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestStep
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de `Conectar el servicio` para la puerta de calidad visual: vacía con una sugerencia,
 * probando, un paso que falló, una contraseña que no sirvió, `¿Es este el servicio?` y `¿Cambiar
 * dónde guardar?`. Datos inventados. La letra y la ventana son las del sistema. Solo corre con
 * `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class WebDavConnectGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsTheConnectionInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val scenes = listOf(
            "empty" to WebDavConnectUiState(suggestions = listOf(PREVIEW_SUGGESTION)),
            "testing" to PREVIEW_FILLED.copy(
                phase = WebDavPhase.TESTING,
                steps = previewSteps(WebDavTestStep.MOVE),
            ),
            "failed" to PREVIEW_FILLED.copy(
                steps = previewSteps(WebDavTestStep.NO_OVERWRITE, failed = true),
                notice =
                    WebDavNotice.Failed(WebDavTestStep.NO_OVERWRITE, WebDavTestFailure.UNSUPPORTED),
            ),
            "password" to PREVIEW_FILLED.copy(
                suggestion = null,
                passwordRejected = true,
                steps = previewSteps(WebDavTestStep.LIST, failed = true),
            ),
            "host" to
                PREVIEW_FILLED.copy(phase = WebDavPhase.CONFIRM_HOST, host = "dav.example.com"),
            "change" to
                PREVIEW_FILLED.copy(phase = WebDavPhase.CONFIRM_CHANGE, changeFrom = "Drive"),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                for ((name, state) in scenes) {
                    scenario.onActivity { activity ->
                        val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                        activity.setContent {
                            AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                                CompositionLocalProvider(
                                    LocalAppFormats provides previewFormats(),
                                    LocalDiscreetMode provides false,
                                ) {
                                    key(name, dark) {
                                        WebDavConnectScreen(state, onAction = {})
                                    }
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(SETTLE_MILLIS)
                    val mode = if (dark) "dark" else "light"
                    save(File(requireNotNull(dir), "$mode-webdav-$name.png"))
                }
            }
        }
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}
