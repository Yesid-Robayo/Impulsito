package io.github.yesidrobayo.shopledger.ui.returns

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.BalanceBadge
import io.github.yesidrobayo.shopledger.ui.components.BalanceBadgeVariant
import io.github.yesidrobayo.shopledger.ui.components.CheckboxRow
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.CustomerPicker
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.QuantityStepper
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.money.PayingAccountChips
import io.github.yesidrobayo.shopledger.ui.sale.unitLabelOf
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La devolución de una venta: qué se devuelve de cada línea, cómo se resuelve y, abajo, siempre a la
 * vista, cuántos productos y el total con `Registrar devolución`.
 *
 * - Cada línea dice lo vendido y lo ya devuelto, y su selector llega hasta lo que falta. Al subir de
 *   cero aparece `Devolver al inventario`, marcado: desmarcado, la mercancía cuenta como perdida.
 * - `Cómo se resuelve` ofrece dinero, saldo a favor o cambio. Con dinero y cuentas en uso, de qué
 *   cuenta sale, con la de la venta elegida. Con saldo a favor, el cliente y el saldo que quedará.
 *   Con cambio, el botón continúa a la venta nueva sin escribir nada.
 * - El modo discreto no cambia nada: aquí no hay costo, ganancia ni margen, y el total a devolver lo
 *   ve quien compra.
 * - En una ventana ancha, las líneas a la izquierda y la resolución con el total a la derecha.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun SaleReturnScreen(
    uiState: SaleReturnUiState,
    onAction: (SaleReturnAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.sale_return_title),
                navigation = TopBarNavigation.CLOSE,
                onNavigate = { onAction(SaleReturnAction.Close) },
                navigationLabel = stringResource(R.string.a11y_close),
                titleStyle = MaterialTheme.typography.titleLarge,
            )
            uiState.saleDate?.let { date ->
                val formats = LocalAppFormats.current
                val number = formats.quantity.format(
                    QuantityMilli.ofUnits(uiState.saleId),
                    allowsFraction = false,
                )
                Text(
                    text = stringResource(
                        R.string.sale_return_subtitle,
                        number,
                        formats.date.dayMonth(date),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = AppTheme.spacing.s16,
                        end = AppTheme.spacing.s16,
                        bottom = AppTheme.spacing.s8,
                    ),
                )
            }
            when {
                uiState.loadFailed -> NoticeCard(
                    text = stringResource(R.string.state_error_read),
                    variant = NoticeVariant.ERROR,
                    modifier = Modifier.padding(AppTheme.spacing.s16),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(SaleReturnAction.Retry) },
                )

                uiState.loading -> LoadingLines()

                uiState.nothingLeft -> NothingLeft(onAction)

                else -> ReturnForm(uiState, onAction)
            }
        }
    }
    uiState.sheet?.let { QuantitySheet(it, onAction) }
    if (uiState.discardAsked) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_discard_title),
            body = stringResource(R.string.dialog_discard_body),
            confirmLabel = stringResource(R.string.dialog_discard_action),
            onConfirm = { onAction(SaleReturnAction.ConfirmLeave) },
            onDismiss = { onAction(SaleReturnAction.DismissDialog) },
            dismissLabel = stringResource(R.string.dialog_discard_exit),
        )
    }
}

/** Tres filas de esqueleto, solo si la lectura tarda más de lo que se nota. */
@Composable
private fun LoadingLines() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

/** Todo ya se devolvió, o la venta ya no está activa: no hay qué elegir. */
@Composable
private fun NothingLeft(onAction: (SaleReturnAction) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            icon = R.drawable.ic_inventory_2,
            title = stringResource(R.string.sale_return_empty_title),
        ) {
            PrimaryActionButton(
                text = stringResource(R.string.action_back),
                onClick = { onAction(SaleReturnAction.Close) },
                variant = ActionVariant.TONAL,
            )
        }
    }
}

