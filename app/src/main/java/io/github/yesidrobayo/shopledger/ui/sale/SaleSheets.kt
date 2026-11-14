package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartLineIssue
import io.github.yesidrobayo.shopledger.domain.rule.sale.HeldSaleRules
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.LotPicker
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PaymentMethodChips
import io.github.yesidrobayo.shopledger.ui.components.PercentField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.QuantityStepper
import io.github.yesidrobayo.shopledger.ui.components.UnitPicker
import io.github.yesidrobayo.shopledger.ui.components.UnitPickerSheet
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.components.lotOptionsOf
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La hoja abierta sobre la venta. En dos paneles, las líneas ya están a la vista: su hoja no se
 * abre.
 */
@Composable
internal fun SaleSheets(
    uiState: NewSaleUiState,
    onAction: (NewSaleAction) -> Unit,
    twoPane: Boolean,
) {
    val close = { onAction(NewSaleAction.CloseSheet) }
    when (val sheet = uiState.sheet) {
        null -> Unit

        SaleSheet.Cart -> if (!twoPane) {
            val count = uiState.cart.itemCount.toInt()
            AppBottomSheet(
                onDismissRequest = close,
                title = pluralStringResource(R.plurals.sale_summary_products, count, count),
            ) { CartContent(uiState.cart, onAction) }
        }

        is SaleSheet.Line ->
            LineSheet(sheet.editor, uiState.cart, uiState.lots[sheet.editor.productId], onAction)

        is SaleSheet.Quantity -> QuantitySheet(sheet, onAction)

        SaleSheet.PaymentMethods -> AppBottomSheet(
            onDismissRequest = close,
            title = stringResource(R.string.filter_payment_method),
        ) {
            val selected = uiState.payment.selected
            if (selected != null) {
                PaymentMethodChips(
                    methods = methodOptions(uiState.payment.methods),
                    selectedId = selected.id,
                    onSelect = { onAction(NewSaleAction.SelectMethod(it)) },
                )
            }
        }

        is SaleSheet.Hold -> HoldSheet(sheet, onAction)

        is SaleSheet.VariantPick -> VariantPickerSheet(sheet, onAction)

        SaleSheet.CurrencyRequired -> AppBottomSheet(
            onDismissRequest = close,
            title = stringResource(R.string.region_required_title),
            action = {
                PrimaryActionButton(
                    text = stringResource(R.string.region_required_action),
                    onClick = { onAction(NewSaleAction.ConfirmCurrency) },
                    variant = ActionVariant.TONAL,
                )
            },
        ) {
            Text(
                stringResource(R.string.region_required_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Las líneas de la venta, con su selector de cantidad, la fecha, la nota y `Vaciar venta`. Es la
 * hoja de las líneas en un teléfono, y el panel derecho en una tableta.
 */
@Composable
internal fun CartContent(cart: SaleCartState, onAction: (NewSaleAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        cart.lines.forEachIndexed { index, line ->
            val parent = line.parentProductId
            if (parent == null) {
                CartLineRow(line, onAction)
            } else {
                CartExtraRow(line, parent, onAction)
            }
            // Los extras van pegados a su línea: el separador cierra el grupo.
            if (cart.lines.getOrNull(index + 1)?.parentProductId == null) {
                HorizontalDivider(
                    thickness = AppTheme.sizes.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        if (cart.isEmpty) return@Column
        cart.tax?.let { tax ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.term_tax),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                MoneyText(tax, MoneySize.ROW)
            }
        }
        if (cart.cashRounding != Money.ZERO) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.checkout_cash_rounding),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                MoneyText(cart.cashRounding, MoneySize.ROW)
            }
        }
        if (cart.priceLists.isNotEmpty()) {
            val resources = LocalResources.current
            Text(
                stringResource(R.string.sale_price_list_chip),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompactChipRow(
                options = cart.priceLists,
                label = {
                    SeedText.displayName(resources, SeedTable.PRICE_LISTS, it.systemKey, it.name)
                },
                selected = cart.priceLists.filter { it.id == cart.priceListId }.toSet(),
                onToggle = { onAction(NewSaleAction.SelectPriceList(it.id)) },
                multiple = false,
            )
        }
        val today = cart.today
        val weekStart = cart.weekStartDay
        if (today != null && weekStart != null) {
            DateField(
                label = stringResource(R.string.form_date),
                date = cart.date ?: today,
                onDateChange = { onAction(NewSaleAction.DateChange(it)) },
                today = today,
                weekStartDay = weekStart,
            )
        }
        AppTextField(
            value = cart.note,
            onValueChange = { onAction(NewSaleAction.NoteChange(it)) },
            label = stringResource(R.string.sale_note_label),
            singleLine = false,
            imeAction = ImeAction.Default,
            error =
                if (cart.noteTooLong) {
                    pluralStringResource(
                        R.plurals.error_text_long,
                        NewSaleViewModel.MAX_NOTE_LENGTH,
                        NewSaleViewModel.MAX_NOTE_LENGTH,
                    )
                } else {
                    null
                },
        )
        PrimaryActionButton(
            text = stringResource(R.string.draft_hold_action),
            onClick = { onAction(NewSaleAction.OpenHold) },
            variant = ActionVariant.OUTLINED,
        )
        PrimaryActionButton(
            text = stringResource(R.string.sale_clear),
            onClick = { onAction(NewSaleAction.ClearSale) },
            variant = ActionVariant.OUTLINED,
        )
    }
}

/**
 * El selector de variantes: un eje por fila, con los valores sin stock tachados y elegibles.
 * Completar los ejes agrega la línea y cierra la hoja, sin botón extra.
 */
@Composable
private fun VariantPickerSheet(sheet: SaleSheet.VariantPick, onAction: (NewSaleAction) -> Unit) {
    val spacing = AppTheme.spacing
    AppBottomSheet(
        onDismissRequest = { onAction(NewSaleAction.CloseSheet) },
        title = stringResource(R.string.variant_picker_title),
    ) {
        Text(
            text = sheet.parentName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sheet.axes.forEachIndexed { index, axis ->
            Text(
                text = axis.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = spacing.s12, bottom = spacing.s4),
            )
            CompactChipRow(
                options = axis.values,
                label = { it },
                selected = setOfNotNull(sheet.chosen.getOrNull(index)),
                onToggle = { onAction(NewSaleAction.PickVariantValue(index, it)) },
                multiple = false,
                struck = { it in axis.outOfStock },
            )
        }
    }
}

/**
 * Poner la venta en espera: un nombre opcional, como una mesa, y `Guardar en espera`. Si falla, la
 * hoja sigue abierta con lo escrito, y la venta, intacta.
 */
@Composable
private fun HoldSheet(sheet: SaleSheet.Hold, onAction: (NewSaleAction) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AppBottomSheet(
        onDismissRequest = { onAction(NewSaleAction.CloseSheet) },
        title = stringResource(R.string.draft_hold_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.draft_hold_confirm_action),
                onClick = { onAction(NewSaleAction.ConfirmHold) },
                variant = ActionVariant.TONAL,
                enabled = !sheet.tooLong,
                state = if (sheet.working) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        AppTextField(
            value = sheet.label,
            onValueChange = { onAction(NewSaleAction.HoldLabelChange(it)) },
            label = stringResource(R.string.draft_label),
            modifier = Modifier.focusRequester(focus),
            supportingText = stringResource(R.string.draft_label_help),
            imeAction = ImeAction.Done,
            error =
                if (sheet.tooLong) {
                    pluralStringResource(
                        R.plurals.error_text_long,
                        HeldSaleRules.MAX_LABEL_LENGTH,
                        HeldSaleRules.MAX_LABEL_LENGTH,
                    )
                } else {
                    null
                },
        )
    }
}

/**
 * Un extra, sangrado bajo la línea de su producto: `+ nombre`, `cantidad × precio` y su total, sin
 * selector, porque sigue la cantidad de su línea. Tocarlo abre la línea de su producto, donde se
 * quita.
 */
@Composable
private fun CartExtraRow(
    line: CartLine,
    parentProductId: Long,
    onAction: (NewSaleAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val unit = unitLabelOf(line.unit)
    val price = rememberMoneyDisplay(line.unitPrice, LocalAppFormats.current.money)
    // La fila tocable junta sus textos en un solo foco: TalkBack lee el extra con su monto.
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onAction(NewSaleAction.OpenLine(parentProductId)) }
            .padding(start = spacing.s24, top = spacing.s4, bottom = spacing.s4),
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.s12)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(
                    stringResource(R.string.sale_extra_line, line.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = formatAnnotated(
                        stringResource(R.string.format_line_quantity_price),
                        quantityText(line.quantity, unit),
                        price.text,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoneyText(line.total, MoneySize.ROW)
        }
        lineProblem(line.issue)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * El aviso de una línea cuyo lote ya venció: icono y texto, nunca solo color. No frena la venta.
 */
@Composable
private fun ExpiredLotLine(date: BusinessDate) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_block),
            contentDescription = null,
            tint = AppTheme.colors.loss,
            modifier = Modifier.size(AppTheme.spacing.s16),
        )
        Text(
            stringResource(
                R.string.lot_warning_expired_sale,
                LocalAppFormats.current.date.dayMonthYear(date),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.loss,
        )
    }
}

/** Una línea: nombre, `cantidad × precio`, su total y el selector de cantidad. Tocarla la abre. */
@Composable
private fun CartLineRow(line: CartLine, onAction: (NewSaleAction) -> Unit) {
    val spacing = AppTheme.spacing
    val unit = unitLabelOf(line.unit)
    val price = rememberMoneyDisplay(line.unitPrice, LocalAppFormats.current.money)
    Column(
        Modifier.padding(vertical = spacing.s4),
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onAction(NewSaleAction.OpenLine(line.productId)) },
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(line.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = formatAnnotated(
                        stringResource(R.string.format_line_quantity_price),
                        quantityText(line.quantity, unit),
                        price.text,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoneyText(line.total, MoneySize.ROW)
        }
        lineProblem(line.issue)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        line.expiredLot?.let { ExpiredLotLine(it) }
        QuantityStepper(
            quantity = line.quantity,
            unit = unit,
            productName = line.name,
            onDecrement = { onAction(NewSaleAction.Decrement(line.productId)) },
            onIncrement = { onAction(NewSaleAction.Increment(line.productId)) },
            removesLine = line.removesLine,
            canIncrement = line.canIncrement,
            onValueClick =
                if (unit.allowsFraction) {
                    { onAction(NewSaleAction.EditQuantity(line.productId)) }
                } else {
                    null
                },
        )
    }
}

@Composable
private fun lineProblem(issue: CartLineIssue?): String? = when (issue) {
    null -> null
    CartLineIssue.INVALID_DISCOUNT -> stringResource(R.string.error_discount_over_line)
    CartLineIssue.AMOUNT_TOO_LARGE -> stringResource(R.string.error_value_high)
    CartLineIssue.PRODUCT_UNAVAILABLE -> stringResource(R.string.status_archived)
    CartLineIssue.UNIT_UNAVAILABLE -> stringResource(R.string.sale_unit_unavailable)
}

/**
 * Una línea: cantidad, precio de esta línea y descuento, en monto o porcentaje. Cada cambio se ve en
 * el total al momento. Sin costo, ganancia ni margen: quien compra puede estar viendo la pantalla.
 */
@Composable
private fun LineSheet(
    editor: LineEditor,
    cart: SaleCartState,
    lots: LotChoices?,
    onAction: (NewSaleAction) -> Unit,
) {
    val line = cart.lines.firstOrNull {
        it.parentProductId == null && it.productId == editor.productId
    }
    val unit = unitLabelOf(editor.unit)
    val focus = remember { FocusRequester() }
    LaunchedEffect(editor.productId, editor.focusPrice) {
        if (editor.focusPrice) focus.requestFocus()
    }
    AppBottomSheet(
        onDismissRequest = { onAction(NewSaleAction.CloseSheet) },
        title = editor.name,
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.action_remove),
                onClick = { onAction(NewSaleAction.RemoveLine(editor.productId)) },
                variant = ActionVariant.OUTLINED,
            )
        },
    ) {
        QuantityField(
            value = editor.quantityText,
            onValueChange = { onAction(NewSaleAction.LineQuantityChange(it)) },
            label = stringResource(R.string.sale_line_quantity),
            unit = unit,
            error = fieldError(editor.quantityError),
        )
        // Solo si el producto se vende en más de una unidad, como por unidad y por caja.
        if (editor.saleUnits.size > 1) {
            var picking by remember { mutableStateOf(false) }
            UnitPicker(
                label = stringResource(R.string.term_unit),
                unit = editor.unit,
                onClick = { picking = true },
            )
            if (picking) {
                UnitPickerSheet(
                    units = editor.saleUnits,
                    onPick = {
                        picking = false
                        onAction(NewSaleAction.LineUnitChange(it.id))
                    },
                    onDismiss = { picking = false },
                )
            }
        }
        // Solo con lotes: la línea sale por el vencimiento más cercano, salvo que se elija otro.
        lots?.let { choices ->
            LotPicker(
                options = lotOptionsOf(choices, withUnassigned = false),
                selectedId = line?.lotId,
                unit = unit,
                onSelect = { onAction(NewSaleAction.LineLotChange(it)) },
                label = stringResource(R.string.lot_change_action),
            )
        }
        line?.expiredLot?.let { date ->
            NoticeCard(
                text = stringResource(
                    R.string.lot_warning_expired_sale,
                    LocalAppFormats.current.date.dayMonthYear(date),
                ),
                variant = NoticeVariant.WARNING,
                icon = R.drawable.ic_block,
            )
        }
        MoneyField(
            value = editor.priceText,
            onValueChange = { onAction(NewSaleAction.LinePriceChange(it)) },
            label = stringResource(R.string.sale_line_price),
            modifier = Modifier.focusRequester(focus),
            error = fieldError(editor.priceError),
        )
        Text(
            stringResource(R.string.sale_line_discount),
            style = MaterialTheme.typography.labelLarge,
        )
        CompactChipRow(
            options = DiscountMode.entries,
            label = {
                when (it) {
                    DiscountMode.AMOUNT -> stringResource(R.string.sale_line_discount_amount)
                    DiscountMode.PERCENT -> stringResource(R.string.sale_line_discount_percent)
                }
            },
            selected = setOf(editor.discountMode),
            onToggle = { onAction(NewSaleAction.LineDiscountModeChange(it)) },
            multiple = false,
        )
        when (editor.discountMode) {
            DiscountMode.AMOUNT -> MoneyField(
                value = editor.discountText,
                onValueChange = { onAction(NewSaleAction.LineDiscountChange(it)) },
                label = stringResource(R.string.sale_line_discount),
                error = fieldError(editor.discountError),
                imeAction = ImeAction.Done,
            )

            DiscountMode.PERCENT -> PercentField(
                value = editor.discountText,
                onValueChange = { onAction(NewSaleAction.LineDiscountChange(it)) },
                label = stringResource(R.string.sale_line_discount),
                error = fieldError(editor.discountError),
                imeAction = ImeAction.Done,
            )
        }
        // Los extras se ofrecen sin frenar: la línea ya vale sin ellos.
        if (editor.extras.isNotEmpty()) {
            Text(
                stringResource(R.string.sale_extras_label),
                style = MaterialTheme.typography.labelLarge,
            )
            CompactChipRow(
                options = editor.extras,
                label = { it.name },
                selected = editor.extras.filter { it.selected }.toSet(),
                onToggle = { onAction(NewSaleAction.ToggleExtra(it.productId)) },
                multiple = true,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sale_line_total),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            MoneyText(line?.total, MoneySize.TITLE)
        }
    }
}

