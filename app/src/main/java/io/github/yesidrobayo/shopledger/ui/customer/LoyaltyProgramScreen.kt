package io.github.yesidrobayo.shopledger.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.PointsSummary
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ObserveLoyaltyProgramUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveLoyaltyProgramUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El programa de puntos en la navegación: arma su ViewModel con los puertos de la base. Si la base
 * no abre, muestra lo que da la navegación para ese caso.
 */
@Composable
fun LoyaltyProgramRoute(key: AppNavKey, navigator: AppNavigator) {
    val money = LocalAppFormats.current.money
    val numbers = LoyaltyNumbers(money.inputSymbols, money.currencyDecimals)
    val model =
        rememberAppViewModel(key = "loyalty-program") { deps ->
            val ports = deps.ports
            val observe = ObserveLoyaltyProgramUseCase(ports.settings, ports.customers)
            val save =
                SaveLoyaltyProgramUseCase(ports.transactions, ports.settings, deps.container.clock)
            LoyaltyProgramViewModel(observe = { observe() }, save = { save(it) }, numbers = numbers)
        }
    when (model) {
        ScreenModel.Opening -> Unit

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            LoyaltyProgramScreen(uiState) { action ->
                when (action) {
                    LoyaltyProgramAction.Back -> navigator.back()
                    LoyaltyProgramAction.OpenCustomers -> navigator.open(AppNavKey.Customers())
                    else -> viewModel.onAction(action)
                }
            }
        }
    }
}

/** La pantalla del programa de puntos, sin ViewModel: dibuja [uiState] y avisa cada acción. */
@Composable
fun LoyaltyProgramScreen(
    uiState: LoyaltyProgramUiState,
    modifier: Modifier = Modifier,
    onAction: (LoyaltyProgramAction) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val ready = uiState as? LoyaltyProgramUiState.Ready
    val showsForm = ready?.form?.enabled == true
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.loyalty_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(LoyaltyProgramAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                actions = { ScreenHelpLink(HelpTopic.CUSTOMERS) },
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                when (uiState) {
                    LoyaltyProgramUiState.Loading -> LoadingFields()

                    LoyaltyProgramUiState.ReadFailed ->
                        EmptyState(
                            icon = R.drawable.ic_error,
                            title = stringResource(R.string.state_error_read),
                        ) {
                            InlineTextButton(
                                stringResource(R.string.action_retry),
                                { onAction(LoyaltyProgramAction.Retry) },
                            )
                        }

                    is LoyaltyProgramUiState.Ready -> LoyaltyContent(uiState, onAction)
                }
            }
            if (ready != null && showsForm) SaveBar(ready, onAction)
        }
        AppSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
    if (ready != null) {
        if (ready.confirmTurnOff) {
            ConfirmDialog(
                title = stringResource(R.string.loyalty_off_confirm_title),
                body = stringResource(R.string.loyalty_off_confirm_body),
                confirmLabel = stringResource(R.string.loyalty_off_confirm_action),
                onConfirm = { onAction(LoyaltyProgramAction.ConfirmTurnOff) },
                onDismiss = { onAction(LoyaltyProgramAction.DismissTurnOff) },
            )
        }
        val saved = if (ready.savedMessage) stringResource(R.string.loyalty_saved) else null
        LaunchedEffect(saved) {
            if (saved != null) {
                snackbar.showSnackbar(saved)
                onAction(LoyaltyProgramAction.MessageShown)
            }
        }
    }
}

@Composable
private fun LoyaltyContent(
    state: LoyaltyProgramUiState.Ready,
    onAction: (LoyaltyProgramAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val form = state.form
    Column(
        Modifier
            .fillMaxSize()
            .wrapContentWidth()
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        SettingsRow(
            title = stringResource(R.string.loyalty_enabled),
            action = SettingsRowAction.Toggle(form.enabled) {
                onAction(LoyaltyProgramAction.SetEnabled(it))
            },
        )
        NoticeCard(
            text = stringResource(R.string.loyalty_not_money),
            variant = NoticeVariant.INFO,
            modifier = Modifier.padding(horizontal = spacing.s16),
        )
        if (!form.enabled) {
            EmptyState(
                icon = R.drawable.ic_sell,
                title = stringResource(R.string.loyalty_empty_title),
                body = stringResource(R.string.loyalty_empty_body),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.loyalty_empty_action),
                    onClick = { onAction(LoyaltyProgramAction.SetEnabled(true)) },
                    variant = ActionVariant.TONAL,
                )
            }
        } else {
            ProgramFields(state, onAction)
        }
    }
}

