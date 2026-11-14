package io.github.yesidrobayo.shopledger.ui.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.A11yAudit
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los estados del escáner dibujados en el teléfono, sin cámara: la hoja que explica el permiso, el
 * permiso negado, la cámara que no abre y la lectura continua. En todos se puede escribir el
 * código, y cada uno pasa la auditoría de accesibilidad.
 */
@RunWith(AndroidJUnit4::class)
class ScannerScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<ScannerAction>()

    private fun show(state: ScannerUiState) {
        compose.setContent {
            PreviewTheme {
                ScannerScreen(
                    uiState = state,
                    onAction = { actions += it },
                    cameraContent = {
                        Box(it.background(MaterialTheme.colorScheme.inverseSurface))
                    },
                )
            }
        }
    }

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun withoutPermissionASheetExplainsBeforeAsking() {
        show(ScannerUiState(continuous = false, permission = CameraPermission.NOT_REQUESTED))

        compose.onNodeWithText(text(R.string.permission_camera_body)).assertIsDisplayed()
        A11yAudit(compose).assertClean("hoja del permiso")
        compose.onNodeWithText(text(R.string.permission_camera_allow_action)).performClick()

        assertEquals(listOf<ScannerAction>(ScannerAction.AllowCamera), actions)
    }

    @Test
    fun aDeniedCameraOffersSettingsAndTypingTheCode() {
        show(ScannerUiState(continuous = false, permission = CameraPermission.DENIED))

        val title = resources.getString(R.string.scanner_denied_title, text(R.string.app_name))
        compose.onNodeWithText(title).assertIsDisplayed()
        A11yAudit(compose).assertClean("permiso negado")
        compose.onNodeWithText(text(R.string.action_open_settings)).performClick()
        compose.onNodeWithText(text(R.string.scanner_type_code_action)).performClick()

        assertEquals(listOf(ScannerAction.OpenSettings, ScannerAction.TypeCode), actions)
    }

    @Test
    fun aCameraThatDoesNotOpenOffersRetry() {
        show(
            ScannerUiState(
                continuous = false,
                permission = CameraPermission.GRANTED,
                camera = CameraPhase.FAILED,
            ),
        )

        compose.onNodeWithText(text(R.string.scanner_error_title)).assertIsDisplayed()
        A11yAudit(compose).assertClean("cámara sin abrir")
        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertEquals(listOf<ScannerAction>(ScannerAction.Retry), actions)
    }

    @Test
    fun continuousReadingShowsTheCountTheLastAndFinish() {
        show(
            ScannerUiState(
                continuous = true,
                permission = CameraPermission.GRANTED,
                camera = CameraPhase.RUNNING,
                hasTorch = true,
                readCount = 3,
                lastRead = SAMPLE_CODE,
                readPulse = 3,
            ),
        )

        compose.onNodeWithText(
            resources.getQuantityString(R.plurals.scanner_read_count, 3, 3),
        ).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.scanner_last, SAMPLE_CODE))
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.a11y_scanner_preview))
            .assertIsDisplayed()
        A11yAudit(compose).assertClean("lectura continua")
        compose.onNodeWithContentDescription(text(R.string.a11y_scanner_flash_on)).performClick()
        compose.onNodeWithText(text(R.string.scanner_finish_action)).performClick()

        assertEquals(listOf(ScannerAction.ToggleTorch, ScannerAction.Close), actions)
    }

    @Test
    fun aTypedCodeIsSentFromItsSheet() {
        show(
            ScannerUiState(
                continuous = false,
                permission = CameraPermission.DENIED,
                typingCode = true,
            ),
        )

        compose.onNodeWithText(text(R.string.scanner_barcode_label)).performTextInput(SAMPLE_CODE)
        A11yAudit(compose).assertClean("escribir el código")
        compose.onNodeWithText(text(R.string.action_continue)).performClick()

        assertEquals(listOf<ScannerAction>(ScannerAction.CodeTyped(SAMPLE_CODE)), actions)
    }

    private companion object {
        const val SAMPLE_CODE = "P00000042"
    }
}