/**
 * La cantidad de un producto por peso o medida, con el campo enfocado y los atajos sobre el
 * teclado. Un atajo agrega y cierra. Los atajos son fracciones de la unidad, nunca una medida local.
 */
@Composable
private fun QuantitySheet(sheet: SaleSheet.Quantity, onAction: (NewSaleAction) -> Unit) {
    val unit = unitLabelOf(sheet.unit)
    val focus = remember { FocusRequester() }
    LaunchedEffect(sheet.productId) { focus.requestFocus() }
    AppBottomSheet(
        onDismissRequest = { onAction(NewSaleAction.CloseSheet) },
        title = sheet.name,
        action = {
            PrimaryActionButton(
                text = stringResource(
                    if (sheet.adding) R.string.action_add else R.string.action_save,
                ),
                onClick = { onAction(NewSaleAction.QuantityConfirm) },
                variant = ActionVariant.TONAL,
            )
        },
    ) {
        QuantityField(
            value = sheet.text,
            onValueChange = { onAction(NewSaleAction.QuantityTextChange(it)) },
            label = stringResource(R.string.sale_line_quantity),
            unit = unit,
            modifier = Modifier.focusRequester(focus),
            error = fieldError(sheet.error),
            imeAction = ImeAction.Done,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            QUANTITY_SHORTCUTS.forEach { milli ->
                val quantity = QuantityMilli(milli)
                PrimaryActionButton(
                    text = quantityText(quantity, unit),
                    onClick = { onAction(NewSaleAction.QuantityShortcut(quantity)) },
                    variant = ActionVariant.OUTLINED,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun fieldError(error: SaleFieldError?): String? = when (error) {
    null -> null
    SaleFieldError.NOT_A_NUMBER -> stringResource(R.string.field_error_number)
    SaleFieldError.TOO_HIGH -> stringResource(R.string.error_value_high)
    SaleFieldError.FRACTION_NOT_ALLOWED -> stringResource(R.string.error_unit_decimals)
    SaleFieldError.ZERO_QUANTITY -> stringResource(R.string.error_quantity_zero)
    SaleFieldError.DISCOUNT_OVER_LINE -> stringResource(R.string.error_discount_over_line)
}

/** El diálogo abierto: salir con productos, vaciar la venta o confirmar una venta en cero. */
@Composable
internal fun SaleDialogs(uiState: NewSaleUiState, onAction: (NewSaleAction) -> Unit) {
    val dismiss = { onAction(NewSaleAction.DismissDialog) }
    when (val dialog = uiState.dialog) {
        null -> Unit

        is SaleDialog.Leave -> {
            val count = dialog.itemCount.toInt()
            ConfirmDialog(
                title = stringResource(R.string.dialog_leave_sale_title),
                body = pluralStringResource(R.plurals.dialog_leave_sale_body, count, count),
                confirmLabel = stringResource(R.string.dialog_leave_sale_action),
                onConfirm = { onAction(NewSaleAction.ConfirmLeave) },
                onDismiss = dismiss,
                variant = ConfirmVariant.DESTRUCTIVE,
                dismissLabel = stringResource(R.string.dialog_leave_sale_exit),
            )
        }

        SaleDialog.LeaveExchange -> ConfirmDialog(
            title = stringResource(R.string.sale_return_exchange_exit_confirm_title),
            body = stringResource(R.string.sale_return_exchange_exit_confirm_body),
            confirmLabel = stringResource(R.string.sale_return_exchange_exit_confirm_action),
            onConfirm = { onAction(NewSaleAction.ConfirmLeave) },
            onDismiss = dismiss,
            variant = ConfirmVariant.DESTRUCTIVE,
            dismissLabel = stringResource(R.string.sale_return_exchange_exit_keep_action),
        )

        is SaleDialog.Clear -> {
            val count = dialog.itemCount.toInt()
            ConfirmDialog(
                title = stringResource(R.string.dialog_clear_sale_title),
                body = pluralStringResource(R.plurals.dialog_clear_sale_body, count, count),
                confirmLabel = stringResource(R.string.dialog_clear_sale_action),
                onConfirm = { onAction(NewSaleAction.ConfirmClear) },
                onDismiss = dismiss,
                variant = ConfirmVariant.DESTRUCTIVE,
                dismissLabel = stringResource(R.string.action_back),
            )
        }

        SaleDialog.ZeroTotal -> {
            val zero = rememberMoneyDisplay(Money.ZERO, LocalAppFormats.current.money)
            ConfirmDialog(
                title = stringResource(R.string.dialog_zero_sale_title, zero.text.text),
                body = stringResource(R.string.dialog_zero_sale_body),
                confirmLabel = stringResource(R.string.sale_register),
                onConfirm = { onAction(NewSaleAction.ConfirmZeroTotal) },
                onDismiss = dismiss,
                dismissLabel = stringResource(R.string.action_back),
            )
        }
    }
}

/** Los atajos de la hoja de cantidad: un cuarto, media y una unidad entera. */
private val QUANTITY_SHORTCUTS = listOf(250L, 500L, 1_000L)
