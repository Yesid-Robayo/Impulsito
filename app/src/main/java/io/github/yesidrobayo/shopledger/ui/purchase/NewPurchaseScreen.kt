package io.github.yesidrobayo.shopledger.ui.purchase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AdaptiveFieldRow
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PickerField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La compra nueva (pantalla de nueva compra): proveedor, fecha y referencia arriba, las líneas
 * editables en el mismo formulario y, abajo, el total con `Registrar compra`.
 *
 * Cada línea es una tarjeta con cantidad y costo lado a lado, y la persona elige si escribe el costo
 * unitario o el total de la línea: el otro valor se muestra deducido, con el efecto en el costo
 * promedio. Una compra se copia de un documento línea por línea, así que todo lo escrito queda a la
 * vista, sin hojas que lo escondan. En una ventana ancha, las líneas van a la izquierda y el
 * resumen a la derecha.
 *
 * Los costos y el total van con modo discreto, salvo el campo que se está llenando.
 */
@Composable
fun NewPurchaseScreen(
    uiState: NewPurchaseUiState,
    onAction: (NewPurchaseAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.purchase_new_title),
                navigation = TopBarNavigation.CLOSE,
                onNavigate = {
                    onAction(
                        if (uiState.registered == null) {
                            NewPurchaseAction.Close
                        } else {
                            NewPurchaseAction.Finish
                        },
                    )
                },
                navigationLabel = stringResource(R.string.a11y_close),
                titleStyle = MaterialTheme.typography.titleLarge,
            )
            val registered = uiState.registered
            when {
                registered != null -> PurchaseSummaryContent(registered, onAction)

                uiState.loadFailed -> NoticeCard(
                    text = stringResource(R.string.state_error_read),
                    variant = NoticeVariant.ERROR,
                    modifier = Modifier.padding(AppTheme.spacing.s16),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(NewPurchaseAction.Retry) },
                )

                // Es un formulario local: mientras llega el catálogo no hay nada que mostrar.
                uiState.loading -> Unit

                else -> PurchaseForm(uiState, onAction)
            }
        }
    }
    PurchaseSheets(uiState, onAction)
    PurchaseDialogs(uiState, onAction)
}

@Composable
private fun ColumnScope.PurchaseForm(
    uiState: NewPurchaseUiState,
    onAction: (NewPurchaseAction) -> Unit,
) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        if (maxWidth >= EXPANDED_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    PurchaseLines(uiState, onAction, Modifier.weight(1f), withHeader = true)
                }
                Column(
                    Modifier
                        .widthIn(max = AppTheme.sizes.sideSheet)
                        .fillMaxHeight()
                        .padding(AppTheme.spacing.s16),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Column(Modifier.keepsMessagesAbove()) { PurchaseFooter(uiState, onAction) }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .widthIn(max = FORM_WIDTH),
            ) {
                PurchaseLines(uiState, onAction, Modifier.weight(1f), withHeader = true)
                Column(Modifier.keepsMessagesAbove().imePadding().navigationBarsPadding()) {
                    HorizontalDivider(
                        thickness = AppTheme.sizes.hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Column(Modifier.padding(AppTheme.spacing.s16)) {
                        PurchaseFooter(uiState, onAction)
                    }
                }
            }
        }
    }
}

/** El encabezado, las líneas y `Agregar producto`, en una sola lista que se desplaza. */
@Composable
private fun PurchaseLines(
    uiState: NewPurchaseUiState,
    onAction: (NewPurchaseAction) -> Unit,
    modifier: Modifier,
    withHeader: Boolean,
) {
    val spacing = AppTheme.spacing
    LazyColumn(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.s16),
    ) {
        if (withHeader) item(key = "header") { PurchaseHeader(uiState, onAction) }
        if (uiState.lines.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = R.drawable.ic_inventory_2,
                    title = stringResource(R.string.empty_purchase_title),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.empty_purchase_action),
                        onClick = { onAction(NewPurchaseAction.OpenProducts) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }
        } else {
            items(uiState.lines, key = { it.key }) { line -> PurchaseLineCard(line, onAction) }
            item(key = "add") {
                InlineTextButton(
                    text = stringResource(R.string.empty_purchase_action),
                    onClick = { onAction(NewPurchaseAction.OpenProducts) },
                )
            }
        }
    }
}

