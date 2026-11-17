package io.github.yesidrobayo.shopledger.ui.supplierreturn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.BalanceBadge
import io.github.yesidrobayo.shopledger.ui.components.BalanceBadgeVariant
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PickerField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.money.PayingAccountChips
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La devolución a proveedor: proveedor y compra de origen arriba, las líneas con cuánto se devuelve,
 * cómo se resuelve y, abajo, el total con `Registrar devolución`.
 *
 * Con compra, las líneas salen de ella: la persona escribe cuánto devuelve de cada una, y el costo
 * unitario es el de esa línea, de solo lectura. Sin compra, se agregan productos, que salen al costo
 * de salida. Bajo cada línea se ve cómo queda el costo promedio, y si el stock quedaría negativo,
 * sin bloquear. En una ventana ancha, las líneas van a la izquierda y la resolución con el total a
 * la derecha.
 *
 * El costo unitario, el efecto en el costo, el saldo con el proveedor y el total van con modo
 * discreto. Las cantidades no, porque se están llenando.
 */
@Composable
fun SupplierReturnScreen(
    uiState: SupplierReturnUiState,
    onAction: (SupplierReturnAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.supplier_return_title),
                navigation = TopBarNavigation.CLOSE,
                onNavigate = { onAction(SupplierReturnAction.Close) },
                navigationLabel = stringResource(R.string.a11y_close),
                titleStyle = MaterialTheme.typography.titleLarge,
                actions = { ScreenHelpLink(HelpTopic.PURCHASES) },
            )
            when {
                uiState.loadFailed -> NoticeCard(
                    text = stringResource(R.string.state_error_read),
                    variant = NoticeVariant.ERROR,
                    modifier = Modifier.padding(AppTheme.spacing.s16),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(SupplierReturnAction.Retry) },
                )

                // Con una compra se esperan sus líneas; en blanco es un formulario local.
                uiState.loading -> if (uiState.withPurchase) LoadingLines()

                else -> ReturnForm(uiState, onAction)
            }
        }
    }
    SupplierReturnSheets(uiState, onAction)
    if (uiState.discardAsked) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_discard_title),
            body = stringResource(R.string.dialog_discard_body),
            confirmLabel = stringResource(R.string.dialog_discard_action),
            onConfirm = { onAction(SupplierReturnAction.ConfirmLeave) },
            onDismiss = { onAction(SupplierReturnAction.DismissDialog) },
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
                SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.listRow))
            }
        }
    }
}

