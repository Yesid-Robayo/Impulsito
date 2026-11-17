package io.github.yesidrobayo.shopledger.ui.sale

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartLineIssue
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LocationSwitcher
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PaymentMethodChips
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SaleProductTile
import io.github.yesidrobayo.shopledger.ui.components.SaleTileLine
import io.github.yesidrobayo.shopledger.ui.components.SayButton
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.media.ProductPhoto
import io.github.yesidrobayo.shopledger.ui.stock.locationChoices
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La venta nueva: la pantalla más importante de la app. Se vende con una mano y con alguien
 * esperando.
 *
 * - Arriba, `Más vendidos` en una cuadrícula que ocupa todo el alto. Abajo, un panel fijo que flota
 *   sobre ella con la búsqueda, los métodos de pago, el resumen y `Registrar venta`: al agregar el
 *   primer producto las tarjetas no se mueven bajo el dedo, porque la cuadrícula ya deja libre el
 *   alto del panel.
 * - Con letra de 150 % o más, o en una pantalla baja, el pago pasa a una fila que abre una hoja y la
 *   búsqueda sube bajo la barra. El botón nunca se oculta.
 * - Desde una ventana mediana, catálogo y venta van lado a lado: el panel derecho es la venta entera,
 *   siempre visible, y la cuadrícula muestra más columnas.
 * - Nunca muestra costo, ganancia ni margen: quien compra puede estar viendo la pantalla. Por eso el
 *   modo discreto no cambia nada aquí.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun NewSaleScreen(
    uiState: NewSaleUiState,
    onAction: (NewSaleAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notices = remember { SnackbarHostState() }
    val maxLines = stringResource(R.string.error_max_lines, Limit.DOCUMENT_LINES.max.toInt())
    val saveFailed = stringResource(R.string.state_error_save)
    val readFailed = stringResource(R.string.state_error_read)
    val leftOut = pluralStringResource(
        R.plurals.notice_fix_archived_products,
        uiState.leftOut,
        uiState.leftOut,
    )
    val notSold = pluralStringResource(
        R.plurals.notice_lines_not_sold,
        uiState.leftOut,
        uiState.leftOut,
    )
    LaunchedEffect(uiState.notice) {
        val notice = uiState.notice ?: return@LaunchedEffect
        val text = when (notice) {
            SaleNotice.LINES_FULL -> maxLines
            SaleNotice.REACTIVATE_FAILED -> saveFailed
            SaleNotice.LINES_LEFT_OUT -> leftOut
            SaleNotice.CORRECTION_FAILED -> readFailed
            SaleNotice.LINES_NOT_SOLD -> notSold
        }
        onAction(NewSaleAction.NoticeShown)
        notices.showSnackbar(AppSnackbarVisuals(text, SnackbarVariant.INFO))
    }
    val heldTexts = heldNoticeTexts(uiState.heldNotice)
    LaunchedEffect(uiState.heldNotice) {
        if (uiState.heldNotice == null) return@LaunchedEffect
        onAction(NewSaleAction.HeldNoticeShown)
        // Uno a la vez: con precios cambiados y productos fuera, se dicen los dos, en orden.
        heldTexts.forEach { (text, variant) ->
            notices.showSnackbar(AppSnackbarVisuals(text, variant))
        }
    }
    // El papel da también el color del texto: sin él, el texto sin color propio saldría en negro
    // sobre el papel oscuro.
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoPane = maxWidth >= MEDIUM_WIDTH
            val wide = maxWidth >= EXPANDED_WIDTH
            val compact = isLargeText() || maxHeight < SHORT_HEIGHT
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(titleOf(uiState.quote, uiState.order)),
                    navigation = TopBarNavigation.CLOSE,
                    onNavigate = { onAction(NewSaleAction.Close) },
                    navigationLabel = stringResource(R.string.a11y_close),
                    actions = {
                        ScreenHelpLink(HelpTopic.NEW_SALE)
                        SayButton()
                        HeldSaleActions(uiState, onAction)
                    },
                )
                uiState.exchange?.let { ExchangeBanner(it) }
                // Solo con más de una ubicación: con una sola, la venta no pregunta dónde.
                LocationSwitcher(
                    locations = locationChoices(uiState.cart.locations),
                    selectedId = uiState.cart.locationId,
                    onSelect = { id -> id?.let { onAction(NewSaleAction.SelectLocation(it)) } },
                    modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
                )
                if (twoPane) {
                    TwoPaneSale(uiState, onAction, wide, compact)
                } else {
                    SinglePaneSale(uiState, onAction, compact)
                }
            }
            SaleSheets(uiState, onAction, twoPane)
            AppSnackbarHost(
                notices,
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = AppTheme.sizes.topBar),
            )
        }
    }
    SaleDialogs(uiState, onAction)
}