/** Proveedor, fecha y referencia del documento. */
@Composable
private fun PurchaseHeader(uiState: NewPurchaseUiState, onAction: (NewPurchaseAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        PickerField(
            label = stringResource(R.string.purchase_supplier),
            value = uiState.supplierName ?: stringResource(R.string.purchase_no_supplier),
            onClick = { onAction(NewPurchaseAction.OpenSuppliers) },
        )
        val today = uiState.today
        val weekStart = uiState.weekStartDay
        if (today != null && weekStart != null) {
            DateField(
                label = stringResource(R.string.form_date),
                date = uiState.date ?: today,
                onDateChange = { onAction(NewPurchaseAction.DateChange(it)) },
                today = today,
                weekStartDay = weekStart,
            )
        }
        AppTextField(
            value = uiState.reference,
            onValueChange = { onAction(NewPurchaseAction.ReferenceChange(it)) },
            label = stringResource(R.string.purchase_invoice_ref),
            error = stringResource(R.string.error_value_high).takeIf { uiState.referenceTooLong },
        )
    }
}

/** Una línea: producto, cantidad y costo, con lo que se deduce y el efecto en el costo. */
@Composable
private fun PurchaseLineCard(line: PurchaseLineUi, onAction: (NewPurchaseAction) -> Unit) {
    val spacing = AppTheme.spacing
    val unit = line.unit?.let { unitLabel(it) } ?: UnitLabel("", allowsFraction = false)
    val costLabel = stringResource(R.string.form_unit_cost)
    val totalLabel = stringResource(R.string.sale_line_total)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = line.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            InlineTextButton(
                text = stringResource(R.string.action_remove),
                onClick = { onAction(NewPurchaseAction.RemoveLine(line.key)) },
            )
        }
        if (line.archived) {
            NoticeCard(
                text = stringResource(R.string.notice_product_archived, line.name),
                variant = NoticeVariant.WARNING,
            )
        }
        if (line.units.size > 1) {
            val baseId = line.units.first().id
            CompactChipRow(
                options = line.units,
                label = { unitLabel(it).symbol },
                selected = setOfNotNull(line.unit),
                onToggle = { chosen ->
                    onAction(
                        NewPurchaseAction.UnitChange(line.key, chosen.id.takeIf { it != baseId }),
                    )
                },
                multiple = false,
                modifier = Modifier.padding(top = spacing.s8),
            )
        }
        CompactChipRow(
            options = listOf(false, true),
            label = { if (it) totalLabel else costLabel },
            selected = setOf(line.byTotal),
            onToggle = { onAction(NewPurchaseAction.AmountModeChange(line.key, it)) },
            multiple = false,
            modifier = Modifier.padding(top = spacing.s8),
        )
        val amountLabel = if (line.byTotal) totalLabel else costLabel
        AdaptiveFieldRow(
            firstLabel = stringResource(R.string.sale_line_quantity),
            secondLabel = amountLabel,
            modifier = Modifier.padding(top = spacing.s8),
            first = {
                QuantityField(
                    value = line.quantityText,
                    onValueChange = { text ->
                        onAction(NewPurchaseAction.QuantityChange(line.key, text))
                    },
                    label = stringResource(R.string.sale_line_quantity),
                    unit = unit,
                    modifier = it,
                    error = line.quantityError?.let { error -> fieldErrorText(error, line.name) },
                )
            },
            second = {
                MoneyField(
                    value = line.amountText,
                    onValueChange = { text ->
                        onAction(NewPurchaseAction.AmountChange(line.key, text))
                    },
                    label = amountLabel,
                    modifier = it,
                    error = line.amountError?.let { error -> fieldErrorText(error, line.name) },
                )
            },
        )
        line.derived?.let { derived ->
            LabeledAmount(if (line.byTotal) costLabel else totalLabel, derived)
        }
        val before = line.costBefore
        val after = line.costAfter
        if (before != null && after != null && before != after) {
            val money = LocalAppFormats.current.money
            Text(
                text = stringResource(
                    R.string.notice_purchase_cost_change,
                    line.name,
                    rememberMoneyDisplay(before, money).text.text,
                    rememberMoneyDisplay(after, money).text.text,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s4),
            )
        }
        line.costDiffersFrom?.let { previous ->
            NoticeCard(
                text = stringResource(
                    R.string.notice_cost_differs,
                    rememberMoneyDisplay(previous, LocalAppFormats.current.money).text.text,
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
        Modifier.fillMaxWidth().padding(top = AppTheme.spacing.s8),
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

/** El aviso, el total y `Registrar compra`, siempre a la vista. */
@Composable
private fun PurchaseFooter(uiState: NewPurchaseUiState, onAction: (NewPurchaseAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        uiState.notice?.let { PurchaseNoticeCard(it, onAction) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.purchase_total_bought),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            MoneyText(uiState.total, MoneySize.TITLE, sensitive = true)
        }
        PrimaryActionButton(
            text = stringResource(R.string.purchase_register),
            onClick = { onAction(NewPurchaseAction.Register) },
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            state = if (uiState.registering) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

@Composable
private fun PurchaseNoticeCard(notice: PurchaseNotice, onAction: (NewPurchaseAction) -> Unit) {
    when (notice) {
        is PurchaseNotice.RegisterFailed -> NoticeCard(
            text = stringResource(registerFailedText(notice.outOfSpace)),
            variant = NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(NewPurchaseAction.Retry) },
        )

        PurchaseNotice.CurrencyUnconfirmed -> NoticeCard(
            text = stringResource(R.string.region_required_body),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.region_required_action),
            onAction = { onAction(NewPurchaseAction.ConfirmCurrency) },
        )

        PurchaseNotice.TotalTooLarge, PurchaseNotice.TooManyLines -> NoticeCard(
            text = stringResource(R.string.error_value_high),
            variant = NoticeVariant.ERROR,
        )

        PurchaseNotice.SupplierUnavailable -> NoticeCard(
            text = stringResource(R.string.purchase_supplier_unavailable),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.purchase_supplier),
            onAction = { onAction(NewPurchaseAction.OpenSuppliers) },
        )
    }
}

/** Tras registrar: el total y cómo quedó el costo de cada producto. */
@Composable
private fun ColumnScope.PurchaseSummaryContent(
    summary: PurchaseSummary,
    onAction: (NewPurchaseAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val money = LocalAppFormats.current.money
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().widthIn(max = FORM_WIDTH),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.s16),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(
                    R.string.confirm_purchase_registered,
                    rememberMoneyDisplay(summary.total, money).text.text,
                ),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        items(summary.costs, key = { it.productId }) { change ->
            Text(
                text = stringResource(
                    R.string.notice_purchase_cost_change,
                    change.name,
                    rememberMoneyDisplay(change.before, money).text.text,
                    rememberMoneyDisplay(change.after, money).text.text,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    Box(Modifier.keepsMessagesAbove().padding(spacing.s16).navigationBarsPadding()) {
        PrimaryActionButton(
            text = stringResource(R.string.action_close),
            onClick = { onAction(NewPurchaseAction.Finish) },
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PurchaseSheets(uiState: NewPurchaseUiState, onAction: (NewPurchaseAction) -> Unit) {
    when (val sheet = uiState.sheet) {
        null -> Unit

        is PurchaseSheet.Products -> SelectionSheet(
            title = stringResource(R.string.empty_purchase_action),
            items = sheet.options,
            key = { it.id },
            query = sheet.query,
            onQueryChange = { onAction(NewPurchaseAction.QueryChange(it)) },
            onDismissRequest = { onAction(NewPurchaseAction.CloseSheet) },
            searchHint = stringResource(R.string.sale_search_hint),
            itemContent = { option ->
                SettingsRow(
                    title = option.name,
                    action = SettingsRowAction.Navigation {
                        onAction(NewPurchaseAction.AddProduct(option.id))
                    },
                    summary = stringResource(R.string.status_archived).takeIf { option.archived },
                )
            },
            onCreate = { onAction(NewPurchaseAction.CreateProduct(it)) },
        )

        is PurchaseSheet.Suppliers -> SelectionSheet(
            title = stringResource(R.string.purchase_supplier),
            items = sheet.options,
            key = { it.id ?: NO_SUPPLIER_KEY },
            query = sheet.query,
            onQueryChange = { onAction(NewPurchaseAction.QueryChange(it)) },
            onDismissRequest = { onAction(NewPurchaseAction.CloseSheet) },
            searchHint = stringResource(R.string.supplier_search_hint),
            itemContent = { option ->
                SettingsRow(
                    title = option.name ?: stringResource(R.string.purchase_no_supplier),
                    action = SettingsRowAction.Navigation {
                        onAction(NewPurchaseAction.SelectSupplier(option.id))
                    },
                )
            },
        )
    }
}

@Composable
private fun PurchaseDialogs(uiState: NewPurchaseUiState, onAction: (NewPurchaseAction) -> Unit) {
    when (val dialog = uiState.dialog) {
        null -> Unit

        PurchaseDialog.Discard -> ConfirmDialog(
            title = stringResource(R.string.dialog_discard_title),
            body = stringResource(R.string.dialog_discard_body),
            confirmLabel = stringResource(R.string.dialog_discard_action),
            onConfirm = { onAction(NewPurchaseAction.ConfirmLeave) },
            onDismiss = { onAction(NewPurchaseAction.DismissDialog) },
            dismissLabel = stringResource(R.string.dialog_discard_exit),
        )

        is PurchaseDialog.Reactivate -> ConfirmDialog(
            title = dialog.name,
            body = stringResource(R.string.notice_product_archived, dialog.name),
            confirmLabel = stringResource(R.string.action_reactivate),
            onConfirm = { onAction(NewPurchaseAction.ConfirmReactivate) },
            onDismiss = { onAction(NewPurchaseAction.DismissDialog) },
        )
    }
}

@Composable
private fun fieldErrorText(error: PurchaseFieldError, product: String): String = when (error) {
    PurchaseFieldError.REQUIRED, PurchaseFieldError.NOT_A_NUMBER ->
        stringResource(R.string.field_error_number)

    PurchaseFieldError.ZERO_QUANTITY -> stringResource(R.string.error_quantity_zero)

    PurchaseFieldError.FRACTION_NOT_ALLOWED -> stringResource(R.string.error_unit_decimals)

    PurchaseFieldError.TOO_HIGH -> stringResource(R.string.error_value_high)

    PurchaseFieldError.STOCK_OVER_LIMIT -> stringResource(R.string.error_stock_over_limit, product)

    PurchaseFieldError.UNIT_UNAVAILABLE -> stringResource(R.string.purchase_unit_unavailable)
}

/** El texto de una falla al registrar: el espacio del teléfono, o la falla general. */
private fun registerFailedText(outOfSpace: Boolean): Int =
    if (outOfSpace) R.string.error_device_storage else R.string.error_purchase_register

/** La clave de `Sin proveedor` en la hoja: ningún id real es negativo. */
private const val NO_SUPPLIER_KEY = -1L

/** Desde esta anchura, las líneas a la izquierda y el resumen a la derecha. */
private val EXPANDED_WIDTH = 840.dp

/** Ancho máximo del formulario en una ventana mediana. */
private val FORM_WIDTH = 600.dp
