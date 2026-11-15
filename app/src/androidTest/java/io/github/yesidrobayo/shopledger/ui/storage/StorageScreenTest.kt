package io.github.yesidrobayo.shopledger.ui.storage

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseStop
import io.github.yesidrobayo.shopledger.domain.model.erase.StorageUsage
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Almacenamiento y borrado` en el teléfono: el aviso desde Android, lo que no se midió, el
 * borrado que nunca empieza solo, y lo que dice cuando se detiene. Valores inventados.
 */
@RunWith(AndroidJUnit4::class)
class StorageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val app = resources.getString(R.string.app_name)
    private val actions = mutableListOf<StorageAction>()

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private fun show(state: StorageUiState) {
        compose.setContent { PreviewTheme { StorageScreen(state) { actions += it } } }
    }

    @Test
    fun fromAndroidItSaysToLookAtTheBackupsAndAnUnmeasuredRowIsUnavailable() {
        show(
            StorageUiState(
                usage = StorageUsage(2_048, null, 0, 0, 1_024),
                fromAndroid = true,
            ),
        )

        compose.onNodeWithText(text(R.string.storage_opened_from_android)).assertExists()
        compose.onNodeWithText(text(R.string.storage_not_available)).assertExists()
        // El título de la sección dice lo mismo que el botón: se toca el que tiene acción.
        compose.onNode(hasText(text(R.string.erase_action)) and hasClickAction())
            .performScrollTo()
            .performClick()

        assertTrue(StorageAction.OpenErase in actions)
    }

    @Test
    fun theFirstStepHasNothingChosenAndCannotContinue() {
        show(StorageUiState(usage = null, flow = EraseFlow.Scope(null)))

        compose.onNodeWithText(text(R.string.erase_option_device)).assertExists()
        compose.onNodeWithText(text(R.string.action_continue)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.erase_option_all)).performClick()

        assertTrue(StorageAction.ChooseScope(EraseScope.DEVICE_AND_COPIES) in actions)
    }

    @Test
    fun offlineItTouchesNothingAndOffersThisPhoneOnly() {
        show(StorageUiState(flow = EraseFlow.Stopped(EraseStop.OFFLINE)))

        compose.onNodeWithText(text(R.string.erase_offline)).assertExists()
        compose.onNodeWithText(text(R.string.erase_device_only_action)).performClick()

        assertTrue(StorageAction.DeviceOnly in actions)
    }

    @Test
    fun aRefusingCloudSaysThisPhoneWasNotTouchedAndOffersRetry() {
        show(StorageUiState(flow = EraseFlow.Stopped(EraseStop.REFUSED)))

        compose.onNodeWithText(text(R.string.erase_error_refused, app)).assertExists()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertTrue(StorageAction.Retry in actions)
    }
}
