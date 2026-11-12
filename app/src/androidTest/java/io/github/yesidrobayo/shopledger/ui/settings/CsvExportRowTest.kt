package io.github.yesidrobayo.shopledger.ui.settings

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: `Exportar a CSV` en Ajustes. Un toque limpio pide exportar; uno que llega tapado
 * por otra app se rechaza y avisa; TalkBack nunca pierde la acción; y mientras exporta, la fila dice
 * `Exportando`, muestra su avance y no atiende toques.
 */
@RunWith(AndroidJUnit4::class)
class CsvExportRowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = compose.activity.resources.getString(id)

    @Test
    fun aCleanTapExportsACoveredOneIsRejectedAndTalkBackAlwaysWorks() {
        val actions = mutableListOf<CsvExportAction>()
        compose.setContent {
            PreviewTheme { CsvExportRow(CsvExportUiState(), onAction = { actions += it }) }
        }
        val title = text(R.string.settings_export)
        compose.onNodeWithText(text(R.string.settings_export_summary)).fetchSemanticsNode()

        tap(title, obscured = true)
        assertEquals(listOf<CsvExportAction>(CsvExportAction.ObscuredTouch), actions)

        tap(title, obscured = false)
        assertEquals(CsvExportAction.Export, actions.last())

        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(3, actions.size)
        assertEquals(CsvExportAction.Export, actions.last())
    }

    @Test
    fun whileExportingTheRowShowsItsProgressAndIgnoresTaps() {
        val actions = mutableListOf<CsvExportAction>()
        var state by mutableStateOf(CsvExportUiState(progress = CsvExportProgress(3, 9)))
        compose.setContent { PreviewTheme { CsvExportRow(state, onAction = { actions += it }) } }

        compose.onNodeWithText(text(R.string.settings_export_working)).fetchSemanticsNode()
        compose.onNodeWithText(text(R.string.settings_export), useUnmergedTree = true)
            .fetchSemanticsNode()
        compose
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(3f / 9f, 0f..1f),
                ),
            ).fetchSemanticsNode()
        compose.onNodeWithText(text(R.string.settings_export_working)).assertHasNoClickAction()

        tap(text(R.string.settings_export_working), obscured = false)
        assertEquals(emptyList<CsvExportAction>(), actions)

        state = CsvExportUiState()
        compose.onNodeWithText(text(R.string.settings_export_summary)).fetchSemanticsNode()
    }

    /** Un toque en el centro del texto [label], con la marca de ventana tapada si [obscured]. */
    private fun tap(label: String, obscured: Boolean) {
        val center = compose.onNodeWithText(label).fetchSemanticsNode().boundsInWindow.center
        val flags = if (obscured) MotionEvent.FLAG_WINDOW_IS_OBSCURED else 0
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event =
                MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    1,
                    arrayOf(
                        MotionEvent.PointerProperties().apply {
                            id = 0
                            toolType = MotionEvent.TOOL_TYPE_FINGER
                        },
                    ),
                    arrayOf(
                        MotionEvent.PointerCoords().apply {
                            x = center.x
                            y = center.y
                            pressure = 1f
                            size = 1f
                        },
                    ),
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    flags,
                )
            compose.runOnUiThread { compose.activity.window.decorView.dispatchTouchEvent(event) }
            event.recycle()
        }
        compose.waitForIdle()
    }
}
