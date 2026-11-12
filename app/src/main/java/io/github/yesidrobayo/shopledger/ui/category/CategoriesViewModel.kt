package io.github.yesidrobayo.shopledger.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryFormIssue
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryInputError
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CategoriesOverview
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseCategoryLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveExpenseCategoryResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Las operaciones de la pantalla de categorías, cada una un caso de uso en su transacción.
 *
 * Las de producto se crean, se renombran y se eliminan aunque tengan productos, que quedan sin
 * categoría. Las de gasto se crean y se renombran; sin gastos se eliminan, con gastos se archivan,
 * y se reactivan. Renombrar una de producto conserva su margen objetivo.
 */
class CategoryOperations(
    val createProduct: suspend (String) -> SaveCategoryResult,
    val renameProduct: suspend (Long, String) -> SaveCategoryResult,
    val deleteProduct: suspend (Long) -> DeleteCategoryResult,
    val saveExpense: suspend (Long?, ExpenseCategoryDraft) -> SaveExpenseCategoryResult,
    val archiveExpense: suspend (Long) -> ExpenseCategoryLifecycleResult,
    val reactivateExpense: suspend (Long) -> ExpenseCategoryLifecycleResult,
    val deleteExpense: suspend (Long) -> ExpenseCategoryLifecycleResult,
)