/** Un teléfono: la cuadrícula a lo alto y el panel flotando encima, abajo. */
@Composable
private fun ColumnScope.SinglePaneSale(
    uiState: NewSaleUiState,
    onAction: (NewSaleAction) -> Unit,
    compact: Boolean,
) {
    val spacing = AppTheme.spacing
    if (compact) {
        SaleSearch(uiState, onAction, Modifier.padding(horizontal = spacing.s16))
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        var panelHeight by remember { mutableIntStateOf(0) }
        // Sin productos no hay nada que buscar ni que cobrar: el estado vacío ocupa todo.
        val showPanel = uiState.content != SaleContent.EmptyCatalog || !uiState.cart.isEmpty
        val panel = if (showPanel) with(LocalDensity.current) { panelHeight.toDp() } else 0.dp
        ProductArea(
            uiState = uiState,
            onAction = onAction,
            maxColumns = PHONE_COLUMNS,
            bottomPadding = panel + spacing.s16,
        )
        if (showPanel) {
            SalePanel(
                uiState = uiState,
                onAction = onAction,
                compact = compact,
                withSearch = !compact,
                opensCart = true,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { panelHeight = it.height }
                        .keepsMessagesAbove(),
            )
        }
    }
}

/**
 * Una tableta o un plegable abierto: catálogo a la izquierda y la venta entera a la derecha. En
 * una ventana baja, como un teléfono acostado, el panel de cobro va [compact], como con letra
 * grande.
 */
@Composable
private fun ColumnScope.TwoPaneSale(
    uiState: NewSaleUiState,
    onAction: (NewSaleAction) -> Unit,
    wide: Boolean,
    compact: Boolean,
) {
    val spacing = AppTheme.spacing
    Row(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SaleSearch(uiState, onAction, Modifier.padding(horizontal = spacing.s16))
            Box(Modifier.weight(1f)) {
                ProductArea(
                    uiState = uiState,
                    onAction = onAction,
                    maxColumns = TABLET_COLUMNS,
                    bottomPadding = spacing.s16,
                )
            }
        }
        VerticalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        val pane =
            if (wide) Modifier.width(AppTheme.sizes.sideSheet) else Modifier.weight(PANE_WEIGHT)
        Surface(
            pane.fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.s16, vertical = spacing.s8),
                ) {
                    CartContent(uiState.cart, onAction)
                }
                SalePanel(
                    uiState,
                    onAction,
                    compact = compact,
                    withSearch = false,
                    opensCart = false,
                    modifier = Modifier.keepsMessagesAbove(),
                )
            }
        }
    }
}

@Composable
private fun SaleSearch(
    uiState: NewSaleUiState,
    onAction: (NewSaleAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.content == SaleContent.EmptyCatalog) return
    SearchField(
        query = uiState.query,
        onQueryChange = { onAction(NewSaleAction.QueryChange(it)) },
        placeholder = stringResource(R.string.sale_search_hint),
        modifier = modifier.padding(vertical = AppTheme.spacing.s8),
        onSubmit = { onAction(NewSaleAction.SubmitSearch) },
        onScan = if (uiState.canScan) ({ onAction(NewSaleAction.Scan) }) else null,
    )
}

