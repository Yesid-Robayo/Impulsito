package io.github.yesidrobayo.shopledger.lock

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialogContent
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: una acción sensible no se dispara con un toque que llega tapado por otra app, y
 * avisa; la de al lado sí responde, un toque limpio confirma, y TalkBack, que activa por
 * accesibilidad sin ningún toque, nunca pierde la acción. Los montos sensibles a la vista van
 * marcados como dato sensible.
 *
 * Los toques se arman con la marca de ventana tapada, como la pone Android cuando otra app dibuja
 * encima, y se entregan a la ventana de la actividad.
 */
@RunWith(AndroidJUnit4::class)
class ObscuredTouchTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = compose.activity.resources.getString(id)

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

    @Test
    fun aCoveredTapOnASensitiveActionIsRejectedAndWarnsWhileTheOtherActionWorks() {
        var sensitive = 0
        var normal = 0
        var warnings = 0
        compose.setContent {
            PreviewTheme {
                Column {
                    PrimaryActionButton(
                        SENSITIVE,
                        { sensitive += 1 },
                        ActionVariant.PRIMARY,
                        Modifier.rejectObscuredTouches { warnings += 1 },
                    )
                    PrimaryActionButton(NORMAL, { normal += 1 }, ActionVariant.TONAL)
                }
            }
        }

        tap(SENSITIVE, obscured = true)
        tap(NORMAL, obscured = true)
        assertEquals(0, sensitive)
        assertEquals(1, warnings)
        assertEquals(1, normal)

        tap(SENSITIVE, obscured = false)
        assertEquals(1, sensitive)

        // TalkBack activa con doble toque por accesibilidad, sin ningún toque en la app.
        compose.onNodeWithText(SENSITIVE).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(2, sensitive)
        assertEquals(1, warnings)
    }

    @Test
    fun aSensitiveConfirmationIgnoresACoveredTapAndSaysWhy() {
        var confirmed = 0
        compose.setContent {
            PreviewTheme {
                ConfirmDialogContent(
                    title = TITLE,
                    body = BODY,
                    confirmLabel = CONFIRM,
                    onConfirm = { confirmed += 1 },
                    onDismiss = {},
                    variant = ConfirmVariant.DESTRUCTIVE,
                    sensitive = true,
                )
            }
        }

        tap(CONFIRM, obscured = true)
        assertEquals(0, confirmed)
        compose.onNodeWithText(text(R.string.security_obscured_touch)).assertIsDisplayed()

        tap(CONFIRM, obscured = false)
        assertEquals(1, confirmed)
    }

    @Test
    fun aSensitiveAmountInSightIsMarkedAsSensitiveData() {
        compose.setContent {
            PreviewTheme {
                Column {
                    MoneyText(Money(12_345L), MoneySize.ROW, sensitive = true)
                }
            }
        }

        compose
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.IsSensitiveData, true))
            .assertIsDisplayed()
    }

    private companion object {
        const val SENSITIVE = "Restaurar de prueba"
        const val NORMAL = "Otra acción"
        const val TITLE = "Título de prueba"
        const val BODY = "Cuerpo de prueba"
        const val CONFIRM = "Confirmar de prueba"
    }
}
