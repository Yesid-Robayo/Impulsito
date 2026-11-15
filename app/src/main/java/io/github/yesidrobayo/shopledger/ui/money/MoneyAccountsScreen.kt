package io.github.yesidrobayo.shopledger.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.payment.ListDetailPanes
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Las cuentas de dinero, sin ViewModel: en una ventana `Expanded`, la lista a la izquierda y el libro
 * de la cuenta abierta a la derecha; si no, la lista o el libro, uno a la vez. Encima van las hojas y
 * las confirmaciones.
 */
@Composable
fun MoneyAccountsContentHost(
    uiState: MoneyAccountsUiState,
    onAction: (MoneyAccountsAction) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= EXPANDED_WIDTH) {
            ListDetailPanes(
                list = { MoneyAccountsScreen(uiState, onAction, it) },
                detail = {
                    if (uiState.selectedId == null) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                            EmptyState(
                                icon = R.drawable.ic_savings,
                                title = stringResource(R.string.money_title),
                            )
                        }
                    } else {
                        AccountDetailScreen(uiState, onAction, TopBarNavigation.NONE)
                    }
                },
            )
        } else if (uiState.selectedId != null) {
            AccountDetailScreen(uiState, onAction, TopBarNavigation.BACK)
        } else {
            MoneyAccountsScreen(uiState, onAction)
        }
    }
    uiState.manual?.let { ManualSheetHost(uiState, it, onAction) }
    uiState.transfer?.let { TransferSheetHost(uiState, it, onAction) }
    uiState.editor?.let { AccountEditorHost(it, onAction) }
    uiState.movement?.let { MovementSheetHost(uiState, it, onAction) }
    uiState.dialog?.let { MoneyConfirm(it, onAction) }
}

/**
 * La lista: el héroe `Dinero disponible` con lo de la moneda del negocio, las cuatro acciones sin una
 * que mande, cada cuenta con su clase y su saldo, y aparte las de otras monedas, que nunca se suman.
 * Con el menú, las archivadas. Saldos y lo que falta por acreditar se ocultan con el modo discreto.
 */
@Composable
fun MoneyAccountsScreen(
    uiState: MoneyAccountsUiState,
    onAction: (MoneyAccountsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = (uiState.content as? MoneyAccountsContent.Ready)?.view
    val archivedMode = uiState.showArchived
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.money_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = {
                        onAction(
                            if (archivedMode) {
                                MoneyAccountsAction.ShowArchived(false)
                            } else {
                                MoneyAccountsAction.Back
                            },
                        )
                    },
                    navigationLabel = stringResource(R.string.a11y_back),
                    actions = {
                        ScreenHelpLink(HelpTopic.MONEY_ACCOUNTS)
                        if (view != null && view.archived.isNotEmpty() && !archivedMode) {
                            OverflowMenu(
                                listOf(
                                    OverflowAction(stringResource(R.string.money_menu_archived), {
                                        onAction(MoneyAccountsAction.ShowArchived(true))
                                    }),
                                ),
                            )
                        }
                    },
                )
                when (val content = uiState.content) {
                    MoneyAccountsContent.Loading -> ListSkeleton()

                    MoneyAccountsContent.ReadError -> CenteredState {
                        EmptyState(
                            icon = R.drawable.ic_error,
                            title = stringResource(R.string.state_error_read),
                        ) {
                            PrimaryActionButton(
                                text = stringResource(R.string.action_retry),
                                onClick = { onAction(MoneyAccountsAction.Retry) },
                                variant = ActionVariant.TONAL,
                            )
                        }
                    }

                    is MoneyAccountsContent.Ready -> LazyColumn(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = AppTheme.sizes.maxContentWidth)
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
                    ) {
                        if (archivedMode) {
                            archivedItems(content.view, uiState.selectedId, onAction)
                        } else {
                            listItems(content.view, uiState.selectedId, onAction)
                        }
                    }
                }
            }
            if (view != null && view.inUse && !archivedMode) {
                TonalFab(
                    text = stringResource(R.string.money_new_account_action),
                    onClick = { onAction(MoneyAccountsAction.NewAccount) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .keepsMessagesAbove(),
                )
            }
        }
    }
}