@Composable
private fun ColumnScope.ReturnForm(
    uiState: SaleReturnUiState,
    onAction: (SaleReturnAction) -> Unit,
) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        if (maxWidth >= MEDIUM_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                ReturnLines(uiState, onAction, Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier
                        .widthIn(max = AppTheme.sizes.sideSheet)
                        .fillMaxHeight()
                        .padding(AppTheme.spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
                ) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Resolution(uiState, onAction)
                    }
                    Column(Modifier.keepsMessagesAbove().imePadding().navigationBarsPadding()) {
                        Footer(uiState, onAction)
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().align(Alignment.TopCenter).widthIn(max = FORM_WIDTH),
            ) {
                ReturnLines(uiState, onAction, Modifier.weight(1f), withResolution = true)
                Column(Modifier.keepsMessagesAbove().imePadding().navigationBarsPadding()) {
                    HorizontalDivider(
                        thickness = AppTheme.sizes.hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Column(Modifier.padding(AppTheme.spacing.s16)) { Footer(uiState, onAction) }
                }
            }
        }
    }
}

/** Los avisos de la venta y sus líneas, en una lista perezosa que se desplaza. */
@Composable
private fun ReturnLines(
    uiState: SaleReturnUiState,
    onAction: (SaleReturnAction) -> Unit,
    modifier: Modifier,
    withResolution: Boolean = false,
) {
    val spacing = AppTheme.spacing
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = spacing.s16)) {
        item(key = NOTICES) { SaleNotices(uiState) }
        item(key = WHAT) { SectionHeader(stringResource(R.string.sale_return_what_title)) }
        if (uiState.noneSelected) {
            item(key = NONE_SELECTED) {
                NoticeCard(
                    text = stringResource(R.string.sale_return_error_none_selected),
                    variant = NoticeVariant.WARNING,
                    modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s4),
                )
            }
        }
        items(uiState.lines, key = { it.lineId }) { line -> LineRow(line, onAction) }
        if (withResolution) {
            item(key = RESOLUTION) {
                Column(Modifier.padding(horizontal = spacing.s16)) { Resolution(uiState, onAction) }
            }
        }
    }
}

/** Los avisos de la venta: de otro mes, cuenta hoy; con puntos, no cambian. */
@Composable
private fun SaleNotices(uiState: SaleReturnUiState) {
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(horizontal = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        uiState.countsOn?.let { date ->
            NoticeCard(
                text = stringResource(
                    R.string.sale_return_other_month_notice,
                    LocalAppFormats.current.date.longDate(date),
                ),
                variant = NoticeVariant.INFO,
            )
        }
        if (uiState.withPoints) {
            NoticeCard(
                text = stringResource(R.string.sale_return_points_notice),
                variant = NoticeVariant.INFO,
            )
        }
    }
}

/**
 * Una línea vendida: su nombre, lo vendido y lo ya devuelto, y el selector de lo que se devuelve.
 * Con algo elegido, si vuelve al inventario.
 */
