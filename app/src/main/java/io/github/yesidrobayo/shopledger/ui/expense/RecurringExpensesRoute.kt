package io.github.yesidrobayo.shopledger.ui.expense

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ObserveRecurringExpensesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveRecurringExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SetRecurringExpenseArchivedUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SkipRecurringExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObservePayingAccountsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlinx.coroutines.flow.combine

/**
 * Los gastos recurrentes en la navegación: arma su ViewModel con los casos de uso y resuelve
 * adónde se va. `Registrar` abre el gasto nuevo con los datos del vencimiento, y guardarlo vuelve
 * aquí. Cada acción deja su aviso breve en la navegación. Si la base va al rescate, la pantalla no
 * lee nada.
 */
@Composable
fun RecurringExpensesRoute(navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val numbers = ExpenseNumberFormat(formats.money.inputSymbols, formats.money.currencyDecimals)
    val model =
        rememberAppViewModel(key = "recurring-expenses") { dependencies ->
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val recurring = ports.recurringExpenses
            val calendar = BusinessCalendarSource(ports.settings)
            val observe = ObserveRecurringExpensesUseCase(recurring, calendar, clock)
            val save = SaveRecurringExpenseUseCase(
                ports.transactions,
                recurring,
                ports.expenseCategories,
                clock,
            )
            val skip = SkipRecurringExpenseUseCase(ports.transactions, recurring, clock)
            val archive = SetRecurringExpenseArchivedUseCase(ports.transactions, recurring, clock)
            val categories = ports.expenseCategories
            RecurringExpensesViewModel(
                overview = observe(),
                archived = observe.archived(),
                calendar = calendar.observe(),
                categories = combine(
                    categories.observe(ArchiveFilter.ACTIVE),
                    categories.observe(ArchiveFilter.ARCHIVED),
                ) { active, archived -> (active + archived).sortedBy { it.id } },
                accounts = ObservePayingAccountsUseCase(
                    ports.moduleUsage,
                    ports.settings,
                    ports.moneyAccounts,
                )(),
                save = { id, draft -> save(id, draft) },
                skip = { skip(it) },
                setArchived = { id, archived -> archive(id, archived) },
                numbers = numbers,
            )
        }
    when (model) {
        ScreenModel.Opening -> RecurringExpensesScreen(RecurringExpensesUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.RecurringExpenses, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val message = uiState.message
            val text = message?.let { messageText(it) }
            LaunchedEffect(message) {
                if (message == null || text == null) return@LaunchedEffect
                viewModel.onAction(RecurringExpensesAction.MessageShown)
                val variant = when (message) {
                    is RecurringMessage.Failed -> SnackbarVariant.ERROR
                    RecurringMessage.AlreadyHandled -> SnackbarVariant.INFO
                    else -> SnackbarVariant.SUCCESS
                }
                messages.show(AppSnackbarVisuals(text, variant))
            }
            RecurringExpensesScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    when (action) {
                        RecurringExpensesAction.Back -> navigator.back()

                        RecurringExpensesAction.ChooseZone ->
                            navigator.open(AppNavKey.RegionalSettings())

                        is RecurringExpensesAction.Register -> navigator.open(
                            AppNavKey.Expenses(
                                startNew = true,
                                recurringExpenseId = action.recurringExpenseId,
                            ),
                        )

                        else -> Unit
                    }
                },
            )
        }
    }
}

@Composable
private fun messageText(message: RecurringMessage): String {
    val formats = LocalAppFormats.current
    return when (message) {
        RecurringMessage.Saved -> stringResource(R.string.recurring_saved)

        is RecurringMessage.Skipped ->
            stringResource(R.string.recurring_skipped, formats.date.longDate(message.nextDate))

        RecurringMessage.Archived -> stringResource(R.string.recurring_archived_done)

        RecurringMessage.Reactivated -> stringResource(R.string.recurring_reactivated)

        is RecurringMessage.Failed -> stringResource(saveErrorText(message.outOfSpace))

        RecurringMessage.AlreadyHandled -> stringResource(R.string.recurring_handled_elsewhere)
    }
}
