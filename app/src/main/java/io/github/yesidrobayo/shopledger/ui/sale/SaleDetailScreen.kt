package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.ProductStock
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlinx.datetime.YearMonth

/**
 * El detalle de una venta: lo que se vendió, cómo se pagó y lo que dejó, y anularla si hizo falta.
 * Una venta confirmada no se edita.
 *
 * - Arriba el héroe con el total, la fecha y la hora; anulada, el total va tachado con su motivo
 *   y su fecha. El total va en el héroe y no sobre el papel: es la firma de la app, un héroe por
 *   pantalla.
 * - Los pagos con el nombre que tenía el método al cobrar, y las líneas con el que tenía el
 *   producto al venderse. Un producto hoy archivado se marca.
 * - Costo, ganancia, margen, comisiones y neto solo aquí, y se ocultan en modo discreto. El total
 *   y los pagos se ven siempre: son lo que el cliente ya conoce.
 * - `Anular venta` no compite: es una acción más al final.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun SaleDetailScreen(
    uiState: SaleDetailUiState,
    onAction: (SaleDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notices = remember { SnackbarHostState() }
    val voidedText = stringResource(R.string.confirm_sale_voided)
    val ready = uiState as? SaleDetailUiState.Ready
    LaunchedEffect(ready?.voided) {
        if (ready?.voided != true) return@LaunchedEffect
        onAction(SaleDetailAction.VoidedShown)
        notices.showSnackbar(AppSnackbarVisuals(voidedText, SnackbarVariant.SUCCESS))
    }
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = titleOf(uiState),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(SaleDetailAction.Back) },
                    navigationLabel = stringResource(R.string.a11y_back),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = AppTheme.sizes.maxContentWidth),
                ) {
                    when (uiState) {
                        SaleDetailUiState.Loading -> DelayedSkeleton { DetailSkeleton() }

                        SaleDetailUiState.NotFound, SaleDetailUiState.ReadError -> ReadProblem(
                            retry = uiState == SaleDetailUiState.ReadError,
                            onAction = onAction,
                        )

                        is SaleDetailUiState.Ready -> DetailBody(uiState.sale, onAction)
                    }
                }
            }
            AppSnackbarHost(
                notices,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }
    }
    if (ready?.sheet != null) VoidSheetContent(ready, onAction)
}

@Composable
private fun titleOf(uiState: SaleDetailUiState): String {
    val ready = uiState as? SaleDetailUiState.Ready ?: return stringResource(R.string.term_sale)
    val number = LocalAppFormats.current.quantity.format(
        QuantityMilli.ofUnits(ready.sale.saleId),
        allowsFraction = false,
    )
    return stringResource(R.string.sale_detail_title, number)
}

@Composable
private fun DetailBody(sale: SaleDetailView, onAction: (SaleDetailAction) -> Unit) {
    val spacing = AppTheme.spacing
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = spacing.s32),
    ) {
        item(key = TOTAL) {
            TotalBlock(
                sale,
                Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
            )
        }
        item(key = PAYMENTS) { SectionHeader(stringResource(R.string.sale_detail_payments)) }
        items(sale.payments, key = { "payment-${it.paymentId}" }) { PaymentRow(it) }
        if (!sale.charges.isEmpty) item(key = CHARGES) { ChargeRows(sale.charges) }
        item(key = LINES) {
            val count = sale.lines.size
            SectionHeader(pluralStringResource(R.plurals.sale_summary_products, count, count))
        }
        items(sale.lines, key = { "line-${it.lineId}" }) { LineRow(it) }
        item(key = RESULTS) {
            ResultsCard(sale, Modifier.padding(horizontal = spacing.s16, vertical = spacing.s16))
        }
        sale.note?.let { note ->
            item(key = NOTE) {
                Column(
                    Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                    verticalArrangement = Arrangement.spacedBy(spacing.s4),
                ) {
                    Text(
                        stringResource(R.string.sale_note_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(note, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (sale.canVoid) {
            item(key = ACTIONS) {
                PrimaryActionButton(
                    text = stringResource(R.string.dialog_void_sale_action),
                    onClick = { onAction(SaleDetailAction.OpenVoid) },
                    variant = ActionVariant.OUTLINED,
                    modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s16),
                )
            }
        }
    }
}

/**
 * El héroe de la pantalla: el total, la fecha y la hora. Anulada, el total va tachado, con la etiqueta, el motivo y la fecha
 * de la anulación: la venta sigue en el historial, sin contar.
 */
