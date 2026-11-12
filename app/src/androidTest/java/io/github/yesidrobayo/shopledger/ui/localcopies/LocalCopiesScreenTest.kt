package io.github.yesidrobayo.shopledger.ui.localcopies

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Copias locales` en el teléfono: el aviso de que sale en claro o cifrada, `Guardar en un
 * archivo` como acción principal, `Creando la copia` mientras trabaja, y cada resultado con su
 * arreglo. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class LocalCopiesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<LocalCopiesAction>()

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: LocalCopiesUiState) {
        compose.setContent { PreviewTheme { LocalCopiesScreen(state, { actions += it }) } }
    }

    private fun absent(value: String) = assertTrue(
        compose.onAllNodesWithText(value).fetchSemanticsNodes().isEmpty(),
    )

    @Test
    fun inClearItWarnsThatTheFileHoldsTheWholeBusiness() {
        show(LocalCopiesUiState(encrypted = false))

        compose.onNodeWithText(text(R.string.offsite_export_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.offsite_export_plain_warning)).assertIsDisplayed()
        absent(text(R.string.offsite_export_encrypted_info))
        compose.onNodeWithText(text(R.string.offsite_save_file_action)).performClick()

        assertEquals(listOf<LocalCopiesAction>(LocalCopiesAction.SaveToFile), actions)
    }

    @Test
    fun encryptedItSaysSo() {
        show(LocalCopiesUiState(encrypted = true))

        compose.onNodeWithText(text(R.string.offsite_export_encrypted_info)).assertIsDisplayed()
        absent(text(R.string.offsite_export_plain_warning))
    }

    @Test
    fun whileWorkingTheButtonSaysSoAndIgnoresTaps() {
        show(LocalCopiesUiState(encrypted = false, export = ExportUi.Working))

        compose.onNodeWithText(text(R.string.offsite_exporting)).performClick()

        assertEquals(emptyList<LocalCopiesAction>(), actions)
    }

    @Test
    fun aFailureOffersToTryAgain() {
        show(LocalCopiesUiState(encrypted = false, export = ExportUi.Failed(ExportFailure.OTHER)))

        compose.onNodeWithText(text(R.string.offsite_error_export)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertEquals(listOf<LocalCopiesAction>(LocalCopiesAction.SaveToFile), actions)
    }

    @Test
    fun noSpaceOffersAnotherPlaceAndDamagedOnlyCloses() {
        show(
            LocalCopiesUiState(encrypted = false, export = ExportUi.Failed(ExportFailure.NO_SPACE)),
        )
        compose.onNodeWithText(text(R.string.offsite_error_space)).assertIsDisplayed()
    }

    @Test
    fun aCopyThatCouldNotBeRestoredStaysInView() {
        show(LocalCopiesUiState(encrypted = false, export = ExportUi.SavedNotRestorable))

        val app = text(R.string.app_name)
        compose.onNodeWithText(resources.getString(R.string.offsite_saved_not_restorable, app))
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_close)).performClick()

        assertEquals(listOf<LocalCopiesAction>(LocalCopiesAction.DismissResult), actions)
    }

    @Test
    fun aDamagedDatabaseIsSaidAndOnlyCloses() {
        show(LocalCopiesUiState(encrypted = false, export = ExportUi.Failed(ExportFailure.DAMAGED)))

        compose.onNodeWithText(text(R.string.offsite_error_damaged)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_close)).performClick()

        assertEquals(listOf<LocalCopiesAction>(LocalCopiesAction.DismissResult), actions)
    }
}
