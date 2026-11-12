package io.github.yesidrobayo.shopledger.ui.expense

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ObserveExpenseMonthUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats

/**
 * Los gastos en la navegación: arma su ViewModel con los casos de uso y resuelve adónde se va.
 *
 * `Ventas` vuelve a la raíz de Registros y `Compras` abre las compras sobre ella: los segmentos no se
 * apilan. Confirmar la moneda abre la región y, al volver, la hoja sigue con lo escrito. Guardar o
 * anular deja su aviso en la navegación. Si la base va al rescate, la pantalla no lee nada.
 */
@Composable
fun ExpensesRoute(key: AppNavKey.Expenses, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val numbers = ExpenseNumberFormat(formats.money.inputSymbols, formats.money.currencyDecimals)
    val model =
        rememberAppViewModel(key = "expenses") { dependencies ->
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val observe = ObserveExpenseMonthUseCase(ports.expenses, ports.expenseCategories)
            val register = RegisterExpenseUseCase(
                transactions = ports.transactions,
                settings = ports.settings,
                expenses = ports.expenses,
                categories = ports.expenseCategories,
                clock = clock,
            )
            val void = VoidExpenseUseCase(ports.transactions, ports.expenses, clock)
            val saveCategory =
                SaveExpenseCategoryUseCase(ports.transactions, ports.expenseCategories, clock)
            ExpensesViewModel(
                observeMonth = { month, categoryId -> observe(month, categoryId) },
                calendar = BusinessCalendarSource(ports.settings).observe(),
                now = clock::now,
                register = { register(it) },
                void = { id, reason, note -> void(id, reason, note) },
                numbers = numbers,
                createCategory = { draft -> saveCategory(null, draft) },
            )
        }
    when (model) {
        ScreenModel.Opening -> ExpensesScreen(ExpensesUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val message = uiState.message
            val text = when (message) {
                is ExpenseMessage.Saved -> stringResource(
                    R.string.confirm_expense_saved,
                    rememberMoneyDisplay(message.amount, formats.money).text.text,
                )

                ExpenseMessage.Voided -> stringResource(R.string.confirm_expense_voided)

                null -> null
            }
            LaunchedEffect(message) {
                if (message == null || text == null) return@LaunchedEffect
                viewModel.onAction(ExpensesAction.MessageShown)
                messages.show(AppSnackbarVisuals(text, SnackbarVariant.SUCCESS))
            }
            ExpensesScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    when (action) {
                        ExpensesAction.ShowSales -> navigator.open(AppNavKey.SaleHistory)

                        ExpensesAction.ShowPurchases -> {
                            navigator.open(AppNavKey.SaleHistory)
                            navigator.open(AppNavKey.PurchaseHistory())
                        }

                        ExpensesAction.OpenRecurring -> navigator.open(AppNavKey.RecurringExpenses)

                        ExpensesAction.ConfirmCurrency ->
                            navigator.open(AppNavKey.RegionalSettings())

                        ExpensesAction.PreviousMonth,
                        ExpensesAction.NextMonth,
                        ExpensesAction.OpenCategories,
                        is ExpensesAction.CategoryFilterChange,
                        ExpensesAction.CloseCategories,
                        ExpensesAction.Retry,
                        is ExpensesAction.OpenExpense,
                        ExpensesAction.StartVoid,
                        is ExpensesAction.VoidReasonChange,
                        is ExpensesAction.VoidNoteChange,
                        ExpensesAction.ConfirmVoid,
                        ExpensesAction.CloseDetail,
                        ExpensesAction.NewExpense,
                        is ExpensesAction.AmountChange,
                        is ExpensesAction.FormCategoryChange,
                        is ExpensesAction.DescriptionChange,
                        is ExpensesAction.DateChange,
                        ExpensesAction.Save,
                        ExpensesAction.CloseForm,
                        ExpensesAction.MessageShown,
                        ExpensesAction.NewCategory,
                        is ExpensesAction.NewCategoryNameChange,
                        ExpensesAction.SaveCategory,
                        ExpensesAction.CloseCategory,
                        -> Unit
                    }
                },
            )
        }
    }
}
