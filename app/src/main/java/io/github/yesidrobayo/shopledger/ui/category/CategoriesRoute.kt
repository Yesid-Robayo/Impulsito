package io.github.yesidrobayo.shopledger.ui.category

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveCategoriesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ArchiveExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.DeleteExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ReactivateExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant

/**
 * Las categorías en la navegación: arma su ViewModel con los casos de uso. Renombrar una de producto
 * conserva su margen objetivo, que se edita en otro lugar. Guardar deja su aviso en la navegación.
 * Si la base va al rescate, la pantalla no lee nada.
 */
@Composable
fun CategoriesRoute(key: AppNavKey, navigator: AppNavigator) {
    val model =
        rememberAppViewModel(key = "categories") { dependencies ->
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val tx = ports.transactions
            val observe = ObserveCategoriesUseCase(ports.categories, ports.expenseCategories)
            val create = CreateCategoryUseCase(tx, ports.categories, clock)
            val edit = EditCategoryUseCase(tx, ports.categories, clock)
            val delete = DeleteCategoryUseCase(tx, ports.categories)
            val saveExpense = SaveExpenseCategoryUseCase(tx, ports.expenseCategories, clock)
            val archive = ArchiveExpenseCategoryUseCase(tx, ports.expenseCategories, clock)
            val reactivate = ReactivateExpenseCategoryUseCase(tx, ports.expenseCategories, clock)
            val deleteExpense = DeleteExpenseCategoryUseCase(tx, ports.expenseCategories)
            CategoriesViewModel(
                overview = { observe() },
                operations = CategoryOperations(
                    createProduct = { name -> create(name) },
                    renameProduct = { id, name ->
                        // Sin la categoría, la edición misma dice que ya no existe.
                        edit(id, name, ports.categories.findById(id)?.details?.targetMargin)
                    },
                    deleteProduct = { id -> delete(id) },
                    saveExpense = { id, draft -> saveExpense(id, draft) },
                    archiveExpense = { id -> archive(id) },
                    reactivateExpense = { id -> reactivate(id) },
                    deleteExpense = { id -> deleteExpense(id) },
                ),
            )
        }
    when (model) {
        ScreenModel.Opening -> CategoriesScreen(CategoriesUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val saved = stringResource(R.string.confirm_category_saved)
            val message = uiState.message
            LaunchedEffect(message) {
                if (message == null) return@LaunchedEffect
                viewModel.onAction(CategoriesAction.MessageShown)
                when (message) {
                    CategoryMessage.SAVED ->
                        messages.show(AppSnackbarVisuals(saved, SnackbarVariant.SUCCESS))
                }
            }
            CategoriesScreen(
                uiState = uiState,
                onAction = { action ->
                    if (action == CategoriesAction.Back) {
                        navigator.back()
                    } else {
                        viewModel.onAction(action)
                    }
                },
            )
        }
    }
}
