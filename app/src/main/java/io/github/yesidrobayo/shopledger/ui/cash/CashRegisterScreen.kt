package io.github.yesidrobayo.shopledger.ui.cash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CashSessionCard
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La caja, sin ViewModel: la tarjeta con la caja abierta o cerrada y sus cifras, el aviso si quedó
 * abierta de otro día, y los cierres anteriores con lo contado y la diferencia. La acción fija es
 * `Abrir caja` o `Cerrar caja`.
 *
 * **Es opcional:** la pantalla lo dice cuando nunca se usó, y vender no depende de nada de esto. Las
 * cifras de la caja y de los cierres se ocultan con el modo discreto. En una ventana `Expanded`, la
 * tarjeta va a la izquierda y los cierres a la derecha.
 */
@Composable
fun CashRegisterScreen(
    uiState: CashRegisterUiState,
    onAction: (CashRegisterAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = (uiState.content as? CashRegisterContent.Ready)?.view
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.cash_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(CashRegisterAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                actions = {
                    OverflowMenu(
                        listOf(
                            OverflowAction(stringResource(R.string.cash_menu_denominations), {
                                onAction(CashRegisterAction.OpenDenominations)
                            }),
                        ),
                    )
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(),
            ) {
                when (val content = uiState.content) {
                    CashRegisterContent.Loading -> LoadingSkeleton()

                    CashRegisterContent.ReadError -> CenteredState {
                        EmptyState(
                            icon = R.drawable.ic_error,
                            title = stringResource(R.string.state_error_read),
                        ) {
                            PrimaryActionButton(
                                text = stringResource(R.string.action_retry),
                                onClick = { onAction(CashRegisterAction.Retry) },
                                variant = ActionVariant.TONAL,
                            )
                        }
                    }

                    CashRegisterContent.UnknownZone -> CenteredState {
                        EmptyState(
                            icon = R.drawable.ic_error,
                            title = stringResource(R.string.stats_zone_unknown_title),
                        ) {
                            PrimaryActionButton(
                                text = stringResource(R.string.stats_zone_unknown_action),
                                onClick = { onAction(CashRegisterAction.ChooseZone) },
                                variant = ActionVariant.TONAL,
                            )
                        }
                    }

                    CashRegisterContent.NoCashAccount -> CenteredState {
                        EmptyState(
                            icon = R.drawable.ic_savings,
                            title = stringResource(R.string.cash_empty_title),
                            body = stringResource(R.string.cash_no_cash_account),
                        )
                    }

                    is CashRegisterContent.Ready -> CashBody(content.view, uiState, onAction)
                }
            }
            if (ready != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .keepsMessagesAbove()
                        .navigationBarsPadding()
                        .padding(AppTheme.spacing.s16),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val open = ready.open != null
                    PrimaryActionButton(
                        text = stringResource(
                            if (open) R.string.cash_close_action else R.string.cash_open_action,
                        ),
                        onClick = {
                            val action = if (open) {
                                CashRegisterAction.StartClose
                            } else {
                                CashRegisterAction.StartOpen
                            }
                            onAction(action)
                        },
                        variant = ActionVariant.PRIMARY,
                        modifier = Modifier.widthIn(
                            max = AppTheme.sizes.maxContentWidth,
                        ).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingSkeleton() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow * 3))
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

/** La tarjeta y los cierres: en una columna, o lado a lado en una ventana ancha. */
@Composable
private fun CashBody(
    view: CashRegisterView,
    uiState: CashRegisterUiState,
    onAction: (CashRegisterAction) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= EXPANDED_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = AppTheme.spacing.s16),
                ) { cardItems(view, uiState, onAction) }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = AppTheme.spacing.s16),
                ) { historyItems(view, onAction) }
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .widthIn(max = AppTheme.sizes.maxContentWidth),
                contentPadding = PaddingValues(bottom = AppTheme.spacing.s16),
            ) {
                cardItems(view, uiState, onAction)
                historyItems(view, onAction)
            }
        }
    }
}