/**
 * El panel fijo de abajo: la búsqueda al alcance del pulgar y, con productos, el método de pago, el
 * resumen y `Registrar venta`. Sin productos solo muestra la búsqueda: no hay nada que confirmar.
 */
@Composable
private fun SalePanel(
    uiState: NewSaleUiState,
    onAction: (NewSaleAction) -> Unit,
    compact: Boolean,
    withSearch: Boolean,
    opensCart: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val cart = uiState.cart
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            HorizontalDivider(
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(
                Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = spacing.s16, vertical = spacing.s8),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                if (withSearch) {
                    SearchField(
                        query = uiState.query,
                        onQueryChange = { onAction(NewSaleAction.QueryChange(it)) },
                        placeholder = stringResource(R.string.sale_search_hint),
                        onSubmit = { onAction(NewSaleAction.SubmitSearch) },
                        onScan = if (uiState.canScan) ({ onAction(NewSaleAction.Scan) }) else null,
                    )
                }
                if (!cart.isEmpty) {
                    val quoting = uiState.quote != null
                    val ordering = uiState.order != null
                    val delivery = uiState.delivery
                    val primary = when {
                        quoting -> NewSaleAction.OpenSaveQuote

                        ordering -> NewSaleAction.OpenSaveOrder

                        // El envío y el saldo a favor de una entrega se ven y se cobran en el cobro.
                        delivery?.needsCheckout == true -> NewSaleAction.OpenCheckout

                        else -> NewSaleAction.Register
                    }
                    // Una cotización o un pedido no se cobran: sin métodos ni más opciones de cobro.
                    if (!quoting && !ordering) PaymentChoice(uiState.payment, onAction, compact)
                    SaleSummary(cart, onAction, opensCart)
                    val exchange = uiState.exchange
                    when {
                        quoting || ordering -> Unit
                        exchange == null -> CheckoutActions(uiState, onAction)
                        else -> exchange.difference?.let { ExchangeDifferenceText(it) }
                    }
                    if (uiState.status == RegisterStatus.FAILED) {
                        NoticeCard(
                            text = stringResource(R.string.error_sale_register),
                            variant = NoticeVariant.ERROR,
                            actionLabel = stringResource(R.string.action_retry),
                            onAction = { onAction(NewSaleAction.Register) },
                        )
                    }
                    PrimaryActionButton(
                        text = stringResource(
                            when {
                                quoting -> R.string.quote_save_action
                                ordering -> R.string.order_save_action
                                delivery != null -> R.string.order_deliver_action
                                uiState.exchange == null -> R.string.sale_register
                                else -> R.string.sale_return_exchange_register_action
                            },
                        ),
                        onClick = { onAction(primary) },
                        variant = ActionVariant.PRIMARY,
                        enabled = cart.canRegister,
                        state =
                            if (uiState.status == RegisterStatus.WORKING) {
                                ActionState.WORKING
                            } else {
                                ActionState.IDLE
                            },
                    )
                }
            }
        }
    }
}

/**
 * Los métodos de pago: chips que se desplazan, o una fila `Pago: Efectivo` que abre una hoja cuando
 * la letra es grande.
 */
