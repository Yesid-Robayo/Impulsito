package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: con los avisos de stock apagados, el inventario lo dice con un aviso discreto que
 * lleva a los ajustes, y la lista sigue igual. Con los avisos permitidos no hay aviso. Productos
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class InventoryNotificationsNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<InventoryAction>()

    private fun show(blocked: Boolean) {
        compose.setContent {
            PreviewTheme {
                InventoryScreen(
                    uiState = InventoryUiState(content = previewInventory()),
                    onAction = { actions += it },
                    notificationsBlocked = blocked,
                )
            }
        }
    }

    @Test
    fun withAlertsOffTheNoticeLeadsToTheSettings() {
        show(blocked = true)

        compose
            .onNodeWithText(resources.getString(R.string.permission_notifications_denied_inventory))
            .assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.action_open_settings)).performClick()

        assertEquals(listOf<InventoryAction>(InventoryAction.OpenNotificationSettings), actions)
        compose.onNodeWithText(previewInventoryItems().first().name).assertIsDisplayed()
    }

    @Test
    fun withAlertsAllowedThereIsNoNotice() {
        show(blocked = false)

        compose
            .onNodeWithText(resources.getString(R.string.permission_notifications_denied_inventory))
            .assertDoesNotExist()
    }
}
