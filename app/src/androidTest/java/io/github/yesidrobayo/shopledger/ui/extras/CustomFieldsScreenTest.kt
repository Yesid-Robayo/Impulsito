package io.github.yesidrobayo.shopledger.ui.extras

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La pantalla de campos propios y etiquetas en el teléfono: cada fila se lee entera con su tipo y
 * sus productos, el grupo de archivados va al final, cada segmento tiene su vacío, y la hoja de un
 * campo con valores dice por qué su tipo no cambia.
 *
 * Los textos se buscan por recurso, así la prueba vale en el idioma del emulador. Los datos son
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class CustomFieldsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    // Una fila se lee entera por TalkBack: su texto solo está en su descripción.
    private fun count(text: String) = compose
        .onAllNodes(
            hasText(text, substring = true) or hasContentDescription(text, substring = true),
        ).fetchSemanticsNodes()
        .size

    @Test
    fun aFieldRowReadsItsNameTypeAndProducts() {
        compose.setContent { PreviewTheme { CustomFieldsScreen(READY, onAction = {}) } }

        val products = resources.getQuantityString(R.plurals.field_product_count, 12, 12)
        val reading =
            listOf("Marca", resources.getString(R.string.field_type_text), products).joinToString()
        assertEquals(1, count(reading))
        assertEquals(1, count(resources.getString(R.string.field_archived_group)))
        assertEquals(1, count(resources.getString(R.string.field_new_action)))
    }

    @Test
    fun theNewFieldButtonAndTheTagsTabAreSentAsActions() {
        val actions = mutableListOf<CustomFieldsAction>()
        compose.setContent {
            PreviewTheme { CustomFieldsScreen(READY, onAction = { actions += it }) }
        }

        compose.onNodeWithText(resources.getString(R.string.field_new_action)).performClick()
        compose.onNodeWithText(resources.getString(R.string.tag_tab_tags)).performClick()

        assertEquals(
            listOf(
                CustomFieldsAction.NewField,
                CustomFieldsAction.SelectTab(CustomFieldsTab.TAGS),
            ),
            actions,
        )
    }

    @Test
    fun theFieldsTabHasItsOwnEmptyState() {
        compose.setContent {
            PreviewTheme {
                CustomFieldsScreen(CustomFieldsUiState(loading = false), onAction = {})
            }
        }
        assertEquals(1, count(resources.getString(R.string.field_empty_title)))
    }

    @Test
    fun theTagsTabHasItsOwnEmptyState() {
        compose.setContent {
            PreviewTheme {
                CustomFieldsScreen(
                    CustomFieldsUiState(loading = false, tab = CustomFieldsTab.TAGS),
                    onAction = {},
                )
            }
        }
        assertEquals(1, count(resources.getString(R.string.tag_empty_title)))
    }

    @Test
    fun theSheetOfAFieldWithValuesSaysWhyTheTypeIsLocked() {
        compose.setContent {
            PreviewTheme {
                CustomFieldsScreen(
                    READY,
                    onAction = {},
                    editor =
                        ExtraEditor(
                            kind = ExtraKind.FIELD,
                            editingId = 1L,
                            name = "Marca",
                            type = CustomFieldType.TEXT,
                            savedType = CustomFieldType.TEXT,
                            typeLocked = true,
                        ),
                )
            }
        }

        assertTrue(count(resources.getString(R.string.field_type_locked)) >= 1)
    }

    private companion object {
        val READY =
            CustomFieldsUiState(
                loading = false,
                fields =
                    listOf(
                        CustomField(1L, "Marca", CustomFieldType.TEXT, 0L, false, 12),
                        CustomField(2L, "Vence", CustomFieldType.DATE, 1L, false, 0),
                    ),
                archived = listOf(CustomField(3L, "Talla", CustomFieldType.TEXT, 2L, true, 4)),
                tags = listOf(ProductTag(1L, "Temporada", 3)),
            )
    }
}
