package io.github.yesidrobayo.shopledger.ui.order

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OrderStatusTrack
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.payment.paymentMethodName
import io.github.yesidrobayo.shopledger.ui.sale.unitLabelOf
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El detalle de un pedido: en qué va, cliente, entrega y dirección, sus líneas con lo que reservan
 * y lo que les falta, y el resumen con el envío, el anticipo y lo que queda por cobrar al entregar.
 *
 * - Pendiente, abajo va `Marcar como listo`; listo, `Registrar entrega`, y el menú ofrece `Volver a
 *   pendiente`.
 * - Entregado o cancelado, no tiene barra: entregado ofrece `Ver venta`.
 * - Una falta de stock se avisa en la línea y nunca impide entregar.
 * - Nunca muestra costo, margen ni ganancia. El anticipo es dinero del cliente, y se ve.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun OrderDetailScreen(
    uiState: OrderDetailUiState,
    onAction: (OrderDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notices = remember { SnackbarHostState() }
    val failed = stringResource(R.string.state_error_save)
    val cancelled = stringResource(R.string.order_cancelled)
    val deposited = stringResource(R.string.order_deposit_recorded)
    val deliverFailed = stringResource(R.string.order_error_deliver)
    LaunchedEffect(uiState.notice) {
        val notice = uiState.notice ?: return@LaunchedEffect
        onAction(OrderDetailAction.NoticeShown)
        val (text, variant) = when (notice) {
            OrderDetailNotice.CHANGE_FAILED -> failed to SnackbarVariant.ERROR
            OrderDetailNotice.CANCELLED -> cancelled to SnackbarVariant.SUCCESS
            OrderDetailNotice.DEPOSIT_RECORDED -> deposited to SnackbarVariant.SUCCESS
            OrderDetailNotice.DELIVER_FAILED -> deliverFailed to SnackbarVariant.ERROR
        }
        notices.showSnackbar(AppSnackbarVisuals(text, variant))
    }
    val order = (uiState.content as? OrderDetailContent.Ready)?.order
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = order?.let {
                        orderTitle(it.id)
                    } ?: stringResource(R.string.order_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(OrderDetailAction.Back) },
                    navigationLabel = stringResource(R.string.a11y_back),
                    actions = { if (order != null) DetailMenu(order, onAction) },
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    when (val content = uiState.content) {
                        OrderDetailContent.Loading -> DelayedSkeleton { DetailSkeleton() }

                        OrderDetailContent.Missing, OrderDetailContent.Failed -> EmptyState(
                            icon = R.drawable.ic_error,
                            title = stringResource(R.string.state_error_read),
                        ) {
                            if (content == OrderDetailContent.Failed) {
                                PrimaryActionButton(
                                    text = stringResource(R.string.action_retry),
                                    onClick = { onAction(OrderDetailAction.Retry) },
                                    variant = ActionVariant.TONAL,
                                )
                            }
                        }

                        is OrderDetailContent.Ready -> DetailBody(content.order, onAction)
                    }
                }
                if (order != null) ActionBar(order.status, uiState.working, onAction)
            }
            AppSnackbarHost(
                notices,
                Modifier.align(Alignment.TopCenter).padding(top = AppTheme.sizes.topBar),
            )
        }
    }
    Dialogs(uiState, onAction)
}

/** `Editar`, `Volver a pendiente` y `Cancelar pedido`, cada uno solo cuando aplica. */
@Composable
private fun DetailMenu(order: OrderDetail, onAction: (OrderDetailAction) -> Unit) {
    val open = order.status.isOpen
    if (!open) return
    val edit = stringResource(R.string.action_edit)
    val back = stringResource(R.string.order_back_to_pending_action)
    val cancel = stringResource(R.string.order_cancel_action)
    OverflowMenu(
        buildList {
            add(OverflowAction(edit, { onAction(OrderDetailAction.Edit) }))
            if (order.status == OrderStatus.READY) {
                add(OverflowAction(back, { onAction(OrderDetailAction.BackToPending) }))
            }
            add(OverflowAction(cancel, { onAction(OrderDetailAction.AskCancel) }))
        },
    )
}