/**
 * La pantalla de categorías de productos y de gastos: las dos listas con sus conteos, la hoja de crear
 * o renombrar y las confirmaciones.
 *
 * Un error al guardar se muestra en la hoja y nunca borra lo escrito. Lo que no se pudo hacer desde
 * la lista, como reactivar con el nombre tomado, se avisa una vez.
 *
 * @param overview las categorías con sus conteos; se pide de nuevo al reintentar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModel(
    private val overview: () -> Flow<CategoriesOverview>,
    private val operations: CategoryOperations,
    initialTab: CategoryTab = CategoryTab.PRODUCTS,
) : ViewModel() {
    private val state = MutableStateFlow(CategoriesUiState(tab = initialTab))
    val uiState: StateFlow<CategoriesUiState> = state.asStateFlow()
    private val attempts = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            attempts.flatMapLatest {
                overview()
                    .map<CategoriesOverview, CategoriesContent> { CategoriesContent.Ready(it) }
                    .catch { emit(CategoriesContent.ReadError) }
            }.collect { content -> state.update { it.copy(content = content) } }
        }
    }

    fun onAction(action: CategoriesAction) {
        when (action) {
            is CategoriesAction.TabChange -> state.update { it.copy(tab = action.tab) }

            CategoriesAction.Retry -> attempts.update { it + 1 }

            CategoriesAction.NewCategory -> state.update {
                it.copy(sheet = CategorySheet(tab = it.tab))
            }

            is CategoriesAction.Rename -> state.update {
                it.copy(
                    sheet = CategorySheet(
                        tab = action.tab,
                        editingId = action.id,
                        name = action.name,
                        isFixed = action.isFixed,
                        initial = action.initial,
                    ),
                )
            }

            is CategoriesAction.NameChange -> editSheet {
                it.copy(name = action.name, error = null, saveFailed = false)
            }

            is CategoriesAction.FixedChange -> editSheet {
                it.copy(isFixed = action.isFixed, saveFailed = false)
            }

            CategoriesAction.Save -> save()

            CategoriesAction.CloseSheet -> state.update {
                if (it.sheet?.saving == true) it else it.copy(sheet = null)
            }

            is CategoriesAction.Ask -> state.update { it.copy(confirm = action.confirm) }

            CategoriesAction.Confirm -> confirm()

            CategoriesAction.CloseConfirm -> state.update { it.copy(confirm = null) }

            is CategoriesAction.Reactivate -> lifecycle { operations.reactivateExpense(action.id) }

            CategoriesAction.FailureShown -> state.update { it.copy(failure = null) }

            CategoriesAction.MessageShown -> state.update { it.copy(message = null) }

            // Volver lo resuelve la navegación.
            CategoriesAction.Back -> Unit
        }
    }

    private fun editSheet(change: (CategorySheet) -> CategorySheet) = state.update { current ->
        val sheet = current.sheet ?: return@update current
        if (sheet.saving) current else current.copy(sheet = change(sheet))
    }

    private fun save() {
        val sheet = state.value.sheet ?: return
        if (sheet.saving) return
        state.update { it.copy(sheet = sheet.copy(saving = true, saveFailed = false)) }
        viewModelScope.launch {
            val outcome = when (sheet.tab) {
                CategoryTab.PRODUCTS -> productOutcome(
                    sheet.editingId?.let { operations.renameProduct(it, sheet.name) }
                        ?: operations.createProduct(sheet.name),
                )

                CategoryTab.EXPENSES -> expenseOutcome(
                    operations.saveExpense(
                        sheet.editingId,
                        ExpenseCategoryDraft(sheet.name, sheet.isFixed),
                    ),
                )
            }
            state.update { current ->
                when (outcome) {
                    SaveOutcome.Saved -> current.copy(sheet = null, message = CategoryMessage.SAVED)

                    is SaveOutcome.FieldError -> current.copy(
                        sheet = sheet.copy(error = outcome.error),
                    )

                    SaveOutcome.Failed -> current.copy(sheet = sheet.copy(saveFailed = true))
                }
            }
        }
    }

    private fun productOutcome(result: SaveCategoryResult): SaveOutcome = when (result) {
        is SaveCategoryResult.Saved -> SaveOutcome.Saved

        is SaveCategoryResult.Invalid -> SaveOutcome.FieldError(
            when {
                CategoryFormIssue.NAME_EMPTY in result.issues -> CategoryNameError.EMPTY
                else -> CategoryNameError.TOO_LONG
            },
        )

        is SaveCategoryResult.DuplicateName -> SaveOutcome.FieldError(CategoryNameError.DUPLICATE)

        SaveCategoryResult.NotFound, is SaveCategoryResult.StorageFailed -> SaveOutcome.Failed
    }

    private fun expenseOutcome(result: SaveExpenseCategoryResult): SaveOutcome = when (result) {
        is SaveExpenseCategoryResult.Saved -> SaveOutcome.Saved

        is SaveExpenseCategoryResult.Invalid -> SaveOutcome.FieldError(
            when {
                ExpenseCategoryInputError.NAME_EMPTY in result.errors -> CategoryNameError.EMPTY
                else -> CategoryNameError.TOO_LONG
            },
        )

        SaveExpenseCategoryResult.DuplicateName ->
            SaveOutcome.FieldError(CategoryNameError.DUPLICATE)

        SaveExpenseCategoryResult.NotFound, is SaveExpenseCategoryResult.StorageFailed ->
            SaveOutcome.Failed
    }

    private fun confirm() {
        val confirm = state.value.confirm ?: return
        state.update { it.copy(confirm = null) }
        when (confirm) {
            is CategoryConfirm.DeleteProduct -> viewModelScope.launch {
                when (operations.deleteProduct(confirm.id)) {
                    is DeleteCategoryResult.Deleted -> Unit

                    DeleteCategoryResult.NotFound, is DeleteCategoryResult.StorageFailed ->
                        state.update { it.copy(failure = CategoryFailure.NOT_SAVED) }
                }
            }

            is CategoryConfirm.DeleteExpense -> lifecycle { operations.deleteExpense(confirm.id) }

            is CategoryConfirm.ArchiveExpense -> lifecycle {
                operations.archiveExpense(confirm.id)
            }
        }
    }

    private fun lifecycle(run: suspend () -> ExpenseCategoryLifecycleResult) {
        viewModelScope.launch {
            val failure = when (run()) {
                // Ya estaba como se pedía: la lista lo muestra así, sin error.
                ExpenseCategoryLifecycleResult.Done,
                ExpenseCategoryLifecycleResult.Unchanged,
                -> null

                ExpenseCategoryLifecycleResult.NameTaken -> CategoryFailure.NAME_TAKEN

                ExpenseCategoryLifecycleResult.NotFound,
                ExpenseCategoryLifecycleResult.Protected,
                ExpenseCategoryLifecycleResult.HasExpenses,
                is ExpenseCategoryLifecycleResult.StorageFailed,
                -> CategoryFailure.NOT_SAVED
            }
            if (failure != null) state.update { it.copy(failure = failure) }
        }
    }

    private sealed interface SaveOutcome {
        data object Saved : SaveOutcome

        data class FieldError(val error: CategoryNameError) : SaveOutcome

        data object Failed : SaveOutcome
    }
}
