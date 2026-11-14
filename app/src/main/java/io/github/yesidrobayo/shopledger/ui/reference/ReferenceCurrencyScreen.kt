package io.github.yesidrobayo.shopledger.ui.reference

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ReferenceRateRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ObserveReferenceRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ObserveReferencedProductsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.RecordReferenceRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetReferenceCurrencyUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PickerField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.RateField
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.region.IcuRegionalCatalog
import io.github.yesidrobayo.shopledger.ui.region.RegionalOption
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlinx.coroutines.flow.map

/**
 * La moneda de referencia en la navegación. La lista de monedas, la hoja de registrar y el
 * diálogo de cambiar se cierran con atrás antes que la pantalla.
 */
@Composable
fun ReferenceCurrencyRoute(navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val symbols = formats.quantity.inputSymbols
    val model = rememberAppViewModel(key = "reference-currency") { deps ->
        val ports = deps.ports
        val clock = deps.container.clock
        val observe = ObserveReferenceRateUseCase(ports.settings, ports.fxRates)
        val set = SetReferenceCurrencyUseCase(ports.transactions, ports.settings, clock)
        val record =
            RecordReferenceRateUseCase(ports.transactions, ports.settings, ports.fxRates, clock)
        val calendar = BusinessCalendarSource(ports.settings)
        ReferenceCurrencyViewModel(
            observe = { observe() },
            calendar = {
                calendar.observe().map { result ->
                    when (result) {
                        is BusinessCalendarResult.Ready -> RateCalendar(
                            result.calendar.stamp(clock.now()).businessDate,
                            result.calendar.weekStartDay,
                        )

                        is BusinessCalendarResult.UnknownZone -> null
                    }
                }
            },
            setReference = { set(it) },
            record = { rate, note, date -> record(rate, note, date) },
            symbols = symbols,
            referencedProducts = { ObserveReferencedProductsUseCase(ports.products)() },
        )
    }
    when (model) {
        ScreenModel.Opening -> ReferenceCurrencyScreen(
            ReferenceCurrencyUiState.Loading,
            emptyList(),
        ) {
        }

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.ReferenceCurrency, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val uiLocale = formats.uiLocale
            val currencies = remember(uiLocale) { IcuRegionalCatalog().currencies(uiLocale) }
            val ready = uiState as? ReferenceCurrencyUiState.Ready
            BackHandler(enabled = ready?.choosing == true || ready?.sheet != null) {
                viewModel.onAction(ReferenceCurrencyAction.DismissDialog)
            }
            ShowMessage(ready?.message) { viewModel.onAction(ReferenceCurrencyAction.MessageShown) }
            ReferenceCurrencyScreen(uiState, currencies) { action ->
                when (action) {
                    ReferenceCurrencyAction.Back -> navigator.back()

                    // Repreciar solo prepara la vista previa: nada cambia hasta aplicarla.
                    ReferenceCurrencyAction.Reprice ->
                        navigator.open(AppNavKey.BulkPriceUpdate(referenceRate = true))

                    else -> viewModel.onAction(action)
                }
            }
        }
    }
}

/**
 * La moneda de referencia, sin ViewModel: la moneda y sus decimales, la cotización vigente, el aviso
 * de que se escribe a mano, y el historial, de la más reciente a la más vieja. La acción fija es
 * `Registrar cotización`.
 *
 * **Nada se descarga ni se recalcula solo.** Sin moneda, un estado vacío lleva a elegirla; sin
 * cotización, lo dice y nunca supone un valor. Una cotización no revela costos ni ganancias, así que
 * el modo discreto no la oculta.
 *
 * @param currencies las monedas que se ofrecen, por nombre en el idioma de la interfaz. La del
 *   negocio no se ofrece: la referencia es otra moneda.
 */
