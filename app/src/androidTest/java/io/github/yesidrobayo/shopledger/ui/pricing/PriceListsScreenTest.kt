package io.github.yesidrobayo.shopledger.ui.pricing

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
 * La pantalla de listas de precios en el teléfono: las listas con su tipo, el vacío, el detalle con
 * de dónde sale cada precio, y el aviso de archivar. Los textos se buscan por recurso, así la prueba
 * vale en el idioma del emulador. Las capturas están en `PriceListsGalleryTest`. Los datos son
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class PriceListsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources

    // Una fila se lee entera por TalkBack: su texto puede estar solo en su descripción.
    private fun count(text: String) = compose
        .onAllNodes(
            hasText(text, substring = true) or hasContentDescription(text, substring = true),
        )
        .fetchSemanticsNodes()
        .size

    @Test
    fun listsSayTheirKindAndTheArchivedOnesCanBeReactivated() {
        val actions = mutableListOf<PriceListsAction>()
        compose.setContent {
            PreviewTheme {
                PriceListsScreen(PriceListSamples.lists, onAction = {
                    actions +=
                        it
                })
            }
        }

        // La inicial, la de mayoristas y la archivada son fijas.
        assertEquals(3, count(resources.getString(R.string.price_list_fixed_desc)))
        assertEquals(1, count(resources.getString(R.string.price_list_archived_group)))
        assertEquals(0, count(resources.getString(R.string.price_list_empty_title)))
        compose.onNodeWithText(resources.getString(R.string.action_reactivate)).performClick()
        compose.onNodeWithText(resources.getString(R.string.price_list_new_action)).performClick()
        assertEquals(
            listOf(PriceListsAction.Reactivate(PriceListSamples.OLD_ID), PriceListsAction.NewList),
            actions,
        )
    }

    @Test
    fun withOnlyTheInitialListItInvitesToCreateOne() {
        compose.setContent {
            PreviewTheme { PriceListsScreen(PriceListSamples.empty, onAction = {}) }
        }

        assertEquals(1, count(resources.getString(R.string.price_list_empty_title)))
    }

    @Test
    fun theDetailTagsWhereEachPriceComesFrom() {
        compose.setContent {
            PreviewTheme { PriceListsScreen(PriceListSamples.detail, onAction = {}) }
        }

        assertEquals(1, count(resources.getString(R.string.price_list_tag_calculated)))
        assertEquals(1, count(resources.getString(R.string.price_list_tag_own)))
        assertEquals(1, count(resources.getString(R.string.price_list_tag_general)))
    }

    @Test
    fun aRelativeListDoesNotOpenThePriceSheet() {
        val actions = mutableListOf<PriceListsAction>()
        compose.setContent {
            PreviewTheme {
                PriceListsScreen(PriceListSamples.detail, onAction = {
                    actions +=
                        it
                })
            }
        }

        compose.onNodeWithText(PriceListSamples.rows.first().name).performClick()

        assertEquals(
            emptyList<PriceListsAction>(),
            actions.filterIsInstance<PriceListsAction.EditPrice>(),
        )
    }

    @Test
    fun archivingWithCustomersAsksFirst() {
        val state = PriceListSamples.lists.copy(
            dialog = PriceListsDialog.Archive(PriceListSamples.WHOLESALE_ID, "Mayoristas", 3),
        )
        compose.setContent { PreviewTheme { PriceListsScreen(state, onAction = {}) } }

        val body = resources.getQuantityString(R.plurals.price_list_archive_confirm_body, 3, 3)
        assertEquals(1, count(body))
    }
}