@Composable
private fun PaymentChoice(
    payment: SalePaymentState,
    onAction: (NewSaleAction) -> Unit,
    compact: Boolean,
) {
    val selected = payment.selected ?: return
    if (compact) {
        val text = stringResource(R.string.sale_payment_row, methodName(selected))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .clickable(role = Role.Button) { onAction(NewSaleAction.OpenPaymentMethods) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        PaymentMethodChips(
            methods = methodOptions(payment.methods),
            selectedId = selected.id,
            onSelect = { onAction(NewSaleAction.SelectMethod(it)) },
        )
    }
}

/**
 * `2 productos ^` y el total: tocarlo abre las líneas. Un total que no se puede confirmar se marca
 * con su motivo, sin perder nada de lo elegido.
 */
@Composable
private fun SaleSummary(
    cart: SaleCartState,
    onAction: (NewSaleAction) -> Unit,
    opensCart: Boolean,
) {
    val count = pluralStringResource(
        R.plurals.sale_summary_products,
        cart.itemCount.toInt(),
        cart.itemCount.toInt(),
    )
    val total = cart.total?.let { rememberMoneyDisplay(it, LocalAppFormats.current.money) }
    val totalText = total?.let { stringResource(R.string.a11y_sale_total, it.description) }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        val description = listOfNotNull(count, totalText).joinToString(", ")
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .then(
                    if (opensCart) {
                        Modifier
                            .clickable(role = Role.Button) { onAction(NewSaleAction.OpenCart) }
                            .clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = description
                                onClick(label = count) {
                                    onAction(NewSaleAction.OpenCart)
                                    true
                                }
                            }
                    } else {
                        Modifier.clearAndSetSemantics { contentDescription = description }
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            // El total se mide primero y nunca se corta: el conteo usa lo que queda y baja de
            // línea si no cabe, con letra grande o en un idioma más largo.
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
            ) {
                Text(
                    count,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (opensCart) {
                    Icon(
                        painter = painterResource(R.drawable.ic_keyboard_arrow_up),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppTheme.sizes.icon),
                    )
                }
            }
            MoneyText(cart.total, MoneySize.TITLE)
        }
        problemOf(cart)?.let { problem ->
            Text(
                text = problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * `Más opciones de cobro`, que abre el cobro, y `Mostrar QR` si el método elegido tiene imagen o
 * instrucciones de cobro. Nunca se interponen: `Registrar venta` sigue confirmando directo. Solo
 * aparecen cuando la venta se puede cobrar.
 */
@Composable
private fun CheckoutActions(uiState: NewSaleUiState, onAction: (NewSaleAction) -> Unit) {
    if (!uiState.cart.canRegister) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineTextButton(
            text = stringResource(R.string.checkout_more_options_action),
            onClick = { onAction(NewSaleAction.OpenCheckout) },
        )
        if (uiState.payment.selected?.hasQr == true) {
            InlineTextButton(
                text = stringResource(R.string.checkout_show_qr_action),
                onClick = { onAction(NewSaleAction.ShowQr) },
            )
        }
    }
}

/**
 * La banda fija de un cambio, bajo la barra: de qué venta viene y el crédito de lo devuelto, que
 * paga la venta nueva.
 */
@Composable
private fun ExchangeBanner(band: ExchangeBand) {
    val formats = LocalAppFormats.current
    val number = formats.quantity.format(QuantityMilli.ofUnits(band.saleId), allowsFraction = false)
    NoticeCard(
        text = stringResource(
            R.string.sale_return_exchange_banner,
            number,
            rememberMoneyDisplay(band.credit, formats.money).text.text,
        ),
        variant = NoticeVariant.INFO,
        modifier = Modifier.padding(
            start = AppTheme.spacing.s16,
            end = AppTheme.spacing.s16,
            bottom = AppTheme.spacing.s8,
        ),
    )
}

/** Lo que queda del cambio: `Por cobrar`, `Por devolver` o `Sin diferencia`. */
@Composable
private fun ExchangeDifferenceText(difference: ExchangeDifference) {
    val money = LocalAppFormats.current.money
    val text = when (difference) {
        is ExchangeDifference.ToCollect -> stringResource(
            R.string.sale_return_exchange_to_collect,
            rememberMoneyDisplay(difference.amount, money).text.text,
        )

        is ExchangeDifference.ToRefund -> stringResource(
            R.string.sale_return_exchange_to_refund,
            rememberMoneyDisplay(difference.amount, money).text.text,
        )

        ExchangeDifference.None -> stringResource(R.string.sale_return_exchange_no_difference)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.End,
    )
}

/** Por qué la venta no se puede confirmar todavía, o `null` si nada lo impide. */
@Composable
private fun problemOf(cart: SaleCartState): String? = when {
    cart.lines.any { it.issue == CartLineIssue.INVALID_DISCOUNT } ->
        stringResource(R.string.error_discount_over_line)

    cart.total == null -> stringResource(R.string.error_value_high)

    else -> null
}

/** La cuadrícula y sus estados: cargando, vacío, sin resultados, error y los productos. */
@Composable
private fun ProductArea(
    uiState: NewSaleUiState,
    onAction: (NewSaleAction) -> Unit,
    maxColumns: Int,
    bottomPadding: Dp,
) {
    when (val content = uiState.content) {
        SaleContent.Loading -> DelayedSkeleton { SkeletonGrid(maxColumns) }

        SaleContent.EmptyCatalog -> CenteredState(bottomPadding) {
            EmptyState(
                icon = R.drawable.ic_inventory_2,
                title = stringResource(R.string.empty_sale_title),
                body = stringResource(R.string.empty_sale_body),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.empty_sale_action),
                    onClick = { onAction(NewSaleAction.CreateProduct()) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        SaleContent.ReadError -> CenteredState(bottomPadding) {
            EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.state_error_read),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(NewSaleAction.RetryRead) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        is SaleContent.NoResults -> CenteredState(bottomPadding) {
            EmptyState(
                icon = R.drawable.ic_search,
                title = stringResource(R.string.empty_search_title, content.query),
                body = stringResource(R.string.empty_search_body),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.empty_search_action),
                    onClick = { onAction(NewSaleAction.CreateProduct(content.query)) },
                    variant = ActionVariant.TONAL,
                )
            }
            ArchivedMatches(content.archived, onAction)
        }

        is SaleContent.Products -> ProductGrid(content, onAction, maxColumns, bottomPadding)
    }
}

@Composable
private fun ProductGrid(
    content: SaleContent.Products,
    onAction: (NewSaleAction) -> Unit,
    maxColumns: Int,
    bottomPadding: Dp,
) {
    val spacing = AppTheme.spacing
    // Otra búsqueda empieza arriba; un cambio de la venta con la misma búsqueda conserva el lugar.
    val gridState =
        rememberSaveable(content.query, content.searching, saver = LazyGridState.Saver) {
            LazyGridState()
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = tileColumns(maxWidth - spacing.s16 * 2, maxColumns)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = spacing.s16,
                    end = spacing.s16,
                    top = spacing.s8,
                    bottom = bottomPadding,
                ),
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            if (!content.searching) {
                item(key = HEADER, span = { GridItemSpan(maxLineSpan) }, contentType = HEADER) {
                    SectionHeader(
                        stringResource(R.string.sale_frequent),
                        Modifier.fullBleed(spacing.s16),
                    )
                }
            }
            items(content.tiles, key = { it.productId }, contentType = { TILE }) { tile ->
                ProductTile(tile, onAction)
            }
            if (content.archived.isNotEmpty()) {
                item(key = ARCHIVED, span = { GridItemSpan(maxLineSpan) }, contentType = ARCHIVED) {
                    ArchivedMatches(content.archived, onAction)
                }
            }
        }
    }
}

