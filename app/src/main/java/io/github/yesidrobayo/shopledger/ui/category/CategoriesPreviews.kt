package io.github.yesidrobayo.shopledger.ui.category

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CategoriesOverview
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ExpenseCategoryRow
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ProductCategoryRow
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.time.Instant

// Vistas previas de las categorías, con datos inventados: un nombre de 40 caracteres, una categoría
// fija, una archivada y la variante vacía.

private val AT = Instant.fromEpochMilliseconds(0)

private fun product(id: Long, name: String) =
    Category(id, CategoryDetails(name, name.lowercase(), null), AT, AT)

private fun expense(
    id: Long,
    key: String?,
    name: String? = null,
    fixed: Boolean = false,
    archived: Boolean = false,
) = ExpenseCategory(
    id = id,
    systemKey = key,
    details = ExpenseCategoryDetails(name, name?.lowercase(), fixed),
    isSystem = key == "OTHER",
    archivedAt = if (archived) AT else null,
    createdAt = AT,
    updatedAt = AT,
)

/** Unas categorías de los dos tipos, con conteos y una archivada. */
internal fun previewCategories(): CategoriesOverview = CategoriesOverview(
    products = listOf(
        ProductCategoryRow(product(1, "Limpieza"), 12),
        ProductCategoryRow(product(2, "c".repeat(40)), 1),
        ProductCategoryRow(product(3, "Bebidas"), 0),
    ),
    expenses = listOf(
        ExpenseCategoryRow(expense(11, "RENT", fixed = true), 8),
        ExpenseCategoryRow(expense(12, "TRANSPORT"), 0),
        ExpenseCategoryRow(expense(19, "OTHER"), 3),
    ),
    archivedExpenses = listOf(
        ExpenseCategoryRow(expense(20, null, "Fumigación", archived = true), 2),
    ),
)

@ThemePreviews
@Composable
private fun CategoriesProductsPreview() {
    PreviewTheme {
        CategoriesScreen(
            CategoriesUiState(
                content = CategoriesContent.Ready(previewCategories()),
            ),
            {
            },
        )
    }
}

@ThemePreviews
@Composable
private fun CategoriesExpensesPreview() {
    PreviewTheme {
        CategoriesScreen(
            CategoriesUiState(
                tab = CategoryTab.EXPENSES,
                content = CategoriesContent.Ready(previewCategories()),
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun CategoriesEmptyPreview() {
    PreviewTheme {
        CategoriesScreen(
            CategoriesUiState(
                content = CategoriesContent.Ready(
                    previewCategories().copy(products = emptyList()),
                ),
            ),
            {},
        )
    }
}