/** Las cuentas, si hay más de una, el aviso de otro día y la tarjeta; o que la caja es opcional. */
private fun LazyListScope.cardItems(
    view: CashRegisterView,
    uiState: CashRegisterUiState,
    onAction: (CashRegisterAction) -> Unit,
) {
    if (uiState.accounts.size > 1) {
        item(key = "accounts") {
            AccountChips(uiState.accounts, view.account.id) {
                onAction(CashRegisterAction.SelectAccount(it))
            }
        }
    }
    if (view.isEmpty) {
        item(key = "empty") {
            EmptyState(
                icon = R.drawable.ic_savings,
                title = stringResource(R.string.cash_empty_title),
                body = stringResource(R.string.cash_empty_body),
            )
        }
        return
    }
    val open = view.open
    if (open != null && open.fromAnotherDay) {
        item(key = "other-day") {
            NoticeCard(
                text = stringResource(
                    R.string.cash_open_old_day,
                    LocalAppFormats.current.date.dayMonthYear(open.session.opening.businessDate),
                ),
                variant = NoticeVariant.WARNING,
                modifier = Modifier.padding(
                    horizontal = AppTheme.spacing.s16,
                    vertical = AppTheme.spacing.s8,
                ),
            )
        }
    }
    item(key = "card") {
        val zone = uiState.zone
        val status = if (open != null && zone != null) {
            stringResource(
                R.string.cash_open_since,
                LocalAppFormats.current.date.time(open.session.opening.openedAt, zone),
            )
        } else {
            stringResource(R.string.cash_closed)
        }
        CashSessionCard(
            status = status,
            figures = open?.figures,
            modifier = Modifier.padding(
                horizontal = AppTheme.spacing.s16,
                vertical = AppTheme.spacing.s8,
            ),
        )
    }
}

/** `Historial de caja`: cada cierre con su fecha, lo contado y la diferencia. */
private fun LazyListScope.historyItems(
    view: CashRegisterView,
    onAction: (CashRegisterAction) -> Unit,
) {
    if (view.closed.isEmpty()) return
    item(key = "history") {
        SectionHeader(stringResource(R.string.cash_history))
    }
    items(view.closed, key = { "closed-${it.id}" }) { session ->
        ClosedRow(session) { onAction(CashRegisterAction.OpenClosed(session.id)) }
    }
}

/** Un cierre: la fecha, `Contado …` y la diferencia con palabras, nunca solo con color. */
@Composable
private fun ClosedRow(session: CashSession, onClick: () -> Unit) {
    val closing = session.closing ?: return
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    Column(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(horizontal = spacing.s16, vertical = spacing.s12)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(
                    formats.date.dayMonthYear(session.opening.businessDate),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                DifferenceText(closing.difference, sensitive = true)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    stringResource(R.string.cash_counted),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MoneyText(closing.counted, MoneySize.ROW, sensitive = true)
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * La diferencia con palabras: `Sobrante …`, `Faltante …` o `Sin diferencia`, con su signo dicho y
 * nunca solo con color. Con [sensitive] y el modo discreto se oculta entera.
 */
@Composable
internal fun DifferenceText(difference: Money, sensitive: Boolean) {
    val money = LocalAppFormats.current.money
    val magnitude = if (difference < Money.ZERO) -difference else difference
    val amount = rememberMoneyDisplay(magnitude, money).text.text
    val text = when {
        difference == Money.ZERO -> stringResource(R.string.cash_no_difference)
        difference > Money.ZERO -> stringResource(R.string.cash_over, amount)
        else -> stringResource(R.string.cash_short, amount)
    }
    val color = if (difference < Money.ZERO) {
        AppTheme.colors.loss
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shown: @Composable () -> Unit = {
        Text(text, style = AppTheme.amounts.small, color = color)
    }
    if (sensitive) DiscreetValue(style = AppTheme.amounts.small, content = shown) else shown()
}

/** Las cuentas de efectivo, para ver la caja de cada una. */
@Composable
internal fun AccountChips(accounts: List<MoneyAccount>, selected: Long, onSelect: (Long) -> Unit) {
    val resources = LocalResources.current
    CompactChipRow(
        options = accounts.map { it.id },
        label = { id ->
            val account = accounts.first { it.id == id }
            SeedText.displayName(
                resources,
                SeedTable.MONEY_ACCOUNTS,
                account.systemKey,
                account.details.name,
            )
        },
        selected = setOf(selected),
        onToggle = onSelect,
        multiple = false,
        modifier = Modifier.padding(
            horizontal = AppTheme.spacing.s16,
            vertical = AppTheme.spacing.s8,
        ),
    )
}

/** Un estado que ocupa la pantalla: centrado y desplazable con letra grande. */
@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private const val SKELETON_ROWS = 3

/** La clase de ventana `Expanded`, en la que la tarjeta y los cierres van lado a lado. */
internal val EXPANDED_WIDTH = 840.dp
