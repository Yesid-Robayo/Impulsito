package io.github.yesidrobayo.shopledger.ui.encryption

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las capturas de `Cifrado de copias` para la puerta de calidad visual: apagado, activo, detenido y
 * cada paso de la activación, en claro y en oscuro con la paleta predeterminada. La escala de la
 * letra y el ancho de la ventana son los del teléfono. El código es el sintético de la
 * especificación.
 *
 * Solo corre con el argumento `captureDir`.
 */
@RunWith(AndroidJUnit4::class)
class EncryptionCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }
    private val groups = listOf("G7K2", "M9PQ", "R4ST", "V6WX", "Y8Z3", "ABCD", "EF51")

    @Test
    fun capturesEveryStateInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val states =
            listOf(
                "off" to EncryptionUiState(EncryptionStatusUi.Off(wasOn = false)),
                "off-was-on" to EncryptionUiState(EncryptionStatusUi.Off(wasOn = true)),
                "on" to EncryptionUiState(EncryptionStatusUi.On),
                "stopped" to EncryptionUiState(EncryptionStatusUi.KeyUnavailable),
                "step1" to
                    EncryptionUiState(EncryptionStatusUi.Off(false), ActivationStepUi.Risk(true)),
                "step2" to
                    EncryptionUiState(
                        EncryptionStatusUi.Off(false),
                        ActivationStepUi.ShowCode(groups, KitState.SAVED),
                    ),
                "step3" to
                    EncryptionUiState(
                        EncryptionStatusUi.Off(false),
                        ActivationStepUi.Retype(groups.toMutableList().also { it[1] = "M9PX" }, 1),
                    ),
                "step4" to
                    EncryptionUiState(EncryptionStatusUi.Off(false), ActivationStepUi.Testing()),
                "step4-failed" to
                    EncryptionUiState(
                        EncryptionStatusUi.Off(false),
                        ActivationStepUi.Testing(ActivationFailure.ROUND_TRIP),
                    ),
                "done" to EncryptionUiState(EncryptionStatusUi.On, ActivationStepUi.Done()),
                "change-confirm" to
                    EncryptionUiState(EncryptionStatusUi.On, confirmChange = true),
                "type-existing" to
                    EncryptionUiState(
                        EncryptionStatusUi.KeyUnavailable,
                        ActivationStepUi.TypeExisting(groups, ResealProblem.MISMATCH),
                    ),
                "done-changed" to
                    EncryptionUiState(EncryptionStatusUi.On, ActivationStepUi.Done(changed = true)),
                "view-code" to
                    EncryptionUiState(EncryptionStatusUi.On, codeView = CodeViewUi.Shown(groups)),
                "view-code-unavailable" to
                    EncryptionUiState(EncryptionStatusUi.On, codeView = CodeViewUi.Unavailable),
                "view-code-no-lock" to
                    EncryptionUiState(EncryptionStatusUi.On, confirmViewWithoutScreenLock = true),
            )
        for (dark in listOf(false, true)) {
            for ((name, state) in states) {
                show(state, dark)
                shoot(requireNotNull(dir), "$name-${if (dark) "dark" else "light"}")
            }
        }
    }

    private fun show(state: EncryptionUiState, dark: Boolean) {
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(LocalAppFormats provides previewFormats()) {
                        EncryptionScreen(state, onAction = {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shoot(dir: File, name: String) {
        compose.waitForIdle()
        SystemClock.sleep(SETTLE_MILLIS)
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 700L
    }
}
