package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.sale.ForeignTender
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.ProductStock
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.money.ProvideAccountCurrency
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
 * - `Anular y corregir` y `Anular venta` no compiten: son acciones al final. Corregir pregunta antes,
 *   porque la venta queda anulada aunque la nueva no se guarde.
 * - `Compartir`, en el menú, entrega el comprobante como texto al selector del sistema, solo de una
 *   venta activa.
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
    val voidFailedText = stringResource(R.string.error_sale_void)
    val ready = uiState as? SaleDetailUiState.Ready
    LaunchedEffect(ready?.voided) {
        if (ready?.voided != true) return@LaunchedEffect
        onAction(SaleDetailAction.VoidedShown)
        notices.showSnackbar(AppSnackbarVisuals(voidedText, SnackbarVariant.SUCCESS))
    }
    LaunchedEffect(ready?.correctFailed) {
        if (ready?.correctFailed != true) return@LaunchedEffect
        onAction(SaleDetailAction.CorrectShown)
        notices.showSnackbar(AppSnackbarVisuals(voidFailedText, SnackbarVariant.ERROR))
    }
    val returnVoidedText = stringResource(R.string.sale_return_voided)
    LaunchedEffect(ready?.returnVoided) {
        if (ready?.returnVoided != true) return@LaunchedEffect
        onAction(SaleDetailAction.ReturnVoidedShown)
        notices.showSnackbar(AppSnackbarVisuals(returnVoidedText, SnackbarVariant.SUCCESS))
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
                    actions = {
                        if (ready != null) {
                            // El texto solo de una venta activa; el comprobante en papel, también
                            // de una anulada, porque dice que se anuló.
                            val share = stringResource(R.string.action_share)
                            val receipt = stringResource(R.string.receipt_view_title)
                            OverflowMenu(
                                buildList {
                                    if (ready.sale.receipt != null) {
                                        add(
                                            OverflowAction(share, {
                                                onAction(SaleDetailAction.Share)
                                            }),
                                        )
                                    }
                                    add(
                                        OverflowAction(receipt, {
                                            onAction(SaleDetailAction.OpenReceipt)
                                        }),
                                    )
                                },
                            )
                        }
                    },
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
    ready?.returnSheet?.let { ReturnSheetContent(it, onAction) }
    if (ready?.correcting == true) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_correct_title),
            body = stringResource(R.string.dialog_correct_sale_body),
            confirmLabel = stringResource(R.string.dialog_correct_action),
            onConfirm = { onAction(SaleDetailAction.ConfirmCorrect) },
            onDismiss = { onAction(SaleDetailAction.DismissCorrect) },
            variant = ConfirmVariant.DESTRUCTIVE,
            dismissLabel = stringResource(R.string.action_back),
        )
    }
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
        if (sale.returns.isNotEmpty()) {
            item(key = RETURNS) { SectionHeader(stringResource(R.string.sale_return_list_title)) }
            items(sale.returns, key = { "return-${it.returnId}" }) { row ->
                ReturnRow(row, onAction)
            }
        }
        if (sale.canVoid || sale.canReturn) {
            item(key = ACTIONS) {
                Column(
                    Modifier.padding(horizontal = spacing.s16, vertical = spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(spacing.s12),
                ) {
                    // Con una devolución activa no se anula: lo que falta se devuelve.
                    if (sale.canReturn) {
                        PrimaryActionButton(
                            text = stringResource(
                                if (sale.hasActiveReturns) {
                                    R.string.sale_return_rest_action
                                } else {
                                    R.string.sale_return_action
                                },
                            ),
                            onClick = { onAction(SaleDetailAction.OpenReturn) },
                            variant = ActionVariant.OUTLINED,
                        )
                    }
                    if (sale.canVoid) {
                        PrimaryActionButton(
                            text = stringResource(R.string.dialog_correct_action),
                            onClick = { onAction(SaleDetailAction.OpenCorrect) },
                            variant = ActionVariant.OUTLINED,
                        )
                        PrimaryActionButton(
                            text = stringResource(R.string.dialog_void_sale_action),
                            onClick = { onAction(SaleDetailAction.OpenVoid) },
                            variant = ActionVariant.OUTLINED,
                        )
                    }
                }
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
 *
 * Si se cobró en otra moneda, debajo van lo entregado, en esa moneda y con su símbolo, y la
 * cotización que se escribió. El monto de arriba sigue en la moneda del negocio.
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
        payment.foreign?.let { ForeignRows(it, last = !payment.hasFee) }
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
        } else if (payment.foreign == null) {
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

/** Lo entregado en otra moneda y la cotización, como filas menores del pago. */
@Composable
private fun ForeignRows(foreign: ForeignTender, last: Boolean) {
    val formats = LocalAppFormats.current
    DetailSubRow(stringResource(R.string.checkout_fx_tendered, foreign.currency.code)) {
        ProvideAccountCurrency(AccountCurrency(foreign.currency, foreign.decimals)) {
            MoneyText(Money(foreign.amountMinor), MoneySize.CAPTION, tone = MoneyTone.MUTED)
        }
    }
    DetailSubRow(stringResource(R.string.checkout_fx_rate), last = last) {
        Text(
            stringResource(
                R.string.reference_rate_current,
                foreign.currency.code,
                formats.quantity.formatMicro(foreign.rateMicro),
            ),
            style = AppTheme.amounts.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
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

/**
 * Una línea vendida, con `cantidad × precio`, su total y el descuento si lo tuvo. Con devoluciones
 * activas, debajo va lo ya devuelto y lo que queda.
 */
@Composable
private fun LineRow(line: SaleDetailLine) {
    val unit = unitLabelOf(line.unit)
    val returned = line.returned > QuantityMilli.ZERO
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
            divider = line.discount == Money.ZERO && !returned,
        )
        if (returned) {
            val label = unit
            Text(
                text = stringResource(
                    R.string.sale_return_returned_and_left,
                    quantityText(line.returned, label),
                    quantityText(line.left, label),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = AppTheme.spacing.s16,
                    end = AppTheme.spacing.s16,
                    bottom = AppTheme.spacing.s8,
                ),
            )
            if (line.discount == Money.ZERO) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = AppTheme.spacing.s16),
                    thickness = AppTheme.sizes.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
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

/**
 * Una devolución en la lista: su número, el día en que cuenta y cómo se resolvió, y lo devuelto.
 * Anulada, el monto va tachado. Tocarla abre su hoja.
 */
@Composable
private fun ReturnRow(row: SaleReturnRow, onAction: (SaleDetailAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .clickable(role = Role.Button) {
                    onAction(SaleDetailAction.OpenReturnDetail(row.returnId))
                }
                .padding(horizontal = spacing.s16, vertical = spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(returnTitle(row), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(
                        R.string.sale_voided_on,
                        LocalAppFormats.current.date.longDate(row.date),
                        stringResource(resolutionLabel(row.resolution)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoneyText(row.total, MoneySize.ROW, voided = row.voided)
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * La hoja de una devolución: el día, cómo se resolvió, sus líneas con lo devuelto y si volvió al
 * inventario, el total y la nota, con `Anular devolución` abajo si está activa. Anularla pasa,
 * en la misma hoja, a pedir el motivo y la nota, como al anular la venta. Una devolución de un
 * cambio cuya venta nueva sigue activa no se anula desde aquí: una línea dice que se anula primero
 * esa venta.
 */
@Composable
private fun ReturnSheetContent(sheet: SaleReturnSheet, onAction: (SaleDetailAction) -> Unit) {
    val void = sheet.void
    AppBottomSheet(
        onDismissRequest = { onAction(SaleDetailAction.CloseReturnDetail) },
        title = if (void != null) {
            stringResource(R.string.sale_return_void_confirm_title)
        } else {
            returnTitle(sheet.row)
        },
        action = when {
            void != null -> {
                {
                    PrimaryActionButton(
                        text = stringResource(R.string.sale_return_void_action),
                        onClick = { onAction(SaleDetailAction.ConfirmReturnVoid) },
                        variant = ActionVariant.DESTRUCTIVE,
                        enabled = !void.noteTooLong,
                        state = if (sheet.voidStatus == VoidStatus.WORKING) {
                            ActionState.WORKING
                        } else {
                            ActionState.IDLE
                        },
                    )
                }
            }

            sheet.canVoid -> {
                {
                    PrimaryActionButton(
                        text = stringResource(R.string.sale_return_void_action),
                        onClick = { onAction(SaleDetailAction.OpenReturnVoid) },
                        variant = ActionVariant.OUTLINED,
                    )
                }
            }

            else -> null
        },
    ) {
        if (void != null) {
            ReturnVoidStep(void, sheet.voidStatus, onAction)
        } else {
            ReturnDetailStep(sheet)
        }
    }
}

/** El paso de anular una devolución: lo que pasará, el motivo, una nota opcional y si falló. */
@Composable
private fun ReturnVoidStep(
    void: VoidSheet,
    status: VoidStatus,
    onAction: (SaleDetailAction) -> Unit,
) {
    Text(
        stringResource(R.string.sale_return_void_confirm_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CompactChipRow(
        options = VoidSheet.RETURN_REASONS,
        label = { stringResource(reasonLabel(it)) },
        selected = setOf(void.reason),
        onToggle = { onAction(SaleDetailAction.ReturnVoidReasonChange(it)) },
        multiple = false,
    )
    AppTextField(
        value = void.note,
        onValueChange = { onAction(SaleDetailAction.ReturnVoidNoteChange(it)) },
        label = stringResource(R.string.form_note),
        singleLine = false,
        error =
            if (void.noteTooLong) {
                pluralStringResource(
                    R.plurals.error_text_long,
                    VoidSheet.MAX_VOID_NOTE_LENGTH,
                    VoidSheet.MAX_VOID_NOTE_LENGTH,
                )
            } else {
                null
            },
    )
    if (status == VoidStatus.FAILED) {
        NoticeCard(stringResource(R.string.error_sale_return_void), NoticeVariant.ERROR)
    }
    if (status == VoidStatus.LOT_USED) {
        NoticeCard(stringResource(R.string.error_void_lot_already_used), NoticeVariant.ERROR)
    }
}

/** El detalle de una devolución en su hoja, y por qué no se anula si es parte de un cambio. */
@Composable
private fun ReturnDetailStep(sheet: SaleReturnSheet) {
    val row = sheet.row
    val spacing = AppTheme.spacing
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        Text(
            text = stringResource(
                R.string.sale_voided_on,
                LocalAppFormats.current.date.longDate(row.date),
                stringResource(resolutionLabel(row.resolution)),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val lines = sheet.lines
        if (lines == null) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
        } else {
            for (line in lines) {
                val unit = unitLabelOf(line.unit)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s12),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(line.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = quantityText(line.quantity, unit),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!line.restock) {
                            Text(
                                text = stringResource(R.string.sale_return_no_restock_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    MoneyText(line.total, MoneySize.ROW)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sale_return_total_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            MoneyText(row.total, MoneySize.TITLE, voided = row.voided)
        }
        row.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        sheet.lockedBySale?.let { saleId ->
            val number = LocalAppFormats.current.quantity.format(
                QuantityMilli.ofUnits(saleId),
                allowsFraction = false,
            )
            Text(
                stringResource(R.string.sale_return_void_exchange_locked, number),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun returnTitle(row: SaleReturnRow): String = stringResource(
    R.string.sale_return_detail_title,
    LocalAppFormats.current.quantity.format(
        QuantityMilli.ofUnits(row.returnId),
        allowsFraction = false,
    ),
)

/** El nombre de cómo se resolvió una devolución. */
internal fun resolutionLabel(resolution: ReturnResolution): Int = when (resolution) {
    ReturnResolution.REFUND -> R.string.sale_return_resolution_refund
    ReturnResolution.STORE_CREDIT -> R.string.sale_return_resolution_store_credit
    ReturnResolution.EXCHANGE -> R.string.sale_return_resolution_exchange
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
private const val RETURNS = "returns"