@Composable
fun ReferenceCurrencyScreen(
    uiState: ReferenceCurrencyUiState,
    currencies: List<RegionalOption>,
    modifier: Modifier = Modifier,
    onAction: (ReferenceCurrencyAction) -> Unit,
) {
    val ready = uiState as? ReferenceCurrencyUiState.Ready
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.reference_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(ReferenceCurrencyAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
            )
            Column(
                Modifier
                    .weight(1f)
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth(),
            ) {
                when (uiState) {
                    ReferenceCurrencyUiState.Loading -> LoadingSkeleton()

                    ReferenceCurrencyUiState.ReadFailed -> ReadFailed(onAction)

                    is ReferenceCurrencyUiState.Ready ->
                        if (uiState.reference == null) {
                            NoReference(onAction)
                        } else {
                            ReferenceBody(uiState, uiState.reference, onAction)
                        }
                }
            }
            if (ready?.reference != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .keepsMessagesAbove()
                        .navigationBarsPadding()
                        .padding(AppTheme.spacing.s16),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.reference_register_action),
                        onClick = { onAction(ReferenceCurrencyAction.StartRate) },
                        variant = ActionVariant.PRIMARY,
                        modifier = Modifier.widthIn(
                            max = AppTheme.sizes.maxContentWidth,
                        ).fillMaxWidth(),
                    )
                }
            }
        }
    }
    if (ready != null) {
        val business = ready.reference
        if (ready.choosing) CurrencySheet(currencies, ready.query, business, onAction)
        ready.pendingChange?.let { ConfirmChange(it, onAction) }
        ready.sheet?.let { RateSheetHost(ready, it, onAction) }
    }
}

@Composable
private fun LoadingSkeleton() {
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

@Composable
private fun ReadFailed(onAction: (ReferenceCurrencyAction) -> Unit) {
    EmptyState(icon = R.drawable.ic_error, title = stringResource(R.string.state_error_read)) {
        PrimaryActionButton(
            text = stringResource(R.string.action_retry),
            onClick = { onAction(ReferenceCurrencyAction.Retry) },
            variant = ActionVariant.TONAL,
        )
    }
}

@Composable
private fun NoReference(onAction: (ReferenceCurrencyAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16)) {
        EmptyState(
            icon = R.drawable.ic_trending_up,
            title = stringResource(R.string.reference_empty_title),
            body = manualNotice(),
        ) {
            PrimaryActionButton(
                text = stringResource(R.string.reference_empty_action),
                onClick = { onAction(ReferenceCurrencyAction.ChooseCurrency) },
                variant = ActionVariant.PRIMARY,
            )
        }
    }
}

/** El aviso fijo: la cotización se escribe a mano, y nada la descarga ni cambia precios solo. */
@Composable
private fun manualNotice(): String =
    stringResource(R.string.reference_manual_notice, stringResource(R.string.app_name))

