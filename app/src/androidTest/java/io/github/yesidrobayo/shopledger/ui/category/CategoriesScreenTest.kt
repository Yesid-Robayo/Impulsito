package io.github.yesidrobayo.shopledger.ui.category

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
 * Las categorías en el teléfono: los conteos, el menú de cada fila según sus reglas, las archivadas
 * con `Reactivar`, y el estado vacío de productos. Nombres inventados.
 */
@RunWith(AndroidJUnit4::class)
class CategoriesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<CategoriesAction>()

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: CategoriesUiState) {
        compose.setContent { PreviewTheme { CategoriesScreen(state, { actions += it }) } }
    }

    private fun openMenu(index: Int) {
        compose.onAllNodesWithContentDescription(text(R.string.a11y_more_options))[index]
            .performClick()
    }

    @Test
    fun productCategoriesShowTheirCountAndAskBeforeDeleting() {
        show(CategoriesUiState(content = CategoriesContent.Ready(previewCategories())))

        compose.onNodeWithText(
            resources.getQuantityString(R.plurals.category_products_count, 12, 12),
        )
            .assertIsDisplayed()
        // Por nombre: Bebidas, ccc…, Limpieza. Limpieza es la tercera.
        openMenu(2)
        compose.onNodeWithText(text(R.string.action_delete)).performClick()

        assertEquals(
            listOf<CategoriesAction>(
                CategoriesAction.Ask(CategoryConfirm.DeleteProduct(1, "Limpieza", 12)),
            ),
            actions,
        )
    }

    @Test
    fun anExpenseCategoryWithExpensesIsOnlyArchived() {
        show(
            CategoriesUiState(
                tab = CategoryTab.EXPENSES,
                content = CategoriesContent.Ready(
                    previewCategories().copy(
                        expenses = previewCategories().expenses.take(1),
                        archivedExpenses = emptyList(),
                    ),
                ),
            ),
        )

        openMenu(0)
        compose.onNodeWithText(text(R.string.action_archive)).assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText(text(R.string.action_delete)).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun archivedExpenseCategoriesCanBeReactivated() {
        show(
            CategoriesUiState(
                tab = CategoryTab.EXPENSES,
                content = CategoriesContent.Ready(
                    previewCategories().copy(expenses = emptyList()),
                ),
            ),
        )

        compose.onNodeWithText(text(R.string.category_archived)).assertIsDisplayed()
        openMenu(0)
        compose.onNodeWithText(text(R.string.action_reactivate)).performClick()

        assertEquals(listOf<CategoriesAction>(CategoriesAction.Reactivate(20)), actions)
    }

    @Test
    fun noProductCategoriesInvitesTheFirst() {
        show(
            CategoriesUiState(
                content = CategoriesContent.Ready(previewCategories().copy(products = emptyList())),
            ),
        )

        compose.onNodeWithText(text(R.string.empty_categories_title)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.empty_categories_action))[0].performClick()

        assertEquals(listOf<CategoriesAction>(CategoriesAction.NewCategory), actions)
    }
}
