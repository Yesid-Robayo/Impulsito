package io.github.yesidrobayo.shopledger.ui.backup

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeRequest
import io.github.yesidrobayo.shopledger.lock.LocalWindowProtection
import io.github.yesidrobayo.shopledger.lock.WindowProtection
import io.github.yesidrobayo.shopledger.ui.rescue.RescueAction
import io.github.yesidrobayo.shopledger.ui.rescue.RescueReason
import io.github.yesidrobayo.shopledger.ui.rescue.RescueScreen
import io.github.yesidrobayo.shopledger.ui.rescue.RescueUiState
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La hoja `Copia cifrada`, aquí desde el rescate, que funciona sin la base: pide el código antes
 * de reemplazar nada, protege la ventana mientras se escribe, dice el código de otras copias sin
 * borrar lo escrito, explica qué hacer sin el código y solo abre la copia con el código completo.
 * Códigos inventados.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedRestoreScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<RescueAction>()

    private fun text(id: Int) = compose.activity.getString(id)

    private fun asking(
        typed: List<String> = RestoreUi.AskCode.EMPTY,
        request: RecoveryCodeRequest = RecoveryCodeRequest.NEEDED,
    ) = RestoreUi.AskCode(RestoreOrigin.LIST, CodeRetry.Copy("copia-1"), request, typed)

    private fun show(restore: RestoreUi, protection: WindowProtection = WindowProtection({})) {
        val state =
            RescueUiState(RescueReason.OpenFailed, BackupDestinationKind.WEBDAV, restore = restore)
        compose.setContent {
            CompositionLocalProvider(LocalWindowProtection provides protection) {
                PreviewTheme { RescueScreen(state, emptyList(), onAction = { actions += it }) }
            }
        }
    }

    @Test
    fun itAsksForTheCodeAndOnlyOpensTheCopyWithAllOfIt() {
        show(asking(typed = listOf("0123", "4567", "89AB", "CDEF", "GHJK", "MNPQ", "")))

        compose.onNodeWithText(text(R.string.recovery_restore_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recovery_restore_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recovery_restore_open_action)).assertIsNotEnabled()
    }

    @Test
    fun withTheWholeCodeOpenStartsAgainWithIt() {
        show(asking(typed = GROUPS))

        compose.onNodeWithText(text(R.string.recovery_restore_open_action))
            .assertIsEnabled()
            .performClick()

        assertEquals(
            listOf<RescueAction>(
                RescueAction.Restore(BackupAction.RestoreCode(RestoreCodeEvent.Open)),
            ),
            actions,
        )
    }

    @Test
    fun anotherCodeIsSaidWithoutErasingWhatWasTyped() {
        show(asking(typed = GROUPS, request = RecoveryCodeRequest.OTHER_CODE))

        compose.onNodeWithText(text(R.string.recovery_restore_other_key)).assertIsDisplayed()
        compose.onNodeWithText("CDEF").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recovery_restore_open_action)).assertIsEnabled()
    }

    @Test
    fun replacingTheCodeOfThisPhoneIsSaidBeforeGoingOn() {
        show(
            RestoreUi.AskCode(
                RestoreOrigin.FILE,
                CodeRetry.File("content://example/copia"),
                RecoveryCodeRequest.NEEDED,
                replacesCurrent = true,
            ),
        )

        compose.onNodeWithText(text(R.string.dialog_restore_other_code_notice)).assertIsDisplayed()
    }

    @Test
    fun aTypoIsSaidAndNothingIsBlocked() {
        show(asking(typed = GROUPS, request = RecoveryCodeRequest.TYPO))

        compose.onNodeWithText(text(R.string.recovery_check_mismatch)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.recovery_restore_open_action)).assertIsEnabled()
    }

    @Test
    fun withoutTheCodeOpensItsHelp() {
        show(asking())
        compose.onNodeWithText(
            text(R.string.recovery_no_code_action),
        ).performScrollTo().performClick()
        assertEquals(
            listOf<RescueAction>(
                RescueAction.Restore(BackupAction.RestoreCode(RestoreCodeEvent.WithoutCode)),
            ),
            actions,
        )
    }

    @Test
    fun theHelpWithoutTheCodeSaysWhereToLook() {
        show(asking().copy(noCodeHelp = true))
        compose.onNodeWithText(
            text(R.string.recovery_no_code_body),
        ).performScrollTo().assertIsDisplayed()
        assertEquals(
            0,
            compose
                .onAllNodesWithText(text(R.string.recovery_no_code_action))
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun theWindowIsSecureOnlyWhileTheCodeIsAsked() {
        var restore: RestoreUi by mutableStateOf(asking())
        val protection = WindowProtection.of(compose.activity.window)
        compose.setContent {
            CompositionLocalProvider(LocalWindowProtection provides protection) {
                PreviewTheme {
                    RescueScreen(
                        RescueUiState(RescueReason.OpenFailed, restore = restore),
                        emptyList(),
                        onAction = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val flags = { compose.activity.window.attributes.flags }

        assertTrue(flags() and WindowManager.LayoutParams.FLAG_SECURE != 0)

        restore = RestoreUi.Idle
        compose.waitForIdle()

        assertFalse(flags() and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    private companion object {
        val GROUPS = listOf("0123", "4567", "89AB", "CDEF", "GHJK", "MNPQ", "RS00")
    }
}
