package io.github.yesidrobayo.shopledger.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyStatementLine
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.LedgerRow
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El libro de una cuenta: su nombre con `Editar` y el menú `Archivar` (o `Reactivar` si está
 * archivada), el `Saldo` grande y los movimientos por día, del más reciente al más viejo. Cada fila
 * dice su tipo, su hora y su nota, el monto con signo y el saldo que dejó; tocarla abre el
 * movimiento. Cifras en la moneda de la cuenta, y ocultas con el modo discreto.
 */
@Composable
fun AccountDetailScreen(
    uiState: MoneyAccountsUiState,
    onAction: (MoneyAccountsAction) -> Unit,
    navigation: TopBarNavigation,
    modifier: Modifier = Modifier,
) {
    val detail = uiState.detail
    val ready = detail as? AccountDetailContent.Ready
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar(ready, navigation, onAction)
            when (detail) {
                AccountDetailContent.Loading -> DetailSkeleton()

                AccountDetailContent.Missing -> CenteredState {
                    EmptyState(
                        icon = R.drawable.ic_savings,
                        title = stringResource(R.string.money_title),
                    )
                }

                is AccountDetailContent.Ready -> ProvideAccountCurrency(
                    detail.row.account.currency,
                ) {
                    Ledger(detail, uiState.today, onAction)
                }
            }
        }
    }
}

@Composable
private fun DetailTopBar(
    ready: AccountDetailContent.Ready?,
    navigation: TopBarNavigation,
    onAction: (MoneyAccountsAction) -> Unit,
) {
    val account = ready?.row?.account
    AppTopBar(
        title = account?.let { accountName(it) } ?: stringResource(R.string.money_title),
        navigation = navigation,
        onNavigate = { onAction(MoneyAccountsAction.Select(null)) },
        navigationLabel = stringResource(R.string.a11y_back),
        actions = {
            if (account != null) {
                TopBarAction(R.drawable.ic_edit, stringResource(R.string.action_edit)) {
                    onAction(MoneyAccountsAction.EditAccount)
                }
                OverflowMenu(
                    listOf(
                        if (account.isArchived) {
                            OverflowAction(stringResource(R.string.action_reactivate), {
                                onAction(MoneyAccountsAction.Reactivate)
                            })
                        } else {
                            OverflowAction(stringResource(R.string.action_archive), {
                                onAction(MoneyAccountsAction.AskArchive)
                            })
                        },
                    ),
                )
            }
        },
    )
}

@Composable
private fun Ledger(
    detail: AccountDetailContent.Ready,
    today: BusinessDate?,
    onAction: (MoneyAccountsAction) -> Unit,
) {
    val formats = LocalAppFormats.current
    val openLabel = stringResource(R.string.action_view)
    LazyColumn(
        Modifier
            .fillMaxSize()
            .wrapContentWidth()
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = AppTheme.spacing.s32),
    ) {
        item(key = "balance") { BalanceBlock(detail.row) }
        if (detail.days.isEmpty()) {
            item(key = "no-movements") {
                EmptyStateInline(
                    title = stringResource(R.string.money_empty_movements),
                    modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
                )
            }
        }
        detail.days.forEach { day ->
            item(key = "day-${day.date}") {
                SectionHeader(formats.date.dayMonthYear(day.date))
            }
            items(day.lines, key = { "movement-${it.movement.id}" }) { line ->
                LedgerRow(
                    title = stringResource(movementLabel(line.movement.type)),
                    detail = movementDetail(line, detail.zone, today),
                    amount = line.movement.amount,
                    balanceAfter = line.movement.balanceAfter,
                    balanceText = R.string.money_balance_after,
                    voided = line.voided,
                    sensitive = true,
                    onClick = { onAction(MoneyAccountsAction.OpenMovement(line.movement.id)) },
                    onClickLabel = openLabel,
                )
            }
        }
    }
}

/**
 * `Saldo` en grande, en negativo con la tinta de pérdida y su signo, con la clase de la cuenta y lo
 * que falta por acreditar.
 */
@Composable
private fun BalanceBlock(row: AccountRow) {
    val account = row.account
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s16, vertical = spacing.s12)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
    ) {
        Text(
            stringResource(R.string.money_balance),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoneyText(
            account.balance,
            MoneySize.LARGE,
            tone = if (account.balance < Money.ZERO) MoneyTone.LOSS else MoneyTone.DEFAULT,
            sensitive = true,
        )
        Text(
            stringResource(kindLabel(account.details.kind)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        row.pending?.let { PendingLine(it) }
    }
}

/**
 * La hora del movimiento, su nota y, si todavía no llega, `Disponible el …`. Cada parte es un dato
 * suelto, no una frase: se separan con un punto medio, como en la fila de una conversión.
 */
@Composable
internal fun movementDetail(
    line: MoneyStatementLine,
    zone: BusinessTimeZone?,
    today: BusinessDate?,
): String {
    val formats = LocalAppFormats.current
    val movement = line.movement
    val available = movement.draft.availableDate
        ?.takeIf { today != null && it > today }
        ?.let { stringResource(R.string.money_available_on, formats.date.dayMonthYear(it)) }
    return listOfNotNull(
        zone?.let { formats.date.time(movement.time.occurredAt, it) },
        movement.draft.note,
        available,
    ).joinToString(DETAIL_SEPARATOR)
}

@Composable
private fun DetailSkeleton() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
            horizontalAlignment = Alignment.Start,
        ) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            repeat(DETAIL_SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

private const val DETAIL_SKELETON_ROWS = 4

private const val DETAIL_SEPARATOR = " · "