private fun LazyListScope.listItems(
    view: MoneyAccountsView,
    selectedId: Long?,
    onAction: (MoneyAccountsAction) -> Unit,
) {
    item(key = "hero") {
        AvailableHero(
            view.available,
            Modifier.padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8),
        )
    }
    if (view.active.isNotEmpty()) {
        item(key = "actions") { MoneyActions(onAction) }
    }
    if (!view.inUse) {
        item(key = "empty") {
            EmptyState(
                icon = R.drawable.ic_savings,
                title = stringResource(R.string.money_empty_title),
                body = stringResource(R.string.money_empty_body),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.money_empty_action),
                    onClick = { onAction(MoneyAccountsAction.NewAccount) },
                    variant = ActionVariant.TONAL,
                )
            }
        }
        return
    }
    items(view.accounts, key = { "account-${it.account.id}" }) { row ->
        AccountListRow(row, selected = row.account.id == selectedId) {
            onAction(MoneyAccountsAction.Select(row.account.id))
        }
    }
    if (view.others.isNotEmpty()) {
        item(key = "others") { SectionHeader(stringResource(R.string.money_other_currencies)) }
        items(view.others, key = { "other-${it.account.id}" }) { row ->
            AccountListRow(row, selected = row.account.id == selectedId) {
                onAction(MoneyAccountsAction.Select(row.account.id))
            }
        }
    }
}

private fun LazyListScope.archivedItems(
    view: MoneyAccountsView,
    selectedId: Long?,
    onAction: (MoneyAccountsAction) -> Unit,
) {
    item(key = "archived") { SectionHeader(stringResource(R.string.money_menu_archived)) }
    items(view.archived, key = { "archived-${it.account.id}" }) { row ->
        AccountListRow(row, selected = row.account.id == selectedId) {
            onAction(MoneyAccountsAction.Select(row.account.id))
        }
    }
}

/**
 * El héroe: `Dinero disponible`, la suma de las cuentas en la moneda del negocio, en grande y en la
 * tinta del héroe. Con el modo discreto se oculta.
 */
@Composable
internal fun AvailableHero(available: Money?, modifier: Modifier = Modifier) {
    val app = AppTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(app.heroContainer, MaterialTheme.shapes.extraLarge)
            .padding(AppTheme.spacing.s20)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Text(
            stringResource(R.string.money_available),
            style = MaterialTheme.typography.labelLarge,
            color = app.onHeroVariant,
        )
        MoneyText(available, MoneySize.HERO, tone = MoneyTone.ON_HERO, sensitive = true)
    }
}

/**
 * `Retiro`, `Aporte`, `Otro ingreso` y `Transferir`, en dos filas y del mismo peso: ninguna manda.
 * Con letra grande van una debajo de otra, para que ninguna se corte.
 */
