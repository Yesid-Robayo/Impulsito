package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveBulkPriceChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlinx.coroutines.flow.Flow

/**
 * La actualización masiva en la navegación. Al aplicar vuelve atrás con su aviso, que sobrevive a
 * la pantalla; un fallo se avisa sin salir, con lo elegido intacto.
 */
@Composable
fun BulkPriceRoute(navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val numbers =
        BulkNumberFormat(
            percentSymbols = formats.percent.inputSymbols,
            moneySymbols = formats.money.inputSymbols,
            currencyDecimals = formats.money.currencyDecimals,
        )
    val model =
        rememberAppViewModel { dependencies ->
            BulkPriceViewModel(
                BulkPriceUseCases.of(dependencies),
                numbers,
                dependencies.container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> BulkPriceScreen(BulkPriceUiState(), {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.BulkPriceUpdate, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val message = uiState.message
            val text = when (message) {
                is BulkMessage.Applied ->
                    pluralStringResource(
                        R.plurals.confirm_prices_updated,
                        message.changed,
                        message.changed,
                    )

                BulkMessage.NothingChanged -> stringResource(R.string.bulk_nothing_changed)

                is BulkMessage.Failed -> stringResource(saveErrorText(message.outOfSpace))

                null -> null
            }
            LaunchedEffect(message) {
                if (text != null) {
                    val variant = when (message) {
                        is BulkMessage.Applied, BulkMessage.NothingChanged, null ->
                            SnackbarVariant.SUCCESS

                        is BulkMessage.Failed -> SnackbarVariant.ERROR
                    }
                    messages.show(AppSnackbarVisuals(text, variant))
                    viewModel.onAction(BulkPriceAction.MessageShown)
                    if (message is BulkMessage.Applied) navigator.back()
                }
            }
            BulkPriceScreen(
                uiState = uiState,
                onAction = { action ->
                    if (action ==
                        BulkPriceAction.Back
                    ) {
                        navigator.back()
                    } else {
                        viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/** Las operaciones de la pantalla sobre los casos de uso de leer y aplicar. */
class BulkPriceUseCases(
    private val choices: ObserveBulkPriceChoicesUseCase,
    private val applyUseCase: ApplyBulkPriceUseCase,
) : BulkPriceOperations {
    override fun catalog(): Flow<List<Product>> = choices.catalog()

    override fun categories(): Flow<List<Category>> = choices.categories()

    override fun suppliers(): Flow<List<Supplier>> = choices.suppliers()

    override fun defaultStep(): Flow<Money> = choices.defaultStep()

    override suspend fun apply(
        productIds: Collection<Long>,
        percent: BasisPoints,
        step: Money,
    ): ApplyBulkPriceResult = applyUseCase(productIds, percent, step)

    companion object {
        fun of(dependencies: ScreenDependencies): BulkPriceUseCases {
            val ports = dependencies.ports
            return BulkPriceUseCases(
                choices = ObserveBulkPriceChoicesUseCase(
                    ports.products,
                    ports.categories,
                    ports.suppliers,
                    ports.settings,
                ),
                applyUseCase = ApplyBulkPriceUseCase(
                    ports.transactions,
                    ports.products,
                    ports.systemRows,
                    ports.priceHistory,
                    BusinessCalendarSource(ports.settings),
                    CurrencyLock(ports.settings),
                    dependencies.container.clock,
                ),
            )
        }
    }
}