@Composable
private fun ProgramFields(
    state: LoyaltyProgramUiState.Ready,
    onAction: (LoyaltyProgramAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val form = state.form
    Column(
        Modifier.padding(horizontal = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        if (!state.hasCustomers) {
            NoticeCard(
                text = stringResource(R.string.loyalty_no_customers),
                variant = NoticeVariant.INFO,
                actionLabel = stringResource(R.string.customer_picker_view_all),
                onAction = { onAction(LoyaltyProgramAction.OpenCustomers) },
            )
        }
        MoneyField(
            value = form.amountPerPoint,
            onValueChange = { onAction(LoyaltyProgramAction.AmountPerPoint(it)) },
            label = stringResource(R.string.loyalty_amount_per_point),
            error = form.amountPerPointError?.let { amountErrorText(it) },
        )
        MoneyField(
            value = form.pointValue,
            onValueChange = { onAction(LoyaltyProgramAction.PointValue(it)) },
            label = stringResource(R.string.loyalty_point_value),
            error = form.pointValueError?.let { amountErrorText(it) },
            imeAction = ImeAction.Done,
        )
        state.example?.let { ExampleCard(it) }
        if (state.valuesChanged) {
            Text(
                stringResource(R.string.loyalty_change_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PointsTotals(state.points)
    }
}

/**
 * El ejemplo en vivo: un solo foco y región viva educada, que TalkBack anuncia al cambiar los
 * montos, con el nombre de la moneda en lugar del símbolo.
 */
@Composable
private fun ExampleCard(example: LoyaltyExample) {
    val formats = LocalAppFormats.current
    val sale = rememberMoneyDisplay(example.saleAmount, formats.money)
    val discount = rememberMoneyDisplay(example.discount, formats.money)
    val earned = formats.quantity.formatCount(example.earnedPoints)
    val redeemed = formats.quantity.formatCount(example.redeemedPoints)
    val earnCount = example.earnedPoints.toPluralCount()
    val redeemCount = example.redeemedPoints.toPluralCount()
    val earnReading =
        pluralStringResource(R.plurals.loyalty_example_earn, earnCount, sale.description, earned)
    val redeemReading =
        pluralStringResource(
            R.plurals.loyalty_example_redeem,
            redeemCount,
            redeemed,
            discount.description,
        )
    AppCard(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            pluralStringResource(
                R.plurals.loyalty_example_earn,
                earnCount,
                sale.text.text,
                earned,
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { contentDescription = earnReading },
        )
        Text(
            pluralStringResource(
                R.plurals.loyalty_example_redeem,
                redeemCount,
                redeemed,
                discount.text.text,
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { contentDescription = redeemReading },
        )
    }
}

@Composable
private fun PointsTotals(points: PointsSummary) {
    if (points.totalPoints <= 0L) return
    val quantity = LocalAppFormats.current.quantity
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        Text(
            stringResource(R.string.loyalty_total_points, quantity.formatCount(points.totalPoints)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                R.string.loyalty_customers_with_points,
                quantity.formatCount(points.customersWithPoints.toLong()),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SaveBar(state: LoyaltyProgramUiState.Ready, onAction: (LoyaltyProgramAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .keepsMessagesAbove()
            .navigationBarsPadding()
            .imePadding()
            .padding(spacing.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        if (state.saveFailed) {
            NoticeCard(
                text = stringResource(saveErrorText(state.outOfSpace)),
                variant = NoticeVariant.ERROR,
                modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth),
            )
        }
        PrimaryActionButton(
            text = stringResource(R.string.loyalty_save_action),
            onClick = { onAction(LoyaltyProgramAction.Save) },
            variant = ActionVariant.PRIMARY,
            state = if (state.saving) ActionState.WORKING else ActionState.IDLE,
            modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth),
        )
    }
}

@Composable
private fun amountErrorText(error: LoyaltyAmountError): String {
    val money = LocalAppFormats.current.money
    return when (error) {
        LoyaltyAmountError.REQUIRED -> stringResource(R.string.loyalty_error_required)

        LoyaltyAmountError.NOT_A_NUMBER -> stringResource(R.string.field_error_number)

        LoyaltyAmountError.DECIMALS -> pluralStringResource(
            R.plurals.error_money_decimals,
            money.currencyDecimals,
            money.currencyDecimals,
        )

        LoyaltyAmountError.TOO_HIGH -> stringResource(R.string.error_value_high)
    }
}

@Composable
private fun LoadingFields() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(SKELETON_FIELDS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle))
            }
        }
    }
}

/** Un conteo largo como cantidad de un plural: lo que pasa de `Int` se lee como «muchos». */
private fun Long.toPluralCount(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private const val SKELETON_FIELDS = 4

private val previewReady = LoyaltyProgramUiState.Ready(
    form = LoyaltyForm(enabled = true, amountPerPoint = "1000", pointValue = "5"),
    savedEnabled = true,
    valuesChanged = false,
    example = LoyaltyExample(Money(10_000), 10, 100, Money(500)),
    points = PointsSummary(1_250, 14),
    hasCustomers = true,
)

@ThemePreviews
@Composable
private fun LoyaltyProgramActivePreview() {
    PreviewTheme { LoyaltyProgramScreen(previewReady) {} }
}

@ThemePreviews
@Composable
private fun LoyaltyProgramOffPreview() {
    PreviewTheme {
        LoyaltyProgramScreen(
            previewReady.copy(
                form = LoyaltyForm(enabled = false, amountPerPoint = "", pointValue = ""),
                savedEnabled = false,
                example = null,
                points = PointsSummary.NONE,
            ),
        ) {}
    }
}

@ThemePreviews
@Composable
private fun LoyaltyProgramErrorsPreview() {
    PreviewTheme {
        LoyaltyProgramScreen(
            previewReady.copy(
                form = LoyaltyForm(
                    enabled = true,
                    amountPerPoint = "",
                    pointValue = "99999999999999",
                    amountPerPointError = LoyaltyAmountError.REQUIRED,
                    pointValueError = LoyaltyAmountError.TOO_HIGH,
                ),
                savedEnabled = false,
                example = null,
                hasCustomers = false,
                saveFailed = true,
            ),
        ) {}
    }
}