@Composable
private fun MoneyActions(onAction: (MoneyAccountsAction) -> Unit) {
    val spacing = AppTheme.spacing
    val actions = listOf(
        R.string.money_draw_action to MoneyAccountsAction.StartManual(ManualMoneyKind.OWNER_DRAW),
        R.string.money_contribution_action to
            MoneyAccountsAction.StartManual(ManualMoneyKind.OWNER_CONTRIBUTION),
        R.string.money_other_income_action to
            MoneyAccountsAction.StartManual(ManualMoneyKind.OTHER_INCOME),
        R.string.money_transfer_action to MoneyAccountsAction.StartTransfer,
    )
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    Column(
        Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        actions.chunked(if (stacked) 1 else 2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s8)) {
                pair.forEach { (text, action) ->
                    PrimaryActionButton(
                        text = stringResource(text),
                        onClick = { onAction(action) },
                        variant = ActionVariant.TONAL,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Una cuenta: el nombre y, debajo, su clase y `Por acreditar` si algo falta por llegar; a la
 * derecha, el saldo en su moneda, en negativo con la tinta de pérdida y su signo. Una fila, un foco.
 * Con letra grande el saldo pasa debajo del nombre.
 */
@Composable
private fun AccountListRow(row: AccountRow, selected: Boolean, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val account = row.account
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    ProvideAccountCurrency(account.currency) {
        val description = accountRowDescription(row)
        Surface(color = if (selected) scheme.secondaryContainer else scheme.surface) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onClick)
                        .heightIn(min = AppTheme.sizes.listRow)
                        .padding(horizontal = spacing.s16, vertical = spacing.s12)
                        // Una fila, un foco, en el mismo nodo que se toca: la cuenta, su clase, el
                        // saldo y lo que falta por acreditar, con el nombre de la moneda.
                        .clearAndSetSemantics {
                            contentDescription = description
                            isSensitiveData = true
                            if (selected) this.selected = true
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s12),
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(spacing.s2),
                    ) {
                        Text(
                            accountName(account),
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(kindLabel(account.details.kind)),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        row.pending?.let { PendingLine(it) }
                        if (stacked) Balance(account)
                    }
                    if (!stacked) Balance(account)
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = spacing.s16),
                    thickness = AppTheme.sizes.hairline,
                    color = scheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun Balance(account: MoneyAccount) {
    MoneyText(
        account.balance,
        MoneySize.ROW,
        tone = if (account.balance < Money.ZERO) MoneyTone.LOSS else MoneyTone.DEFAULT,
        sensitive = true,
    )
}

/**
 * Lo que dice TalkBack de una cuenta: el nombre, la clase, `saldo …` y, si algo falta por llegar,
 * `Por acreditar: …`. Con el modo discreto, cada cifra se dice `Oculto` y el rótulo se conserva.
 */
@Composable
private fun accountRowDescription(row: AccountRow): String {
    val account = row.account
    val money = LocalAppFormats.current.money
    val discreet = LocalDiscreetMode.current
    val hidden = stringResource(R.string.a11y_discreet_hidden)
    val balance = rememberMoneyDisplay(account.balance, money).description
    val pending = row.pending?.let { rememberMoneyDisplay(it, money).description }
    val base = stringResource(
        R.string.a11y_money_account_row,
        accountName(account),
        stringResource(kindLabel(account.details.kind)),
        if (discreet) hidden else balance,
    )
    return pending?.let {
        stringResource(
            R.string.a11y_label_value,
            base,
            stringResource(R.string.a11y_money_pending, if (discreet) hidden else it),
        )
    } ?: base
}

/** `Por acreditar: …`, lo que ya está en el saldo pero todavía no llega. */
@Composable
internal fun PendingLine(pending: Money) {
    val display = rememberMoneyDisplay(pending, LocalAppFormats.current.money)
    val style = AppTheme.amounts.small
    // TalkBack lee el nombre de la moneda, nunca el símbolo.
    val description = stringResource(R.string.a11y_money_pending, display.description)
    DiscreetValue(style = style) {
        Text(
            stringResource(R.string.money_pending_settlement, display.text.text),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics {
                contentDescription = description
                isSensitiveData = true
            },
        )
    }
}

@Composable
private fun ListSkeleton() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow * 2))
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

/** Un estado que ocupa la pantalla: centrado y desplazable con letra grande. */
@Composable
internal fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private const val SKELETON_ROWS = 4

/** Desde esta escala de letra, las acciones y los saldos van uno debajo de otro. */
internal const val STACKED_FONT_SCALE = 1.5f

/** La clase de ventana `Expanded`, en la que la lista y el libro van lado a lado. */
internal val EXPANDED_WIDTH = 840.dp