@Composable
private fun ProductTile(tile: SaleTile, onAction: (NewSaleAction) -> Unit) {
    val unit = unitLabelOf(tile.unit)
    val line = tile.line
    SaleProductTile(
        name = tile.name,
        price = tile.price,
        stockText = stockText(tile.stock, unit),
        unit = unit,
        onClick = { onAction(NewSaleAction.AddProduct(tile.productId)) },
        photo = tile.photoMediaId?.let { id -> { ProductPhoto(id) } },
        line =
            line?.let {
                SaleTileLine(
                    quantity = it.quantity,
                    unit = it.unit?.let { lineUnit -> unitLabelOf(lineUnit) },
                    onIncrement = { onAction(NewSaleAction.Increment(tile.productId)) },
                    onDecrement = { onAction(NewSaleAction.Decrement(tile.productId)) },
                    removesLine = it.removesLine,
                    canIncrement = it.canIncrement,
                    onQuantityClick =
                        if (unit.allowsFraction) {
                            { onAction(NewSaleAction.EditQuantity(tile.productId)) }
                        } else {
                            null
                        },
                    oversellBy = it.oversell,
                )
            },
    )
}

/**
 * Productos archivados que coinciden con la búsqueda: no se venden, pero se pueden reactivar aquí
 * mismo y seguir vendiendo.
 */