@Composable
private fun LineRow(line: SaleReturnLineUi, onAction: (SaleReturnAction) -> Unit) {
    val spacing = AppTheme.spacing
    val unit = unitLabelOf(line.unit)
    val sold = quantityText(line.sold, unit)
    val returned = quantityText(line.returnedBefore, unit)
    val spoken = stringResource(
        R.string.a11y_sale_return_line,
        line.name,
        sold,
        returned,
        quantityText(line.quantity, unit),
    )
    // Con letra grande, el selector baja bajo el nombre: al lado, el nombre quedaría en una palabra
    // por renglón.
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    val texts: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier.semantics { contentDescription = spoken },
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            Text(line.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (line.returnedBefore > QuantityMilli.ZERO) {
                    stringResource(R.string.sale_return_sold_and_returned, sold, returned)
                } else {
                    stringResource(R.string.sale_return_sold_only, sold)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (line.archived) StatusTag(stringResource(R.string.status_archived))
        }
    }
    val stepper: @Composable () -> Unit = {
        if (line.returnable && line.remaining > QuantityMilli.ZERO) {
            QuantityStepper(
                quantity = line.quantity,
                unit = unit,
                productName = line.name,
                onDecrement = { onAction(SaleReturnAction.Decrement(line.lineId)) },
                onIncrement = { onAction(SaleReturnAction.Increment(line.lineId)) },
                canIncrement = line.canIncrement,
                onValueClick = { onAction(SaleReturnAction.EditQuantity(line.lineId)) },
            )
        }
    }
    val rowPadding = Modifier
        .fillMaxWidth()
        .heightIn(min = AppTheme.sizes.listRow)
        .padding(horizontal = spacing.s16, vertical = spacing.s8)
    Column(Modifier.fillMaxWidth()) {
        if (stacked) {
            Column(
                rowPadding,
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
                horizontalAlignment = Alignment.End,
            ) {
                texts(Modifier.fillMaxWidth())
                stepper()
            }
        } else {
            Row(
                rowPadding,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                texts(Modifier.weight(1f))
                stepper()
            }
        }
        if (line.quantity > QuantityMilli.ZERO) {
            Column(Modifier.padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s8)) {
                CheckboxRow(
                    text = stringResource(R.string.sale_return_restock),
                    checked = line.restock,
                    onCheckedChange = {
                        onAction(SaleReturnAction.RestockChange(line.lineId, it))
                    },
                )
                if (!line.restock) {
                    Text(
                        text = stringResource(R.string.sale_return_no_restock_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
 * `Cómo se resuelve`: dinero, con la cuenta de la que sale si se llevan cuentas; saldo a favor, con
 * el cliente y lo que quedará; o cambio por otro. Y la nota.
 */
@Composable
private fun Resolution(uiState: SaleReturnUiState, onAction: (SaleReturnAction) -> Unit) {
    val spacing = AppTheme.spacing
    val refund = stringResource(R.string.sale_return_resolution_refund)
    val credit = stringResource(R.string.sale_return_resolution_store_credit)
    val exchange = stringResource(R.string.sale_return_resolution_exchange)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        Text(
            stringResource(R.string.sale_return_how_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = spacing.s24),
        )
        CompactChipRow(
            options = ReturnResolution.entries,
            label = {
                when (it) {
                    ReturnResolution.REFUND -> refund
                    ReturnResolution.STORE_CREDIT -> credit
                    ReturnResolution.EXCHANGE -> exchange
                }
            },
            selected = setOf(uiState.resolution),
            onToggle = { onAction(SaleReturnAction.ResolutionChange(it)) },
            multiple = false,
        )
        when (uiState.resolution) {
            ReturnResolution.REFUND -> if (uiState.accounts.isNotEmpty()) {
                PayingAccountChips(
                    uiState.accounts,
                    uiState.accountId,
                    label = R.string.sale_return_refund_from,
                ) { onAction(SaleReturnAction.AccountChange(it)) }
                if (uiState.accountId == null) {
                    Text(
                        text = stringResource(R.string.sale_return_no_account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ReturnResolution.STORE_CREDIT -> StoreCredit(uiState, onAction)

            // El cambio se arma en la venta nueva.
            ReturnResolution.EXCHANGE -> Unit
        }
        AppTextField(
            value = uiState.note,
            onValueChange = { onAction(SaleReturnAction.NoteChange(it)) },
            label = stringResource(R.string.sale_return_note_label),
            supportingText = stringResource(R.string.sale_return_note_hint),
            error = pluralStringResource(
                R.plurals.error_text_long,
                MAX_NOTE_LENGTH,
                MAX_NOTE_LENGTH,
            ).takeIf { uiState.noteTooLong },
            singleLine = false,
        )
    }
}

/**
 * El cliente del saldo a favor, con el saldo que quedará. El de la venta no se cambia; sin cliente
 * se elige uno, porque un saldo a favor siempre es de alguien.
 */
@Composable
private fun StoreCredit(uiState: SaleReturnUiState, onAction: (SaleReturnAction) -> Unit) {
    val customer = uiState.customer
    val money = LocalAppFormats.current.money
    CustomerPicker(
        label = stringResource(R.string.checkout_customer),
        name = customer?.name,
        balance = customer?.balance ?: Money.ZERO,
        onOpen = { if (!uiState.customerLocked) onAction(SaleReturnAction.PickCustomer) },
        onRemove = { if (!uiState.customerLocked) onAction(SaleReturnAction.RemoveCustomer) },
        error = stringResource(R.string.sale_return_error_no_customer)
            .takeIf { uiState.needsCustomer },
    )
    if (customer == null) return
    val after = customer.balanceAfter
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        Text(
            text = stringResource(
                R.string.sale_return_balance_after,
                rememberMoneyDisplay(after, money).text.text,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        BalanceBadge(BalanceBadgeVariant.forCustomer(after), after)
    }
    if (uiState.creditSale && customer.name != null) {
        Text(
            text = stringResource(R.string.sale_return_credit_debt_help, customer.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * El aviso, `1 producto` con el total a devolver y el botón, siempre a la vista. Si la venta lleva
 * impuesto, tocar el resumen despliega la base y el impuesto.
 */
@Composable
private fun Footer(uiState: SaleReturnUiState, onAction: (SaleReturnAction) -> Unit) {
    val spacing = AppTheme.spacing
    val money = LocalAppFormats.current.money
    val count =
        pluralStringResource(R.plurals.sale_return_summary, uiState.itemCount, uiState.itemCount)
    val spoken = stringResource(
        R.string.a11y_sale_return_total,
        rememberMoneyDisplay(uiState.total, money).description,
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        uiState.notice?.let { ReturnNotice(it, onAction) }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .then(
                    if (uiState.showsTax) {
                        Modifier.clickable(role = Role.Button) {
                            onAction(SaleReturnAction.ToggleTax)
                        }
                    } else {
                        Modifier
                    },
                )
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            Column(Modifier.weight(1f)) {
                Text(count, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.sale_return_total_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Nunca se oculta: es lo que recibe quien compra, y lo está viendo.
            MoneyText(
                uiState.total,
                MoneySize.TITLE,
                Modifier.clearAndSetSemantics { contentDescription = spoken },
            )
        }
        if (uiState.showsTax && uiState.taxExpanded) {
            BreakdownRow(stringResource(R.string.sale_return_net_label), uiState.net)
            BreakdownRow(stringResource(R.string.sale_return_tax_label), uiState.tax)
        }
        PrimaryActionButton(
            text = stringResource(
                if (uiState.resolution == ReturnResolution.EXCHANGE) {
                    R.string.sale_return_continue_exchange_action
                } else {
                    R.string.sale_return_register_action
                },
            ),
            onClick = { onAction(SaleReturnAction.Register) },
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            state = if (uiState.registering) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

@Composable
private fun BreakdownRow(label: String, amount: Money) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        MoneyText(amount, MoneySize.CAPTION)
    }
}

@Composable
private fun ReturnNotice(notice: SaleReturnNotice, onAction: (SaleReturnAction) -> Unit) {
    when (notice) {
        is SaleReturnNotice.RegisterFailed -> NoticeCard(
            text = stringResource(
                if (notice.outOfSpace) {
                    R.string.error_device_storage
                } else {
                    R.string.sale_return_error_register
                },
            ),
            variant = NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(SaleReturnAction.Retry) },
        )

        SaleReturnNotice.AccountUnavailable -> NoticeCard(
            text = stringResource(R.string.document_account_unavailable),
            variant = NoticeVariant.WARNING,
        )
    }
}

/** La hoja para escribir la cantidad de una línea cuya unidad admite fracciones. */
@Composable
private fun QuantitySheet(sheet: ReturnQuantitySheet, onAction: (SaleReturnAction) -> Unit) {
    val unit = unitLabelOf(sheet.unit)
    AppBottomSheet(
        onDismissRequest = { onAction(SaleReturnAction.CloseSheet) },
        title = sheet.name,
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.action_save),
                onClick = { onAction(SaleReturnAction.QuantityConfirm) },
                variant = ActionVariant.PRIMARY,
            )
        },
    ) {
        QuantityField(
            value = sheet.text,
            onValueChange = { onAction(SaleReturnAction.QuantityTextChange(it)) },
            label = stringResource(R.string.sale_line_quantity),
            unit = unit,
            modifier = Modifier.fillMaxWidth(),
            error = when (val error = sheet.error) {
                null -> null

                is ReturnQuantityError.AboveRemaining ->
                    stringResource(
                        R.string.sale_return_error_max,
                        quantityText(error.remaining, unit),
                    )

                ReturnQuantityError.NotANumber -> stringResource(R.string.field_error_number)
            },
        )
    }
}

private const val NOTICES = "notices"
private const val WHAT = "what"
private const val NONE_SELECTED = "none"
private const val RESOLUTION = "resolution"
private const val SKELETON_ROWS = 3

/** Desde esta escala de letra, el selector de cantidad va bajo el nombre de la línea. */
private const val STACKED_FONT_SCALE = 1.5f

/** Largo máximo de la nota de una devolución. */
private const val MAX_NOTE_LENGTH = 200

/** Desde esta anchura, las líneas a la izquierda y la resolución con el total a la derecha. */
private val MEDIUM_WIDTH = 600.dp

/** Ancho máximo del formulario en un teléfono grande. */
private val FORM_WIDTH = 600.dp