/** La moneda, la cotización vigente, el aviso y el historial, en una lista perezosa. */
@Composable
private fun ReferenceBody(
    state: ReferenceCurrencyUiState.Ready,
    reference: ReferenceCurrency,
    onAction: (ReferenceCurrencyAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val rows = Modifier.padding(horizontal = spacing.s16)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        item(key = "currency") {
            Column(rows, verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                PickerField(
                    label = stringResource(R.string.reference_currency_label),
                    value = reference.code.code,
                    onClick = { onAction(ReferenceCurrencyAction.ChooseCurrency) },
                )
                Text(
                    stringResource(R.string.reference_decimals_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompactChipRow(
                    options = (0..ReferenceRateRules.MAX_DECIMALS).toList(),
                    label = { LocalAppFormats.current.quantity.formatCount(it.toLong()) },
                    selected = setOf(reference.decimals),
                    onToggle = { onAction(ReferenceCurrencyAction.PickDecimals(it)) },
                    multiple = false,
                )
            }
        }
        item(key = "current") { CurrentRate(state.latest, reference, rows) }
        item(key = "notice") {
            NoticeCard(manualNotice(), NoticeVariant.INFO, rows)
        }
        if (state.repriceCount > 0) {
            item(key = "reprice") {
                NoticeCard(
                    text = pluralStringResource(
                        R.plurals.reference_products_notice,
                        state.repriceCount,
                        LocalAppFormats.current.quantity.formatCount(state.repriceCount.toLong()),
                    ),
                    variant = NoticeVariant.INFO,
                    modifier = rows,
                    actionLabel = stringResource(R.string.reference_reprice_action),
                    onAction = { onAction(ReferenceCurrencyAction.Reprice) },
                )
            }
        }
        if (state.history.isNotEmpty()) {
            item(key = "history") {
                SectionHeader(stringResource(R.string.reference_history_title))
            }
            items(state.history, key = { it.rate.id }) { row -> RateRowView(row, reference, rows) }
        }
    }
}

/** `1 XXX = 4.150,5` y `Vigente desde el 28 oct`, o que todavía no hay cotización. */
@Composable
private fun CurrentRate(latest: RateRow?, reference: ReferenceCurrency, modifier: Modifier) {
    if (latest == null) {
        EmptyStateInline(
            title = stringResource(R.string.reference_no_rate_title),
            modifier = modifier,
        )
        return
    }
    val formats = LocalAppFormats.current
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(
            stringResource(
                R.string.reference_rate_current,
                reference.code.code,
                formats.quantity.formatMicro(latest.rate.content.rateMicro),
            ),
            style = AppTheme.amounts.tile,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(
                R.string.reference_rate_since,
                formats.date.dayMonthYear(latest.rate.content.businessDate),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Una cotización del historial: fecha y nota a la izquierda, cotización y variación a la derecha. */
@Composable
private fun RateRowView(row: RateRow, reference: ReferenceCurrency, modifier: Modifier) {
    val formats = LocalAppFormats.current
    val content = row.rate.content
    Row(
        modifier.fillMaxWidth().heightIn(
            min = AppTheme.sizes.listRow,
        ).semantics(mergeDescendants = true) {
        },
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                formats.date.dayMonthYear(content.businessDate),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(
                    R.string.reference_rate_current,
                    reference.code.code,
                    formats.quantity.formatMicro(content.rateMicro),
                ),
                style = AppTheme.amounts.row,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
            row.variation?.let {
                Text(
                    formats.percent.formatChange(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** La lista de monedas, sin la del negocio, con búsqueda por código o nombre. */
@Composable
private fun CurrencySheet(
    currencies: List<RegionalOption>,
    query: String,
    current: ReferenceCurrency?,
    onAction: (ReferenceCurrencyAction) -> Unit,
) {
    val formats = LocalAppFormats.current
    val business = formats.money.currencyCode.code
    val catalog = remember { IcuRegionalCatalog() }
    val folded = TextNormalizer.normalize(query.trim())
    val shown = currencies.filter {
        it.key != business &&
            (folded.isEmpty() || folded in it.searchKey)
    }
    SelectionSheet(
        title = stringResource(R.string.reference_currency_label),
        items = shown,
        key = { it.key },
        query = query,
        onQueryChange = { onAction(ReferenceCurrencyAction.Query(it)) },
        onDismissRequest = { onAction(ReferenceCurrencyAction.DismissDialog) },
        searchHint = stringResource(R.string.reference_search_hint),
        itemContent = { option ->
            val decimals = CurrencyCode.of(option.key)
                ?.let(catalog::isoDecimals) ?: current?.decimals ?: DEFAULT_DECIMALS
            CurrencyOptionRow(option) {
                onAction(ReferenceCurrencyAction.PickCurrency(option.key, decimals))
            }
        },
        resultsCount = R.plurals.a11y_search_results_currencies,
    )
}

@Composable
private fun CurrencyOptionRow(option: RegionalOption, onClick: () -> Unit) {
    SettingsRow(
        title = option.title,
        action = SettingsRowAction.Navigation(onClick),
        summary = option.detail,
    )
}

@Composable
private fun ConfirmChange(pending: ReferenceCurrency, onAction: (ReferenceCurrencyAction) -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.reference_change_confirm_title),
        body = stringResource(R.string.reference_change_confirm_body),
        confirmLabel = stringResource(R.string.reference_change_confirm_action),
        onConfirm = { onAction(ReferenceCurrencyAction.ConfirmChange) },
        onDismiss = { onAction(ReferenceCurrencyAction.DismissDialog) },
    )
}

/**
 * La hoja de `Registrar cotización`: la cotización con hasta seis decimales, la fecha, hoy por
 * defecto, y la nota. Debajo dice la anterior y la variación, y avisa sin bloquear un salto de más
 * de la mitad. Una falla se dice en la hoja y lo escrito se conserva.
 */
@Composable
private fun RateSheetHost(
    state: ReferenceCurrencyUiState.Ready,
    sheet: RateSheet,
    onAction: (ReferenceCurrencyAction) -> Unit,
) {
    val reference = state.reference ?: return
    val formats = LocalAppFormats.current
    AppBottomSheet(
        onDismissRequest = { onAction(ReferenceCurrencyAction.DismissDialog) },
        title = stringResource(R.string.reference_register_action),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.reference_register_action),
                onClick = { onAction(ReferenceCurrencyAction.SaveRate) },
                variant = ActionVariant.PRIMARY,
                state = if (sheet.saving) ActionState.WORKING else ActionState.IDLE,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        RateField(
            value = sheet.rateText,
            onValueChange = { onAction(ReferenceCurrencyAction.RateText(it)) },
            label = stringResource(R.string.reference_rate_label),
            prefix = stringResource(R.string.reference_rate_prefix, reference.code.code),
            error = sheet.problem?.let { rateProblemText(it) },
        )
        val calendar = state.calendar
        if (calendar != null) {
            DateField(
                label = stringResource(R.string.reference_date_label),
                date = sheet.date,
                onDateChange = { onAction(ReferenceCurrencyAction.RateDate(it)) },
                today = calendar.today,
                weekStartDay = calendar.weekStartDay,
                error = if (sheet.futureDate) {
                    stringResource(
                        R.string.reference_error_future,
                    )
                } else {
                    null
                },
            )
        }
        AppTextField(
            value = sheet.note,
            onValueChange = { onAction(ReferenceCurrencyAction.RateNote(it)) },
            label = stringResource(R.string.reference_note_label),
            supportingText = stringResource(R.string.reference_note_hint),
            error = if (sheet.noteTooLong) {
                pluralStringResource(
                    R.plurals.error_text_long,
                    ReferenceRateRules.NOTE_MAX,
                    ReferenceRateRules.NOTE_MAX,
                )
            } else {
                null
            },
        )
        Previous(state.latest, sheet, reference)
        if (sheet.jumpWarning) {
            NoticeCard(
                stringResource(R.string.reference_warning_jump, formats.percent.format(JUMP)),
                NoticeVariant.WARNING,
            )
        }
        sheet.saveFailed?.let {
            NoticeCard(stringResource(saveErrorText(it.outOfSpace)), NoticeVariant.ERROR)
        }
    }
}

/** La cotización anterior y la variación frente a lo escrito, si hay anterior. */
@Composable
private fun ColumnScope.Previous(latest: RateRow?, sheet: RateSheet, reference: ReferenceCurrency) {
    val previous = latest ?: return
    val formats = LocalAppFormats.current
    Text(
        stringResource(
            R.string.reference_previous,
            stringResource(
                R.string.reference_rate_current,
                reference.code.code,
                formats.quantity.formatMicro(previous.rate.content.rateMicro),
            ),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    sheet.variation?.let {
        Text(
            stringResource(R.string.reference_variation, formats.percent.formatChange(it)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Lo que dice un campo de cotización con [problem]. Lo usan esta pantalla y el cobro en otra
 * moneda.
 */
@Composable
internal fun rateProblemText(problem: RateProblem): String = stringResource(
    when (problem) {
        RateProblem.NOT_POSITIVE -> R.string.reference_error_zero
        RateProblem.NOT_A_NUMBER -> R.string.field_error_number
        RateProblem.TOO_HIGH -> R.string.error_value_high
    },
)

/** Muestra [message] con los mensajes de la navegación, que sobreviven a la pantalla. */
@Composable
private fun ShowMessage(message: ReferenceMessage?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val text = message?.let {
        stringResource(
            when (it) {
                ReferenceMessage.RateSaved -> R.string.reference_saved
                is ReferenceMessage.Failed -> saveErrorText(it.outOfSpace)
            },
        )
    }
    val variant = when (message) {
        null, ReferenceMessage.RateSaved -> SnackbarVariant.SUCCESS
        is ReferenceMessage.Failed -> SnackbarVariant.ERROR
    }
    LaunchedEffect(message) {
        if (text != null) {
            messages.show(AppSnackbarVisuals(text, variant))
            onShown()
        }
    }
}

private const val SKELETON_ROWS = 3

/** Sin decimales conocidos para una moneda, se proponen dos; la persona los cambia. */
private const val DEFAULT_DECIMALS = 2

/** El umbral del aviso de salto: la mitad de la cotización anterior. Valor de diseño. */
private val JUMP = BasisPoints(5_000L)