@Composable
private fun TotalBlock(sale: SaleDetailView, modifier: Modifier = Modifier) {
    val formats = LocalAppFormats.current
    val app = AppTheme.colors
    val dateText = sale.zone?.let { zone ->
        stringResource(
            R.string.sale_detail_when,
            formats.date.longDate(sale.businessDate),
            formats.date.time(sale.occurredAt, zone),
        )
    } ?: formats.date.longDate(sale.businessDate)
    val muted = app.onHeroVariant
    Column(
        modifier
            .fillMaxWidth()
            .background(app.heroContainer, MaterialTheme.shapes.extraLarge)
            .padding(AppTheme.spacing.s20),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        MoneyText(
            amount = sale.total,
            size = MoneySize.HERO,
            tone = MoneyTone.ON_HERO,
            voided = sale.voiding != null,
        )
        Text(dateText, style = MaterialTheme.typography.bodyMedium, color = muted)
        sale.voiding?.let { voiding ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
            ) {
                // El total tachado ya lleva la etiqueta `Anulada`: aquí van la fecha y el motivo.
                val reason = stringResource(reasonLabel(voiding.reason))
                val text = voiding.date?.let {
                    stringResource(R.string.sale_voided_on, formats.date.longDate(it), reason)
                } ?: reason
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = app.heroAccent,
                )
            }
            voiding.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/**
 * Un pago: el método a la izquierda y el monto a la derecha, y cuándo se acredita si tarda. Con
 * comisión, debajo van la comisión y lo que llega de verdad, como el descuento de una línea, y se
 * ocultan en modo discreto: el monto cobrado lo conoce quien compra, la comisión no.
 */
@Composable
private fun PaymentRow(payment: SaleDetailPayment) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRowSingle)
                .padding(horizontal = spacing.s16, vertical = spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(payment.methodName, style = MaterialTheme.typography.titleSmall)
                payment.settlesOn?.let {
                    Text(
                        stringResource(R.string.checkout_settles_on, formats.date.longDate(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            MoneyText(payment.amount, MoneySize.ROW)
        }
        if (payment.hasFee) {
            DetailSubRow(stringResource(R.string.term_fee)) {
                MoneyText(-payment.fee, MoneySize.CAPTION, tone = MoneyTone.MUTED, sensitive = true)
            }
            DetailSubRow(stringResource(R.string.term_net_received), last = true) {
                MoneyText(
                    payment.netReceived,
                    MoneySize.CAPTION,
                    tone = MoneyTone.MUTED,
                    sensitive = true,
                )
            }
        } else {
            // Sin comisión, la línea igual separa este pago del siguiente o de los cargos.
            HorizontalDivider(
                modifier = Modifier.padding(start = spacing.s16),
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * Lo que explica el total además de las líneas, bajo los pagos: el impuesto de cada tasa con su
 * nombre, el ajuste de toda la venta, la propina y el redondeo del efectivo, solo si no son cero.
 * El ajuste y el redondeo llevan su signo.
 */
@Composable
private fun ChargeRows(charges: SaleDetailCharges) {
    Column(Modifier.padding(top = AppTheme.spacing.s4)) {
        val rows = buildList<Pair<String, Money>> {
            for (tax in charges.taxes) {
                add((tax.name ?: stringResource(R.string.term_tax)) to tax.amount)
            }
            if (charges.orderAdjustment != Money.ZERO) {
                add(stringResource(R.string.checkout_sale_adjustment) to charges.orderAdjustment)
            }
            if (charges.tip != Money.ZERO) add(stringResource(R.string.term_tip) to charges.tip)
            if (charges.cashRounding != Money.ZERO) {
                add(stringResource(R.string.region_cash_rounding) to charges.cashRounding)
            }
        }
        rows.forEachIndexed { index, (label, amount) ->
            DetailSubRow(label, last = index == rows.lastIndex) {
                MoneyText(amount, MoneySize.CAPTION, tone = MoneyTone.MUTED)
            }
        }
    }
}

/**
 * Una fila de apoyo bajo otra: la etiqueta pequeña a la izquierda y la cifra a la derecha. La
 * última del grupo lleva la línea que lo separa del siguiente.
 */
@Composable
private fun DetailSubRow(label: String, last: Boolean = false, value: @Composable () -> Unit) {
    val spacing = AppTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        value()
    }
    if (last) {
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** Una línea vendida, con `cantidad × precio`, su total y el descuento si lo tuvo. */
@Composable
private fun LineRow(line: SaleDetailLine) {
    val unit = unitLabelOf(line.unit)
    Column {
        ProductRow(
            data = ProductRowData(
                name = line.name,
                stock = ProductStock.Service,
                trailing = ProductRowTrailing.SaleLine(
                    line.total,
                    line.quantity,
                    line.unitPrice,
                    unit,
                ),
                archived = line.archived,
            ),
            divider = line.discount == Money.ZERO,
        )
        if (line.discount > Money.ZERO) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppTheme.spacing.s16,
                        end = AppTheme.spacing.s16,
                        bottom = AppTheme.spacing.s8,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.sale_line_discount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MoneyText(-line.discount, MoneySize.CAPTION, tone = MoneyTone.MUTED)
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = AppTheme.spacing.s16),
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * Lo que dejó la venta: costo, ganancia, margen, comisiones y neto. Todo se oculta en modo
 * discreto; la ganancia negativa va con su signo.
 */
@Composable
private fun ResultsCard(sale: SaleDetailView, modifier: Modifier = Modifier) {
    val results = sale.results
    AppCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            ResultRow(stringResource(R.string.term_cost)) {
                MoneyText(results.cost, MoneySize.ROW, sensitive = true)
            }
            ResultRow(stringResource(R.string.term_sales_profit)) {
                MoneyText(
                    results.profit,
                    MoneySize.ROW,
                    tone = if (results.profit < Money.ZERO) MoneyTone.LOSS else MoneyTone.DEFAULT,
                    sensitive = true,
                )
            }
            ResultRow(stringResource(R.string.term_margin)) {
                DiscreetValue {
                    val margin = results.margin
                    Text(
                        text =
                            if (margin is Margin.Defined) {
                                LocalAppFormats.current.percent.format(margin.value)
                            } else {
                                stringResource(R.string.a11y_no_value)
                            },
                        style = AppTheme.amounts.row,
                    )
                }
            }
            if (results.fees > Money.ZERO) {
                ResultRow(stringResource(R.string.term_fee)) {
                    MoneyText(results.fees, MoneySize.ROW, sensitive = true)
                }
                ResultRow(stringResource(R.string.term_net_received)) {
                    MoneyText(results.netReceived, MoneySize.ROW, sensitive = true)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        value()
    }
}

/**
 * La hoja de anular: el motivo, una nota opcional y lo que pasará, el stock que vuelve y el mes que
 * cambia, que es el de la venta aunque se anule en otro.
 */
@Composable
private fun VoidSheetContent(ready: SaleDetailUiState.Ready, onAction: (SaleDetailAction) -> Unit) {
    val sheet = requireNotNull(ready.sheet)
    val date = ready.sale.businessDate.date
    val period = LocalAppFormats.current.date.monthYear(YearMonth(date.year, date.month))
    AppBottomSheet(
        onDismissRequest = { onAction(SaleDetailAction.CloseSheet) },
        title = stringResource(R.string.dialog_void_sale_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.dialog_void_sale_action),
                onClick = { onAction(SaleDetailAction.ConfirmVoid) },
                variant = ActionVariant.DESTRUCTIVE,
                enabled = !sheet.noteTooLong,
                state = if (ready.status ==
                    VoidStatus.WORKING
                ) {
                    ActionState.WORKING
                } else {
                    ActionState.IDLE
                },
            )
        },
    ) {
        Text(
            stringResource(R.string.dialog_void_sale_body, period),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CompactChipRow(
            options = VoidSheet.REASONS,
            label = { stringResource(reasonLabel(it)) },
            selected = setOf(sheet.reason),
            onToggle = { onAction(SaleDetailAction.ReasonChange(it)) },
            multiple = false,
        )
        AppTextField(
            value = sheet.note,
            onValueChange = { onAction(SaleDetailAction.NoteChange(it)) },
            label = stringResource(R.string.form_note),
            singleLine = false,
            error =
                if (sheet.noteTooLong) {
                    pluralStringResource(
                        R.plurals.error_text_long,
                        VoidSheet.MAX_VOID_NOTE_LENGTH,
                        VoidSheet.MAX_VOID_NOTE_LENGTH,
                    )
                } else {
                    null
                },
        )
        if (ready.status == VoidStatus.FAILED) {
            NoticeCard(stringResource(R.string.error_sale_void), NoticeVariant.ERROR)
        }
    }
}

/** El nombre de un motivo de anulación, como lo eligió la persona. */
internal fun reasonLabel(reason: VoidReason): Int = when (reason) {
    VoidReason.ENTRY_ERROR -> R.string.void_reason_entry_error
    VoidReason.CUSTOMER_RETURN -> R.string.void_reason_customer_return
    VoidReason.SUPPLIER_RETURN -> R.string.void_reason_supplier_return
    VoidReason.UNDO -> R.string.void_reason_undo
    VoidReason.OTHER -> R.string.void_reason_other
}

@Composable
private fun ReadProblem(retry: Boolean, onAction: (SaleDetailAction) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(icon = R.drawable.ic_error, title = stringResource(R.string.state_error_read)) {
            if (retry) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(SaleDetailAction.Retry) },
                    variant = ActionVariant.TONAL,
                )
            }
        }
    }
}

/** Tres filas de esqueleto bajo el bloque del total. */
@Composable
private fun DetailSkeleton() {
    val spacing = AppTheme.spacing
    Column(Modifier.padding(spacing.s16), verticalArrangement = Arrangement.spacedBy(spacing.s12)) {
        SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.saleTile))
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
        }
    }
}

private const val SKELETON_ROWS = 3
private const val TOTAL = "total"
private const val PAYMENTS = "payments"
private const val CHARGES = "charges"
private const val LINES = "lines"
private const val RESULTS = "results"
private const val NOTE = "note"
private const val ACTIONS = "actions"