@Composable
private fun ArchivedMatches(archived: List<ArchivedMatch>, onAction: (NewSaleAction) -> Unit) {
    if (archived.isEmpty()) return
    val spacing = AppTheme.spacing
    Column(Modifier.fillMaxWidth().padding(top = spacing.s8)) {
        SectionHeader(
            stringResource(R.string.product_filter_archived),
            Modifier.fullBleed(spacing.s16),
        )
        archived.forEach { match ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRowSingle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.s4),
                ) {
                    Text(
                        match.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusTag(stringResource(R.string.status_archived))
                }
                InlineTextButton(
                    text = stringResource(R.string.action_reactivate),
                    onClick = { onAction(NewSaleAction.Reactivate(match.productId)) },
                )
            }
        }
    }
}

/**
 * Lleva un encabezado hasta el borde de la pantalla: dentro de la cuadrícula, que deja [margin] a
 * cada lado para las tarjetas, el encabezado ya trae su propio margen y quedaría corrido.
 */
private fun Modifier.fullBleed(margin: Dp): Modifier = layout { measurable, constraints ->
    val extra = margin.roundToPx() * 2
    val wide = constraints.copy(
        minWidth = constraints.minWidth + extra,
        maxWidth = constraints.maxWidth + extra,
    )
    val placeable = measurable.measure(wide)
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-margin.roundToPx(), 0)
    }
}

/** Seis tarjetas de esqueleto con la forma de la cuadrícula. */
@Composable
private fun SkeletonGrid(maxColumns: Int) {
    val spacing = AppTheme.spacing
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = tileColumns(maxWidth - spacing.s16 * 2, maxColumns)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
            userScrollEnabled = false,
        ) {
            items(SKELETON_TILES) {
                SkeletonBlock(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppTheme.sizes.saleTile),
                )
            }
        }
    }
}

