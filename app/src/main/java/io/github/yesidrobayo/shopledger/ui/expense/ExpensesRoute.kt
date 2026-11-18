package io.github.yesidrobayo.shopledger.ui.expense

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.document.AttachmentTarget
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseCorrections
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ObserveExpenseMonthUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ObserveRecurringExpensesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.PrepareRecurringRegistrationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveExpenseCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObservePayingAccountsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.DocumentUndoTexts
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.navigation.rememberExpenseUndo
import io.github.yesidrobayo.shopledger.navigation.showUndoable
import io.github.yesidrobayo.shopledger.ui.backup.RootBackupBanner
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.media.ReceiptPhotosRoute
import io.github.yesidrobayo.shopledger.ui.sale.RecordsTab
import kotlinx.coroutines.flow.map

/**
 * Los gastos en la navegación: arma su ViewModel con los casos de uso y resuelve adónde se va.
 *
 * Como segmento de Registros, con [onSegment], `Ventas` y `Compras` cambian el contenido ahí
 * mismo, sin abrir otra pantalla. Como destino propio, como desde el atajo `Gasto` de Inicio, va
 * sin segmentos y atrás vuelve; desde ese atajo abre con la hoja del gasto nuevo, y guardar
 * vuelve a Inicio. Desde `Registrar` de un gasto recurrente abre con sus datos puestos, y guardar
 * vuelve a los gastos recurrentes. Confirmar la moneda abre la región y, al volver, la hoja sigue
 * con lo escrito. Guardar o anular deja su aviso en la navegación. Si la base va al rescate, la
 * pantalla no lee nada.
 *
 * Como raíz de la pestaña `Gastos`, con [ownTab], va sin segmentos ni atrás, con los ajustes al
 * borde, como las otras raíces.
 *
 * @param onSegment elige otro segmento de Registros, o `null` si los gastos van como destino
 *   propio o como su pestaña.
 * @param ownTab si es la raíz de la pestaña `Gastos`.
 */
@Composable
fun ExpensesRoute(
    key: AppNavKey.Expenses,
    navigator: AppNavigator,
    onSegment: ((RecordsTab) -> Unit)? = null,
    ownTab: Boolean = false,
) {
    val asTab = onSegment != null
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
                money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
                recurring = ports.recurringExpenses,
            )
            val void = VoidExpenseUseCase(
                transactions = ports.transactions,
                expenses = ports.expenses,
                clock = clock,
                settings = ports.settings,
                money = DocumentMoney(ports.moneyAccounts, ports.moneyLedger),
            )
            val payingAccounts =
                ObservePayingAccountsUseCase(ports.moduleUsage, ports.settings, ports.moneyAccounts)
            val saveCategory =
                SaveExpenseCategoryUseCase(ports.transactions, ports.expenseCategories, clock)
            val recurring = ObserveRecurringExpensesUseCase(
                ports.recurringExpenses,
                BusinessCalendarSource(ports.settings),
                clock,
            )
            val prepareRecurring = PrepareRecurringRegistrationUseCase(
                ports.recurringExpenses,
                ports.expenseCategories,
            )
            ExpensesViewModel(
                observeMonth = { month, categoryId -> observe(month, categoryId) },
                calendar = BusinessCalendarSource(ports.settings).observe(),
                now = clock::now,
                register = { register(it) },
                void = { id, reason, note -> void(id, reason, note) },
                numbers = numbers,
                createCategory = { draft -> saveCategory(null, draft) },
                loadCorrection = { expenseId ->
                    ports.expenses.findById(expenseId)?.let { expense ->
                        val categoryId = expense.content.expenseCategoryId
                        ExpenseCorrections.of(
                            expense.content,
                            ports.expenseCategories.findById(categoryId),
                        )
                    }
                },
                payingAccounts = payingAccounts(),
                loadRecurring = { prepareRecurring(it) },
                dueRecurring = recurring().map { it.due.size },
                // La misma suma por categoría que `Gastos por categoría` de las estadísticas.
                categoryTotals = { month ->
                    ports.periodBreakdowns.observeExpenseCategories(ReportPeriod.Month(month))
                },
            )
        }
    when (model) {
        ScreenModel.Opening ->
            ExpensesScreen(ExpensesUiState(), onAction = {}, asTab = asTab, ownTab = ownTab)

        ScreenModel.Unavailable ->
            PlaceholderRoute(if (ownTab) AppNavKey.ExpensesTab else key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val undo = rememberExpenseUndo()
            val undoTexts = DocumentUndoTexts(
                undoLabel = stringResource(R.string.action_undo),
                undone = stringResource(R.string.confirm_expense_voided),
                failed = stringResource(R.string.error_expense_void),
                retry = stringResource(R.string.action_retry),
            )
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
                when (message) {
                    // El aviso de gasto guardado ofrece `Deshacer`, que sigue aunque se salga.
                    is ExpenseMessage.Saved ->
                        messages.showUndoable(text, message.expenseId, undo, undoTexts)

                    ExpenseMessage.Voided ->
                        messages.show(AppSnackbarVisuals(text, SnackbarVariant.SUCCESS))
                }
                // Desde el atajo de Inicio, guardar vuelve a Inicio, con el aviso encima.
                if (key.startNew && message is ExpenseMessage.Saved) navigator.back()
            }
            // Desde el atajo de Inicio, la hoja del gasto nuevo se abre una sola vez: al volver de
            // confirmar la moneda, o tras girar la pantalla, sigue como estaba.
            var opened by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (key.startNew && !opened) {
                    opened = true
                    val recurringId = key.recurringExpenseId
                    viewModel.onAction(
                        if (recurringId == null) {
                            ExpensesAction.NewExpense
                        } else {
                            ExpensesAction.StartRecurring(recurringId)
                        },
                    )
                }
            }
            ExpensesScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    when (action) {
                        // Sin segmentos, la flecha de atrás también llega como `Ventas`. Como
                        // pestaña no hay ni segmentos ni atrás: no llega.
                        ExpensesAction.ShowSales -> when {
                            ownTab -> Unit
                            onSegment == null -> navigator.back()
                            else -> onSegment(RecordsTab.SALES)
                        }

                        ExpensesAction.ShowPurchases -> onSegment?.invoke(RecordsTab.PURCHASES)

                        ExpensesAction.OpenRecurring -> navigator.open(AppNavKey.RecurringExpenses)

                        ExpensesAction.ConfirmCurrency, ExpensesAction.ChooseZone ->
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
                        is ExpensesAction.StartRecurring,
                        is ExpensesAction.AmountChange,
                        is ExpensesAction.FormCategoryChange,
                        is ExpensesAction.DescriptionChange,
                        is ExpensesAction.DateChange,
                        is ExpensesAction.AccountChange,
                        ExpensesAction.Save,
                        ExpensesAction.CloseForm,
                        ExpensesAction.MessageShown,
                        ExpensesAction.NewCategory,
                        is ExpensesAction.NewCategoryNameChange,
                        ExpensesAction.SaveCategory,
                        ExpensesAction.CloseCategory,
                        ExpensesAction.StartCorrect,
                        ExpensesAction.CloseCorrect,
                        ExpensesAction.ConfirmCorrect,
                        -> Unit
                    }
                },
                asTab = asTab,
                ownTab = ownTab,
                backupBanner = { RootBackupBanner(navigator::open) },
                receiptPhotos = { row ->
                    ReceiptPhotosRoute(AttachmentTarget.Expense(row.id), canAdd = !row.voided)
                },
            )
        }
    }
}