@Composable
private fun DetailBody(order: OrderDetail, onAction: (OrderDetailAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .verticalScroll(rememberScrollState())
            .padding(vertical = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Column(
            Modifier.padding(horizontal = spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            MoneyText(order.total, MoneySize.LARGE)
            if (order.status == OrderStatus.CANCELLED) {
                StatusTag(stringResource(R.string.order_status_cancelled))
            }
            if (order.overdue) {
                StatusTag(stringResource(R.string.order_status_overdue), tone = StatusTone.WARNING)
            }
        }
        // Cancelado no avanza: lo dice su etiqueta, no el avance.
        if (order.status != OrderStatus.CANCELLED) {
            OrderStatusTrack(
                labels = listOf(
                    stringResource(R.string.order_status_pending),
                    stringResource(R.string.order_status_ready),
                    stringResource(R.string.order_status_delivered),
                ),
                current = stepOf(order.status),
            )
        }
        Delivery(order, onAction)
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Column {
            order.lines.forEach { line -> LineRow(line) }
        }
        Summary(order)
        DepositEntry(order, onAction)
    }
}

/** Cliente, que abre su ficha, fecha de entrega, dirección y nota. */
@Composable
private fun Delivery(order: OrderDetail, onAction: (OrderDetailAction) -> Unit) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
        val name = order.customerName ?: stringResource(R.string.order_no_customer)
        val customer = if (order.customerId != null) {
            Modifier.clickable(role = Role.Button) { onAction(OrderDetailAction.OpenCustomer) }
        } else {
            Modifier
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            modifier = customer
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
        )
        Column(
            Modifier.padding(horizontal = spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            order.dueDate?.let {
                Text(
                    stringResource(R.string.order_delivery_row, formats.date.dayMonth(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }
            order.address?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            order.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (order.status == OrderStatus.DELIVERED && order.deliveredSaleId != null) {
                InlineTextButton(
                    text = stringResource(R.string.order_view_sale_action),
                    onClick = { onAction(OrderDetailAction.ViewSale) },
                )
            }
        }
    }
}

/** Una línea: `cantidad × precio` y su total; debajo, lo que reserva y lo que falta. */
@Composable
private fun LineRow(line: OrderLineUi) {
    val unit = unitLabelOf(line.unit)
    val spacing = AppTheme.spacing
    Column {
        ProductRow(
            data = ProductRowData(
                name = line.name,
                stock = null,
                trailing = ProductRowTrailing.SaleLine(
                    line.total,
                    line.quantity,
                    line.unitPrice,
                    unit,
                ),
            ),
            divider = line.reserved == QuantityMilli.ZERO,
        )
        if (line.reserved > QuantityMilli.ZERO) {
            Column(
                Modifier.padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s8),
                verticalArrangement = Arrangement.spacedBy(spacing.s4),
            ) {
                Text(
                    stringResource(R.string.order_reserved, quantityText(line.reserved, unit)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (line.short > QuantityMilli.ZERO) {
                    StatusTag(
                        stringResource(R.string.order_stock_short, quantityText(line.short, unit)),
                        tone = StatusTone.WARNING,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = spacing.s16),
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Productos y envío; sin entregar, también el anticipo y lo que queda por cobrar. */
@Composable
private fun Summary(order: OrderDetail) {
    Column(
        Modifier.padding(horizontal = AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        SummaryRow(stringResource(R.string.order_summary_products), order.products)
        if (order.shipping > Money.ZERO) {
            SummaryRow(stringResource(R.string.order_summary_shipping), order.shipping)
        }
        if (order.status.isOpen) {
            if (order.deposit > Money.ZERO) {
                SummaryRow(stringResource(R.string.order_summary_deposit), -order.deposit)
            }
            SummaryRow(stringResource(R.string.order_summary_due), order.due)
        }
    }
}

@Composable
private fun SummaryRow(label: String, amount: Money) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        MoneyText(amount, MoneySize.ROW, signed = amount < Money.ZERO)
    }
}

/**
 * `Registrar anticipo`, solo sin entregar. Sin cliente no se puede: un anticipo es un pago del
 * cliente, y lo dice en su lugar.
 */
@Composable
private fun DepositEntry(order: OrderDetail, onAction: (OrderDetailAction) -> Unit) {
    if (!order.status.isOpen) return
    Box(Modifier.padding(horizontal = AppTheme.spacing.s16)) {
        if (order.customerId != null) {
            InlineTextButton(
                text = stringResource(R.string.order_deposit_action),
                onClick = { onAction(OrderDetailAction.OpenDeposit) },
            )
        } else {
            Text(
                stringResource(R.string.order_deposit_needs_customer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** El paso del avance: pendiente, listo o entregado. */
private fun stepOf(status: OrderStatus): Int = when (status) {
    OrderStatus.PENDING, OrderStatus.CANCELLED -> 0
    OrderStatus.READY -> 1
    OrderStatus.DELIVERED -> 2
}

/** La barra fija de abajo, según el estado. Los mensajes se apoyan encima. */
@Composable
private fun ActionBar(
    status: OrderStatus,
    working: Boolean,
    onAction: (OrderDetailAction) -> Unit,
) {
    val (label, action) = when (status) {
        OrderStatus.PENDING -> R.string.order_mark_ready_action to OrderDetailAction.MarkReady
        OrderStatus.READY -> R.string.order_deliver_action to OrderDetailAction.Deliver
        OrderStatus.DELIVERED, OrderStatus.CANCELLED -> return
    }
    Surface(
        Modifier.fillMaxWidth().keepsMessagesAbove(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            HorizontalDivider(
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            PrimaryActionButton(
                text = stringResource(label),
                onClick = { onAction(action) },
                variant = ActionVariant.PRIMARY,
                state = if (working) ActionState.WORKING else ActionState.IDLE,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(AppTheme.spacing.s16),
            )
        }
    }
}

@Composable
private fun Dialogs(uiState: OrderDetailUiState, onAction: (OrderDetailAction) -> Unit) {
    when (val dialog = uiState.dialog) {
        null -> Unit

        is OrderDetailDialog.ConfirmCancel -> ConfirmDialog(
            title = stringResource(R.string.order_cancel_confirm_title),
            body = dialog.creditOwner?.let {
                stringResource(R.string.order_cancel_confirm_body_deposit, it)
            } ?: stringResource(R.string.order_cancel_confirm_body),
            confirmLabel = stringResource(R.string.order_cancel_action),
            onConfirm = { onAction(OrderDetailAction.ConfirmCancel) },
            onDismiss = { onAction(OrderDetailAction.DismissDialog) },
            variant = ConfirmVariant.DESTRUCTIVE,
        )
    }
    uiState.deposit?.let { DepositSheetView(uiState, it, onAction) }
}

/**
 * La hoja de `Registrar anticipo`: el valor y, con las cuentas de dinero en uso, el método y la
 * cuenta, como en un abono. Un error se dice en la hoja y lo escrito se conserva.
 */
@Composable
private fun DepositSheetView(
    uiState: OrderDetailUiState,
    sheet: DepositSheet,
    onAction: (OrderDetailAction) -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = { onAction(OrderDetailAction.DismissDeposit) },
        title = stringResource(R.string.order_deposit_action),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.order_deposit_save_action),
                onClick = { onAction(OrderDetailAction.SaveDeposit) },
                variant = ActionVariant.PRIMARY,
                state = if (sheet.working) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            MoneyField(
                value = sheet.amountText,
                onValueChange = { onAction(OrderDetailAction.DepositAmount(it)) },
                label = stringResource(R.string.order_deposit_amount_label),
                error = when (sheet.error) {
                    null -> null
                    DepositError.AMOUNT_ZERO -> stringResource(R.string.order_error_deposit_amount)
                    DepositError.AMOUNT_TOO_HIGH -> stringResource(R.string.error_value_high)
                },
            )
            DepositChoices(uiState, sheet, onAction)
            if (sheet.failed) {
                NoticeCard(
                    text = stringResource(R.string.state_error_save),
                    variant = NoticeVariant.ERROR,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(OrderDetailAction.SaveDeposit) },
                )
            }
        }
    }
}

/** Método y cuenta, solo con las cuentas de dinero en uso. */
@Composable
private fun DepositChoices(
    uiState: OrderDetailUiState,
    sheet: DepositSheet,
    onAction: (OrderDetailAction) -> Unit,
) {
    val choices = uiState.choices
    if (!choices.accountsInUse) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (choices.methods.isNotEmpty()) {
        Text(
            stringResource(R.string.order_deposit_method_label),
            style = MaterialTheme.typography.labelLarge,
            color = muted,
        )
        CompactChipRow(
            options = choices.methods.map { it.id },
            label = { id -> paymentMethodName(choices.methods.first { it.id == id }) },
            selected = setOfNotNull(sheet.methodId),
            onToggle = { onAction(OrderDetailAction.DepositMethod(it)) },
            multiple = false,
        )
    }
    val method = choices.methods.firstOrNull { it.id == sheet.methodId }
    // La cuenta se pregunta solo si el método no trae una: con una, el dinero va ahí.
    if (method?.details?.moneyAccountId != null || choices.accounts.isEmpty()) return
    Text(
        stringResource(R.string.order_deposit_account_label),
        style = MaterialTheme.typography.labelLarge,
        color = muted,
    )
    val resources = LocalResources.current
    CompactChipRow(
        options = choices.accounts.map { it.id },
        label = { id ->
            val account = choices.accounts.first { it.id == id }
            SeedText.displayName(
                resources,
                SeedTable.MONEY_ACCOUNTS,
                account.systemKey,
                account.details.name,
            )
        },
        selected = setOfNotNull(sheet.accountId),
        // Tocar la elegida la quita: el anticipo queda solo en el libro del cliente.
        onToggle = { id ->
            onAction(OrderDetailAction.DepositAccount(id.takeUnless { it == sheet.accountId }))
        },
        multiple = false,
    )
}

/** Tres bloques de esqueleto con la forma del detalle. */
@Composable
private fun DetailSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
        }
    }
}

private const val SKELETON_ROWS = 3