/** Un estado que ocupa el área de productos: arriba, desplazable con letra grande. */
@Composable
private fun CenteredState(bottomPadding: Dp, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = AppTheme.spacing.s16,
                end = AppTheme.spacing.s16,
                bottom = bottomPadding,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/**
 * Cuántas columnas caben en [width]: cada tarjeta necesita el ancho de su selector de cantidad, que
 * se mide con la letra real, más su relleno. Con letra grande o en un teléfono angosto queda una
 * sola columna, como pide la guía de accesibilidad.
 */
@Composable
private fun tileColumns(width: Dp, maxColumns: Int): Int {
    val measurer = rememberTextMeasurer()
    val style = AppTheme.amounts.row
    val spacing = AppTheme.spacing
    val sizes = AppTheme.sizes
    val density = LocalDensity.current
    val value = with(density) { measurer.measure(STEPPER_SAMPLE, style).size.width.toDp() }
    val stepper = sizes.touchTarget * 2 + spacing.s8 * 2 + maxOf(value, sizes.stepperValue)
    val tile = stepper + spacing.s12 * 2
    val fits = ((width + spacing.s12) / (tile + spacing.s12)).toInt()
    return fits.coerceIn(1, maxColumns)
}

// Las clases de ventana: desde 600 dp la venta va en dos paneles, y desde 840 dp el panel derecho
// tiene el ancho de una hoja lateral. Una pantalla más baja que esto pasa al modo compacto.
private val MEDIUM_WIDTH = 600.dp
private val EXPANDED_WIDTH = 840.dp
private val SHORT_HEIGHT = 480.dp

/** El panel derecho de una ventana mediana, frente a la cuadrícula. */
private const val PANE_WEIGHT = 0.72f

/** Las columnas de la cuadrícula: dos en un teléfono, hasta cuatro en una tableta. */
private const val PHONE_COLUMNS = 2
private const val TABLET_COLUMNS = 4

/** Una cantidad típica del selector, para medir cuánto ocupa con la letra de ahora. */
private const val STEPPER_SAMPLE = "000"

private const val SKELETON_TILES = 6
private const val HEADER = "header"
private const val TILE = "tile"
private const val ARCHIVED = "archived"

/**
 * Lo que la barra ofrece de la espera: `En espera: 2` si hay otras ventas guardadas, y el menú con
 * `Poner en espera` si la venta tiene algo que guardar.
 *
 * `En espera: 2` es un botón de texto, el mismo de las acciones secundarias: se lee entero, con la
 * palabra que fija la guía, y no compite con `Registrar venta`, que es la única acción fuerte aquí.
 */
@Composable
private fun HeldSaleActions(uiState: NewSaleUiState, onAction: (NewSaleAction) -> Unit) {
    // Un cambio no se pone en espera: su devolución vive en la pantalla de debajo. Una cotización
    // o un pedido tampoco: ya se guardan como tales. La entrega de un pedido no se guarda aparte:
    // perdería el pedido que entrega.
    if (uiState.exchange != null || uiState.quote != null) return
    if (uiState.order != null || uiState.delivery != null) return
    if (uiState.heldCount > 0) {
        InlineTextButton(
            text = stringResource(R.string.draft_badge, uiState.heldCount),
            onClick = { onAction(NewSaleAction.OpenHeldSales) },
        )
    }
    if (!uiState.cart.isEmpty) {
        OverflowMenu(
            listOf(
                OverflowAction(
                    label = stringResource(R.string.draft_hold_action),
                    onClick = { onAction(NewSaleAction.OpenHold) },
                ),
                OverflowAction(
                    label = stringResource(R.string.quote_save_from_sale_action),
                    onClick = { onAction(NewSaleAction.OpenSaveQuote) },
                ),
                OverflowAction(
                    label = stringResource(R.string.order_save_from_sale_action),
                    onClick = { onAction(NewSaleAction.OpenSaveOrder) },
                ),
            ),
        )
    }
}

/** El título de la pantalla: la venta, la cotización o el pedido, nuevos o editados. */
@StringRes
private fun titleOf(quote: QuoteEditing?, order: OrderEditing?): Int = when {
    quote != null && quote.editingId == null -> R.string.quote_new_title
    quote != null -> R.string.quote_edit_title
    order != null && order.editingId == null -> R.string.order_new_title
    order != null -> R.string.order_edit_title
    else -> R.string.sale_new_title
}

/** Los mensajes de [notice], en orden, con su tipo. */
@Composable
private fun heldNoticeTexts(notice: HeldNotice?): List<Pair<String, SnackbarVariant>> =
    when (notice) {
        null -> emptyList()

        HeldNotice.Held ->
            listOf(stringResource(R.string.draft_saved) to SnackbarVariant.SUCCESS)

        HeldNotice.HoldFailed ->
            listOf(stringResource(R.string.draft_error_hold) to SnackbarVariant.INFO)

        HeldNotice.ResumeFailed ->
            listOf(stringResource(R.string.draft_error_resume) to SnackbarVariant.INFO)

        is HeldNotice.Resumed -> buildList {
            if (notice.changedPrices > 0) {
                val text = pluralStringResource(
                    R.plurals.draft_prices_changed,
                    notice.changedPrices,
                    notice.changedPrices,
                )
                add(text to SnackbarVariant.INFO)
            }
            if (notice.removed > 0) {
                val text = pluralStringResource(
                    R.plurals.draft_products_removed,
                    notice.removed,
                    notice.removed,
                )
                add(text to SnackbarVariant.INFO)
            }
        }
    }
