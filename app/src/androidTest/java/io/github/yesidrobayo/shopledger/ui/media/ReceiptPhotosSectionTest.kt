package io.github.yesidrobayo.shopledger.ui.media

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptPhoto
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las fotos del comprobante en el dispositivo: agregar solo en un documento activo, abrir una para
 * leerla, quitarla solo tras confirmar, y la foto que no está dice `Foto no disponible`. Sin
 * caché, como fuera de la app, ninguna foto se lee del disco. Imágenes inventadas.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptPhotosSectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<ReceiptPhotosAction>()
    private val photo =
        ReceiptPhoto(1L, StoredImage("e".repeat(64), "image/webp", 1_600, 1_200, 9))

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun anActiveDocumentOffersToAddAndAPhotoOpens() {
        showSection(ReceiptPhotosUiState(photos = listOf(photo)))

        compose.onNodeWithText(text(R.string.attachment_add_action)).performClick()
        compose.onAllNodesWithContentDescription(text(R.string.media_unavailable)).onFirst()
            .performClick()
        assertTrue(ReceiptPhotosAction.Choose in actions)
        assertTrue(ReceiptPhotosAction.Open(photo) in actions)
    }

    @Test
    fun aVoidedDocumentShowsItsPhotosWithoutAdding() {
        showSection(ReceiptPhotosUiState(photos = listOf(photo)), canAdd = false)

        compose.onAllNodesWithText(text(R.string.attachment_add_action)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(text(R.string.media_unavailable))
            .assertCountEquals(1)
    }

    @Test
    fun removingAsksFirst() {
        showSection(ReceiptPhotosUiState(listOf(photo), viewing = photo, confirmRemove = true))

        compose.onNodeWithText(text(R.string.attachment_remove_title)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.product_photo_remove_action)).onFirst()
            .performClick()
        val asked = ReceiptPhotosAction.AskRemove in actions
        assertTrue(asked || ReceiptPhotosAction.ConfirmRemove in actions)
    }

    @Test
    fun anUnreadablePhotoSaysSo() {
        showSection(ReceiptPhotosUiState(emptyList(), error = ReceiptPhotoError.UNREADABLE))

        compose.onNodeWithText(text(R.string.product_photo_error)).assertIsDisplayed()
    }

    private fun showSection(state: ReceiptPhotosUiState, canAdd: Boolean = true) {
        show { ReceiptPhotosSection(state, canAdd, { actions += it }) }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = false) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }
}