@Composable
private fun ColumnScope.ReturnForm(
    uiState: SupplierReturnUiState,
    onAction: (SupplierReturnAction) -> Unit,
) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        // El ancho del bloque, leído aquí: dentro de una fila o columna no se alcanza.
        val boxWidth = maxWidth
        if (maxWidth >= MEDIUM_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                ReturnLines(uiState, onAction, Modifier.weight(1f).fillMaxHeight())
                // A lo sumo la mitad: en una ventana mediana, las líneas no quedan angostas.
                Column(
                    Modifier
                        .width(minOf(AppTheme.sizes.sideSheet, boxWidth / 2))
                        .fillMaxHeight()
                        .padding(AppTheme.spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
                ) {
                    // Se desplaza: en una ventana baja, la nota y las cuentas no se cortan.
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

/** Proveedor, compra de origen y las líneas, en una sola lista que se desplaza. */
@Composable
private fun ReturnLines(
    uiState: SupplierReturnUiState,
    onAction: (SupplierReturnAction) -> Unit,
    modifier: Modifier,
    withResolution: Boolean = false,
) {
    val spacing = AppTheme.spacing
    LazyColumn(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
        contentPadding = PaddingValues(spacing.s16),
    ) {
        item(key = HEADER) { Header(uiState, onAction) }
        if (uiState.lines.isEmpty() && !uiState.withPurchase) {
            item(key = EMPTY) {
                EmptyState(
                    icon = R.drawable.ic_inventory_2,
                    title = stringResource(R.string.supplier_return_empty_title),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.supplier_return_add_product_action),
                        onClick = { onAction(SupplierReturnAction.OpenProducts) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }
        } else {
            items(uiState.lines, key = { it.key }) { line -> LineCard(line, onAction) }
            if (!uiState.withPurchase) {
                item(key = ADD) {
                    InlineTextButton(
                        text = stringResource(R.string.supplier_return_add_product_action),
                        onClick = { onAction(SupplierReturnAction.OpenProducts) },
                    )
                }
            }
        }
        if (withResolution) item(key = RESOLUTION) { Resolution(uiState, onAction) }
    }
}

/** El proveedor y la compra de origen. Con compra, el proveedor es el suyo y no se cambia. */
@Composable
private fun Header(uiState: SupplierReturnUiState, onAction: (SupplierReturnAction) -> Unit) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        val supplierLabel = stringResource(R.string.supplier_return_supplier_label)
        PickerField(
            label = supplierLabel,
            value = uiState.supplierName ?: stringResource(R.string.purchase_no_supplier),
            onClick = { onAction(SupplierReturnAction.OpenSuppliers) },
            lockedReason = stringResource(R.string.supplier_return_supplier_from_origin)
                .takeIf { uiState.supplierLocked },
        )
        if (uiState.creditNeedsSupplier) {
            NoticeCard(
                text = stringResource(R.string.supplier_return_error_no_supplier),
                variant = NoticeVariant.WARNING,
                // Aparece al registrar sin proveedor: TalkBack lo dice sin mover el foco.
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
                actionLabel = supplierLabel,
                onAction = { onAction(SupplierReturnAction.OpenSuppliers) },
            )
        }
        val origin = uiState.origin
        PickerField(
            label = stringResource(R.string.supplier_return_origin_label),
            value = if (origin == null) {
                stringResource(R.string.supplier_return_origin_none)
            } else {
                val number = formats.quantity.format(
                    QuantityMilli.ofUnits(origin.purchaseId),
                    allowsFraction = false,
                )
                stringResource(
                    R.string.purchase_row_detail,
                    stringResource(R.string.purchase_detail_title, number),
                    formats.date.dayMonthYear(origin.date),
                )
            },
            onClick = { onAction(SupplierReturnAction.OpenPurchases) },
        )
    }
}

/**
 * Una línea: producto, cantidad, costo unitario y total de la línea, con el efecto en el costo
 * promedio y el aviso si el stock quedaría negativo.
 */
@Composable
private fun LineCard(line: SupplierReturnLineUi, onAction: (SupplierReturnAction) -> Unit) {
    val spacing = AppTheme.spacing
    val money = LocalAppFormats.current.money
    val unit = line.unit?.let { unitLabel(it) } ?: UnitLabel("", allowsFraction = false)
    // El costo se lee con el nombre de la moneda; con el modo discreto, TalkBack dice «Oculto».
    val discreet = LocalDiscreetMode.current
    val hidden = stringResource(R.string.a11y_discreet_hidden)
    val spoken = line.unitCost?.let { cost ->
        val costSpoken = rememberMoneyDisplay(cost, money).description
        stringResource(
            R.string.a11y_supplier_return_line,
            line.name,
            line.quantityText,
            if (discreet) hidden else costSpoken,
        )
    }
    val described = spoken?.let { Modifier.semantics { contentDescription = it } } ?: Modifier
    AppCard(described) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = line.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (line.removable) {
                InlineTextButton(
                    text = stringResource(R.string.action_remove),
                    onClick = { onAction(SupplierReturnAction.RemoveLine(line.key)) },
                )
            }
        }
        QuantityField(
            value = line.quantityText,
            onValueChange = { onAction(SupplierReturnAction.QuantityChange(line.key, it)) },
            label = stringResource(R.string.sale_line_quantity),
            unit = unit,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s8),
            error = line.error?.let { lineErrorText(it, unit) },
        )
        line.unitCost?.let {
            LabeledAmount(stringResource(R.string.supplier_return_unit_cost_label), it)
        }
        line.lineTotal?.let {
            LabeledAmount(stringResource(R.string.supplier_return_line_total_label), it)
        }
        val before = line.costBefore
        val after = line.costAfter
        if (before != null && after != null) {
            DiscreetValue(
                modifier = Modifier.padding(top = spacing.s4),
                style = MaterialTheme.typography.bodySmall,
            ) {
                val beforeDisplay = rememberMoneyDisplay(before, money)
                val afterDisplay = rememberMoneyDisplay(after, money)
                // TalkBack lee los costos con el nombre de la moneda, y avisa cuando cambian.
                val effectSpoken = stringResource(
                    R.string.supplier_return_cost_effect,
                    beforeDisplay.description,
                    afterDisplay.description,
                )
                Text(
                    text = stringResource(
                        R.string.supplier_return_cost_effect,
                        beforeDisplay.text.text,
                        afterDisplay.text.text,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        contentDescription = effectSpoken
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
        }
        line.leftAfter?.let { left ->
            NoticeCard(
                text = stringResource(
                    R.string.supplier_return_warning_negative,
                    quantityText(left, unit),
                ),
                variant = NoticeVariant.WARNING,
                modifier = Modifier.padding(top = spacing.s8),
            )
        }
    }
}

@Composable
private fun LabeledAmount(label: String, amount: Money) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = AppTheme.spacing.s8)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        MoneyText(amount, MoneySize.ROW, sensitive = true)
    }
}

/**
 * `Cómo se resuelve`: reembolso en dinero, con la cuenta a la que entra si se llevan cuentas, o
 * crédito con el proveedor, con lo que se le deberá después. Y la nota.
 */
@Composable
private fun Resolution(uiState: SupplierReturnUiState, onAction: (SupplierReturnAction) -> Unit) {
    val spacing = AppTheme.spacing
    val refund = stringResource(R.string.supplier_return_resolution_cash_refund)
    val credit = stringResource(R.string.supplier_return_resolution_supplier_credit)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        SectionHeader(stringResource(R.string.supplier_return_how_title))
        CompactChipRow(
            options = PurchaseReturnResolution.entries,
            label = {
                when (it) {
                    PurchaseReturnResolution.CASH_REFUND -> refund
                    PurchaseReturnResolution.SUPPLIER_CREDIT -> credit
                }
            },
            selected = setOf(uiState.resolution),
            onToggle = { onAction(SupplierReturnAction.ResolutionChange(it)) },
            multiple = false,
        )
        when (uiState.resolution) {
            PurchaseReturnResolution.CASH_REFUND ->
                PayingAccountChips(
                    uiState.accounts,
                    uiState.accountId,
                    label = R.string.supplier_return_refund_to,
                ) { onAction(SupplierReturnAction.AccountChange(it)) }

            PurchaseReturnResolution.SUPPLIER_CREDIT -> uiState.balanceAfter?.let { after ->
                BalanceAfter(after)
            }
        }
        AppTextField(
            value = uiState.note,
            onValueChange = { onAction(SupplierReturnAction.NoteChange(it)) },
            label = stringResource(R.string.supplier_return_note_label),
            supportingText = stringResource(R.string.supplier_return_note_hint),
            error = stringResource(R.string.error_value_high).takeIf { uiState.noteTooLong },
            singleLine = false,
        )
    }
}

/** Lo que se le deberá al proveedor después del crédito, con palabras y en modo discreto. */
@Composable
private fun BalanceAfter(after: Money) {
    val money = LocalAppFormats.current.money
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        DiscreetValue(
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
        ) {
            val display = rememberMoneyDisplay(after, money)
            // TalkBack lee el saldo con el nombre de la moneda, no con su símbolo.
            val spoken =
                stringResource(R.string.supplier_return_balance_after, display.description)
            Text(
                stringResource(R.string.supplier_return_balance_after, display.text.text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        }
        BalanceBadge(BalanceBadgeVariant.forSupplier(after), after)
    }
}

/** El aviso, el total y `Registrar devolución`, siempre a la vista. */
@Composable
private fun Footer(uiState: SupplierReturnUiState, onAction: (SupplierReturnAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        uiState.notice?.let { ReturnNotice(it, onAction) }
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.supplier_return_total_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            MoneyText(uiState.total, MoneySize.TITLE, sensitive = true)
        }
        PrimaryActionButton(
            text = stringResource(R.string.supplier_return_register_action),
            onClick = { onAction(SupplierReturnAction.Register) },
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            state = if (uiState.registering) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

@Composable
private fun ReturnNotice(notice: SupplierReturnNotice, onAction: (SupplierReturnAction) -> Unit) {
    when (notice) {
        is SupplierReturnNotice.RegisterFailed -> NoticeCard(
            text = stringResource(
                if (notice.outOfSpace) {
                    R.string.error_device_storage
                } else {
                    R.string.supplier_return_error_register
                },
            ),
            variant = NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(SupplierReturnAction.Retry) },
        )

        SupplierReturnNotice.CurrencyUnconfirmed -> NoticeCard(
            text = stringResource(R.string.region_required_body),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.region_required_action),
            onAction = { onAction(SupplierReturnAction.ConfirmCurrency) },
        )

        SupplierReturnNotice.AccountUnavailable -> NoticeCard(
            text = stringResource(R.string.document_account_unavailable),
            variant = NoticeVariant.WARNING,
        )

        SupplierReturnNotice.NothingToReturn -> NoticeCard(
            text = stringResource(R.string.supplier_return_empty_title),
            variant = NoticeVariant.WARNING,
        )
    }
}

@Composable
private fun SupplierReturnSheets(
    uiState: SupplierReturnUiState,
    onAction: (SupplierReturnAction) -> Unit,
) {
    when (val sheet = uiState.sheet) {
        null -> Unit

        is SupplierReturnSheet.Suppliers -> SelectionSheet(
            title = stringResource(R.string.supplier_return_supplier_label),
            items = sheet.options,
            key = { it.id },
            query = sheet.query,
            onQueryChange = { onAction(SupplierReturnAction.QueryChange(it)) },
            onDismissRequest = { onAction(SupplierReturnAction.CloseSheet) },
            searchHint = stringResource(R.string.supplier_search_hint),
            itemContent = { option ->
                SettingsRow(
                    title = option.name,
                    action = SettingsRowAction.Navigation {
                        onAction(SupplierReturnAction.SelectSupplier(option.id))
                    },
                )
            },
        )

        is SupplierReturnSheet.Products -> SelectionSheet(
            title = stringResource(R.string.supplier_return_add_product_action),
            items = sheet.options,
            key = { it.id },
            query = sheet.query,
            onQueryChange = { onAction(SupplierReturnAction.QueryChange(it)) },
            onDismissRequest = { onAction(SupplierReturnAction.CloseSheet) },
            searchHint = stringResource(R.string.sale_search_hint),
            itemContent = { option ->
                SettingsRow(
                    title = option.name,
                    action = SettingsRowAction.Navigation {
                        onAction(SupplierReturnAction.AddProduct(option.id))
                    },
                )
            },
            resultsCount = R.plurals.a11y_search_results,
        )

        is SupplierReturnSheet.Purchases -> PurchasesSheet(sheet, onAction)
    }
}

/** `Sin compra de origen` primero, y después las compras activas, de la más nueva a la más antigua. */
@Composable
private fun PurchasesSheet(
    sheet: SupplierReturnSheet.Purchases,
    onAction: (SupplierReturnAction) -> Unit,
) {
    val formats = LocalAppFormats.current
    AppBottomSheet(
        onDismissRequest = { onAction(SupplierReturnAction.CloseSheet) },
        title = stringResource(R.string.supplier_return_origin_label),
    ) {
        SettingsRow(
            title = stringResource(R.string.supplier_return_origin_none),
            action = SettingsRowAction.Navigation {
                onAction(SupplierReturnAction.SelectPurchase(null))
            },
        )
        for (option in sheet.options) {
            val number = formats.quantity.format(
                QuantityMilli.ofUnits(option.id),
                allowsFraction = false,
            )
            SettingsRow(
                title = stringResource(R.string.purchase_detail_title, number),
                summary = option.supplierName?.let { name ->
                    stringResource(
                        R.string.purchase_row_detail,
                        formats.date.dayMonthYear(option.date),
                        name,
                    )
                } ?: formats.date.dayMonthYear(option.date),
                action = SettingsRowAction.Navigation {
                    onAction(SupplierReturnAction.SelectPurchase(option.id))
                },
            )
        }
    }
}

@Composable
private fun lineErrorText(error: SupplierReturnLineError, unit: UnitLabel): String = when (error) {
    is SupplierReturnLineError.AboveRemaining ->
        stringResource(R.string.supplier_return_error_max, quantityText(error.remaining, unit))

    SupplierReturnLineError.NotANumber -> stringResource(R.string.field_error_number)

    SupplierReturnLineError.FractionNotAllowed -> stringResource(R.string.error_unit_decimals)

    SupplierReturnLineError.TooHigh -> stringResource(R.string.error_value_high)
}

private const val HEADER = "header"
private const val EMPTY = "empty"
private const val ADD = "add"
private const val RESOLUTION = "resolution"
private const val SKELETON_ROWS = 3

/** Desde esta anchura, las líneas a la izquierda y la resolución con el total a la derecha. */
private val MEDIUM_WIDTH = 600.dp

/** Ancho máximo del formulario en un teléfono grande. */
private val FORM_WIDTH = 600.dp
