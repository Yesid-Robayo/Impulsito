package io.github.yesidrobayo.shopledger.ui.region

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.rule.CashRounding
import io.github.yesidrobayo.shopledger.format.DateFormatter
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.format.PercentFormatter
import io.github.yesidrobayo.shopledger.format.QuantityFormatter
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OnboardingStep
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.currency.CurrencySymbolFonts
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import java.util.Locale
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** El paso del primer uso en que se confirma la región, y cuántos tiene. */
private const val ONBOARDING_STEP = 2
private const val ONBOARDING_STEPS = 4

/** Decimales que admite la moneda del negocio, en la fila de chips. */
private val DECIMAL_CHOICES = (0..4).toList()

/**
 * Desde este ancho la ventana es `Expanded`: el formulario va a la izquierda y la vista previa queda
 * fija a la derecha. Es el límite de las clases de ventana de Material.
 */
private val EXPANDED_WIDTH = 840.dp

/**
 * La configuración regional: moneda, decimales, símbolo, redondeo de efectivo, formato, zona,
 * semana y separador de CSV, con una vista previa que cambia con cada elección.
 *
 * Es pura: recibe el estado y avisa cada acción. Con [RegionalSettingsUiState.Ready.firstRun] es un
 * paso del primer uso, sin barra superior.
 *
 * @param onBack vuelve a la pantalla anterior desde la barra superior.
 */
@Composable
fun RegionalSettingsScreen(
    uiState: RegionalSettingsUiState,
    onAction: (RegionalSettingsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.region_saved)
    val ready = uiState as? RegionalSettingsUiState.Ready
    LaunchedEffect(ready?.saveState) {
        if (ready?.saveState == RegionalSaveState.SAVED && !ready.firstRun) {
            snackbar.showSnackbar(AppSnackbarVisuals(savedText, SnackbarVariant.SUCCESS))
            onAction(RegionalSettingsAction.SavedShown)
        }
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when (uiState) {
            RegionalSettingsUiState.Loading -> Framed(onBack) {
                DelayedSkeleton { LoadingFields() }
            }

            RegionalSettingsUiState.Unavailable ->
                Framed(onBack) {
                    EmptyState(
                        icon = R.drawable.ic_error,
                        title = stringResource(R.string.state_error_read),
                    )
                }

            is RegionalSettingsUiState.Ready ->
                if (uiState.firstRun) {
                    OnboardingStep(
                        step = ONBOARDING_STEP,
                        totalSteps = ONBOARDING_STEPS,
                        title = stringResource(R.string.region_title),
                        action = { SaveButton(uiState, onAction) },
                    ) { RegionalForm(uiState, onAction, withPreview = true) }
                } else {
                    Framed(onBack, bottom = { SaveButton(uiState, onAction) }) {
                        AdaptiveForm(uiState, onAction)
                    }
                }
        }
        AppSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding(),
        )
    }
    if (ready != null) {
        Sheets(ready, onAction)
        if (ready.confirmingZone) {
            ConfirmDialog(
                title = stringResource(R.string.region_zone_confirm_title),
                body = stringResource(R.string.region_zone_confirm_body),
                confirmLabel = stringResource(R.string.region_zone_confirm_action),
                onConfirm = { onAction(RegionalSettingsAction.ConfirmZoneChange) },
                onDismiss = { onAction(RegionalSettingsAction.CancelZoneChange) },
            )
        }
    }
}

/** La barra superior con su atrás, el contenido que se desplaza y, si hay, la acción fija abajo. */
@Composable
private fun Framed(
    onBack: () -> Unit,
    bottom: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = stringResource(R.string.region_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = onBack,
            navigationLabel = stringResource(R.string.a11y_back),
            titleStyle = MaterialTheme.typography.titleLarge,
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            content()
        }
        if (bottom != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(AppTheme.spacing.s16),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth)) { bottom() }
            }
        }
    }
}

/**
 * El formulario según el ancho: en `Compact` y `Medium`, una columna de 600 dp como máximo, con la
 * vista previa en medio; en `Expanded`, la vista previa fija a la derecha, a la vista mientras se
 * cambia cualquier campo.
 */
@Composable
private fun AdaptiveForm(
    state: RegionalSettingsUiState.Ready,
    onAction: (RegionalSettingsAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= EXPANDED_WIDTH) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = spacing.s16),
                horizontalArrangement = Arrangement.spacedBy(spacing.s24),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(spacing.s12),
                ) { RegionalForm(state, onAction, withPreview = false) }
                Column(
                    Modifier
                        .width(AppTheme.sizes.sideSheet)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = spacing.s16),
                ) { Preview(state.draft) }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = spacing.s16),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .fillMaxWidth()
                        .padding(horizontal = spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(spacing.s12),
                ) { RegionalForm(state, onAction, withPreview = true) }
            }
        }
    }
}

/** Los campos, de arriba abajo, en el orden de la pantalla. */
@Composable
private fun ColumnScope.RegionalForm(
    state: RegionalSettingsUiState.Ready,
    onAction: (RegionalSettingsAction) -> Unit,
    withPreview: Boolean,
) {
    val draft = state.draft
    val errors = state.errors
    if (state.firstRun || !draft.currency.isConfirmed) {
        NoticeCard(stringResource(R.string.region_notice_device), NoticeVariant.INFO)
    }
    if (errors.isNotEmpty() && RegionalFieldError.CURRENCY_MISSING in errors) {
        NoticeCard(stringResource(R.string.region_error_no_currency), NoticeVariant.ERROR)
    }

    FormSection(stringResource(R.string.region_section_currency))
    CurrencyRow(state, onAction)
    FieldLabel(stringResource(R.string.region_decimals))
    if (state.locked) {
        Text(
            draft.decimals.toString(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Help(stringResource(R.string.region_locked_help))
    } else {
        CompactChipRow(
            options = DECIMAL_CHOICES,
            label = { it.toString() },
            selected = setOf(draft.decimals),
            onToggle = { onAction(RegionalSettingsAction.ChooseDecimals(it)) },
            multiple = false,
        )
        val iso = state.isoDecimals
        if (iso != null && iso != draft.decimals) {
            Help(stringResource(R.string.region_decimals_iso_help, iso))
        }
        Help(stringResource(R.string.region_lock_warning))
    }
    SymbolField(state, onAction)
    if (withPreview) Preview(draft)

    FormSection(stringResource(R.string.region_section_cash))
    CashRoundingField(state, onAction)

    FormSection(stringResource(R.string.region_section_formats))
    SettingsRow(
        title = stringResource(R.string.region_format_locale),
        action = SettingsRowAction.Navigation {
            onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.FORMAT))
        },
        summary =
            state.formatName?.let {
                stringResource(R.string.region_option_summary, it, draft.formatTag)
            }
                ?: stringResource(R.string.region_format_locale_help),
        warning = stringResource(R.string.region_currency_unconfirmed)
            .takeIf { RegionalFieldError.FORMAT_MISSING in errors },
        modifier = Modifier.bleed(),
    )
    if (state.formatName != null) Help(stringResource(R.string.region_format_locale_help))
    SettingsRow(
        title = stringResource(R.string.region_time_zone),
        action = SettingsRowAction.Navigation {
            onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.ZONE))
        },
        summary =
            state.zoneName?.let { stringResource(R.string.region_option_summary, it, draft.zoneId) }
                ?: draft.zoneId,
        warning = stringResource(R.string.region_currency_unconfirmed)
            .takeIf { RegionalFieldError.ZONE_UNKNOWN in errors },
        modifier = Modifier.bleed(),
    )
    FieldLabel(stringResource(R.string.region_week_start))
    val uiLocale = rememberUiLocale()
    val weekNames = remember(uiLocale) { weekdayNames(uiLocale) }
    CompactChipRow(
        options = DayOfWeek.entries,
        label = { weekNames.getValue(it) },
        selected = setOf(draft.weekStart),
        onToggle = { onAction(RegionalSettingsAction.ChooseWeekStart(it)) },
        multiple = false,
    )
    FieldLabel(stringResource(R.string.region_csv_separator))
    CompactChipRow(
        options = CsvSeparator.entries,
        label = { stringResource(csvLabel(it)) },
        selected = setOf(draft.csvSeparator),
        onToggle = { onAction(RegionalSettingsAction.ChooseCsvSeparator(it)) },
        multiple = false,
    )
    if (state.saveFailed) {
        NoticeCard(
            text = stringResource(R.string.state_error_save),
            variant = NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(RegionalSettingsAction.Save) },
        )
    }
}

/** La moneda: se elige de la lista, o solo se lee si ya hay registros con dinero. */
@Composable
private fun CurrencyRow(
    state: RegionalSettingsUiState.Ready,
    onAction: (RegionalSettingsAction) -> Unit,
) {
    val code = state.draft.currency
    val summary =
        when {
            !code.isConfirmed -> code.code

            state.currencyName != null -> stringResource(
                R.string.region_option_summary,
                state.currencyName,
                code.code,
            )

            else -> code.code
        }
    SettingsRow(
        title = stringResource(R.string.region_currency),
        action =
            if (state.locked) {
                SettingsRowAction.Value {}
            } else {
                SettingsRowAction.Navigation {
                    onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.CURRENCY))
                }
            },
        summary = summary,
        warning = stringResource(R.string.region_currency_unconfirmed).takeIf { !code.isConfirmed },
        modifier = Modifier.bleed(),
    )
}

/** El símbolo propio, con su ayuda, su error al salir y el aviso si el teléfono no lo dibuja. */
@Composable
private fun SymbolField(
    state: RegionalSettingsUiState.Ready,
    onAction: (RegionalSettingsAction) -> Unit,
) {
    val draft = state.draft
    val resources = LocalResources.current
    val symbol = draft.symbol.trim()
    val unavailable =
        remember(symbol, draft.currency, resources) {
            symbol.isNotEmpty() &&
                CurrencySymbolFonts.chain(
                    resources,
                ).resolve(symbol, draft.currency.code).usesIsoCode
        }
    AppTextField(
        value = draft.symbol,
        onValueChange = { onAction(RegionalSettingsAction.EditSymbol(it)) },
        label = stringResource(R.string.region_symbol_optional),
        error = stringResource(R.string.region_error_symbol_length)
            .takeIf { RegionalFieldError.SYMBOL_LENGTH in state.errors },
        supportingText = stringResource(R.string.region_symbol_help),
        capitalization = KeyboardCapitalization.None,
        onLeave = { onAction(RegionalSettingsAction.LeaveSymbol) },
    )
    if (unavailable) {
        NoticeCard(
            stringResource(R.string.region_symbol_unavailable, draft.currency.code),
            NoticeVariant.WARNING,
        )
    }
}

/** El redondeo de efectivo, con un ejemplo en vivo si no es cero. */
@Composable
private fun CashRoundingField(
    state: RegionalSettingsUiState.Ready,
    onAction: (RegionalSettingsAction) -> Unit,
) {
    val draft = state.draft
    val formats = rememberDraftFormats(draft)
    val error =
        when {
            RegionalFieldError.CASH_ROUNDING_NUMBER in state.errors ->
                stringResource(R.string.field_error_number)

            RegionalFieldError.CASH_ROUNDING_DECIMALS in state.errors ->
                pluralStringResource(R.plurals.error_money_decimals, draft.decimals, draft.decimals)

            RegionalFieldError.CASH_ROUNDING_HIGH in state.errors ->
                stringResource(R.string.error_value_high)

            else -> null
        }
    if (formats == null) {
        // Sin un formato usable no se puede leer un monto: primero hay que elegir el formato.
        Help(stringResource(R.string.region_cash_rounding_zero_hint))
        return
    }
    CompositionLocalProvider(LocalAppFormats provides formats) {
        MoneyField(
            value = draft.cashRounding,
            onValueChange = { onAction(RegionalSettingsAction.EditCashRounding(it)) },
            label = stringResource(R.string.region_cash_rounding),
            error = error,
            imeAction = ImeAction.Done,
            modifier = Modifier.onLeave { onAction(RegionalSettingsAction.LeaveCashRounding) },
        )
        val step = state.cashRoundingStep
        if (step == null || step == Money.ZERO) {
            Help(stringResource(R.string.region_cash_rounding_zero_hint))
        } else {
            val sample = CASH_SAMPLE
            val rounded = sample + CashRounding.amount(sample, step)
            val before = rememberMoneyDisplay(sample, formats.money).text
            val after = rememberMoneyDisplay(rounded, formats.money).text
            Text(
                formatAnnotated(
                    stringResource(R.string.region_cash_rounding_example),
                    before,
                    after,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * La vista previa, con cifras de muestra y no datos del negocio: un monto, el mismo negativo, una
 * cantidad, una fecha y una hora, con el formato, la moneda y el símbolo que se están eligiendo.
 */
@Composable
private fun Preview(draft: RegionalDraft) {
    val formats = rememberDraftFormats(draft) ?: return
    val spacing = AppTheme.spacing
    val sample = previewAmount(draft.decimals)
    val utc = remember { requireNotNull(BusinessTimeZone.of(UTC)) }
    CompositionLocalProvider(LocalAppFormats provides formats, LocalDiscreetMode provides false) {
        AppCard(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.region_preview_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(
                Modifier.padding(top = spacing.s8),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                PreviewLine(stringResource(R.string.region_preview_amount)) {
                    MoneyText(sample, MoneySize.ROW)
                }
                PreviewLine(stringResource(R.string.region_preview_negative)) {
                    MoneyText(-sample, MoneySize.ROW)
                }
                val kilogram =
                    UnitLabel(stringResource(R.string.unit_symbol_kilogram), allowsFraction = true)
                PreviewLine(stringResource(R.string.region_preview_quantity)) {
                    PreviewValue(quantityText(QuantityMilli(SAMPLE_QUANTITY_MILLI), kilogram))
                }
                PreviewLine(stringResource(R.string.region_preview_date)) {
                    PreviewValue(formats.date.dayMonthYear(SAMPLE_DATE))
                }
                PreviewLine(stringResource(R.string.region_preview_time)) {
                    PreviewValue(formats.date.time(SAMPLE_INSTANT, utc))
                }
            }
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = AppTheme.spacing.s8),
        )
        value()
    }
}

@Composable
private fun PreviewValue(text: String) {
    Text(text, style = AppTheme.amounts.row, color = MaterialTheme.colorScheme.onSurface)
}

/** Las tres hojas de búsqueda: moneda, formato y zona. */
@Composable
private fun Sheets(
    state: RegionalSettingsUiState.Ready,
    onAction: (RegionalSettingsAction) -> Unit,
) {
    val sheet = state.sheet ?: return
    val (title, hint, selected) =
        when (sheet) {
            RegionalSheet.CURRENCY ->
                Triple(
                    R.string.region_currency,
                    R.string.region_currency_search_hint,
                    state.draft.currency.code,
                )

            RegionalSheet.FORMAT ->
                Triple(
                    R.string.region_format_locale,
                    R.string.region_format_search_hint,
                    state.draft.formatTag,
                )

            RegionalSheet.ZONE ->
                Triple(
                    R.string.region_time_zone,
                    R.string.region_time_zone_search_hint,
                    state.zoneKey ?: state.draft.zoneId,
                )
        }
    SelectionSheet(
        title = stringResource(title),
        items = state.sheetOptions,
        key = { it.key },
        query = state.query,
        onQueryChange = { onAction(RegionalSettingsAction.Search(it)) },
        onDismissRequest = { onAction(RegionalSettingsAction.CloseSheet) },
        searchHint = stringResource(hint),
        itemContent = { option -> OptionRow(option, option.key == selected, onAction) },
    )
}

/** Una opción de la hoja: nombre, detalle y la marca de la elegida, que no depende del color. */
@Composable
private fun OptionRow(
    option: RegionalOption,
    selected: Boolean,
    onAction: (RegionalSettingsAction) -> Unit,
) {
    SettingsRow(
        title = option.title,
        action =
            SettingsRowAction.Value {
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(AppTheme.sizes.icon),
                    )
                }
            },
        summary = option.detail,
        modifier =
            Modifier.selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onAction(RegionalSettingsAction.Pick(option.key)) },
            ),
    )
}

/** `Confirmar configuración` en el primer uso y `Guardar cambios` después. */
@Composable
private fun SaveButton(
    state: RegionalSettingsUiState.Ready,
    onAction: (RegionalSettingsAction) -> Unit,
) {
    PrimaryActionButton(
        text = stringResource(
            if (state.firstRun) R.string.region_confirm_action else R.string.region_save_action,
        ),
        onClick = { onAction(RegionalSettingsAction.Save) },
        variant = ActionVariant.PRIMARY,
        state =
            when (state.saveState) {
                RegionalSaveState.IDLE -> ActionState.IDLE
                RegionalSaveState.SAVING -> ActionState.WORKING
                RegionalSaveState.SAVED -> ActionState.DONE
            },
    )
}

@Composable
private fun FormSection(title: String) {
    SectionHeader(title, Modifier.bleed())
}

/**
 * Las filas y los encabezados del catálogo traen su propio margen lateral de `s16`, pensado para ir
 * de borde a borde. Dentro del formulario, que ya tiene ese margen, ocupan también el del formulario:
 * así su texto queda alineado con el de los campos.
 */
@Composable
private fun Modifier.bleed(): Modifier {
    val margin = AppTheme.spacing.s16
    return layout { measurable, constraints ->
        val extra = (margin * 2).roundToPx()
        val wider =
            constraints.copy(
                minWidth = constraints.minWidth + extra,
                maxWidth =
                    if (constraints.hasBoundedWidth) {
                        constraints.maxWidth + extra
                    } else {
                        constraints.maxWidth
                    },
            )
        val placeable = measurable.measure(wider)
        layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
    }
}

/** Avisa con [onLeave] cuando el campo pierde el foco después de haberlo tenido. */
@Composable
private fun Modifier.onLeave(onLeave: () -> Unit): Modifier {
    val focused = remember { mutableStateOf(false) }
    return onFocusChanged {
        if (focused.value && !it.isFocused) onLeave()
        focused.value = it.isFocused
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Help(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Bloques con la forma de los campos, por si la lectura tarda. */
@Composable
private fun LoadingFields() {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        repeat(LOADING_BLOCKS) {
            SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.field))
        }
    }
}

/** El idioma en que respondieron los recursos: el de las palabras, nunca el de los números. */
@Composable
internal fun rememberUiLocale(): Locale {
    val tag = stringResource(R.string.resources_language)
    return remember(tag) { Locale.forLanguageTag(tag) }
}

/**
 * Los formatos de lo que se está eligiendo, o `null` si el formato no es usable en este teléfono.
 * Se arman de nuevo solo si cambia algo que los afecta.
 */
@Composable
private fun rememberDraftFormats(draft: RegionalDraft): AppFormats? {
    val uiLocale = rememberUiLocale()
    return remember(draft.formatTag, draft.currency, draft.decimals, draft.symbol, uiLocale) {
        val formatLocale = FormatLocale.of(draft.formatTag) ?: return@remember null
        AppFormats(
            money = MoneyFormatter(
                formatLocale,
                draft.currency,
                draft.decimals,
                draft.symbol,
                uiLocale,
            ),
            quantity = QuantityFormatter(formatLocale),
            percent = PercentFormatter(formatLocale),
            date = DateFormatter(formatLocale, uiLocale),
        )
    }
}

/** Los nombres cortos de los días en el idioma de la interfaz, con su propia región. */
private fun weekdayNames(uiLocale: Locale): Map<DayOfWeek, String> {
    val names = DateFormatter(requireNotNull(FormatLocale.of(uiLocale.toLanguageTag())), uiLocale)
    return DayOfWeek.entries.associateWith(names::weekdayShort)
}

private fun csvLabel(separator: CsvSeparator): Int = when (separator) {
    CsvSeparator.SEMICOLON -> R.string.region_csv_semicolon
    CsvSeparator.COMMA -> R.string.region_csv_comma
    CsvSeparator.TAB -> R.string.region_csv_tab
}

/**
 * Un monto de muestra con las cifras `12345,6789` recortadas a los decimales elegidos: así se ven la
 * agrupación de miles y cada decimal, en cualquier moneda.
 */
internal fun previewAmount(decimals: Int): Money {
    var minor = PREVIEW_MINOR_AT_FOUR_DECIMALS
    repeat(MAX_DECIMALS - decimals) { minor /= TEN }
    return Money(minor)
}

/** Un pago en efectivo de muestra que casi nunca cae justo en el paso: `1232` en unidad menor. */
internal val CASH_SAMPLE: Money = Money(CASH_SAMPLE_MINOR)

private const val PREVIEW_MINOR_AT_FOUR_DECIMALS = 123_456_789L
private const val MAX_DECIMALS = 4
private const val TEN = 10L
private const val CASH_SAMPLE_MINOR = 1_232L
private const val SAMPLE_QUANTITY_MILLI = 250L
private const val LOADING_BLOCKS = 6
private const val UTC = "UTC"

/** Una fecha y una hora de muestra, iguales en cualquier teléfono. */
private val SAMPLE_DATE = BusinessDate(LocalDate(2026, 10, 28))
private val SAMPLE_INSTANT: Instant = LocalDateTime(2026, 10, 28, 10, 42).toInstant(TimeZone.UTC)

/** Un estado de muestra: moneda de prueba de ISO 4217 y un formato con coma decimal. */
private fun previewState(
    firstRun: Boolean = false,
    currency: CurrencyCode = requireNotNull(CurrencyCode.of("XTS")),
    locked: Boolean = false,
    errors: Set<RegionalFieldError> = emptySet(),
    saveFailed: Boolean = false,
): RegionalSettingsUiState.Ready = RegionalSettingsUiState.Ready(
    firstRun = firstRun,
    draft =
        RegionalDraft(
            currency = currency,
            decimals = 2,
            symbol = "¤",
            cashRounding = "0,05",
            formatTag = "es",
            zoneId = "Etc/GMT+5",
            weekStart = DayOfWeek.MONDAY,
            csvSeparator = CsvSeparator.SEMICOLON,
        ),
    locked = locked,
    currencyName = PREVIEW_CURRENCY_NAME.takeIf { currency.isConfirmed },
    formatName = PREVIEW_FORMAT_NAME,
    zoneName = null,
    isoDecimals = 3,
    cashRoundingStep = Money(5),
    errors = errors,
    saveFailed = saveFailed,
)

/** Un nombre de 80 caracteres, para ver que nada se corta. */
private const val PREVIEW_CURRENCY_NAME =
    "Moneda de prueba con un nombre larguísimo que ocupa ochenta caracteres en la fila"
private const val PREVIEW_FORMAT_NAME = "Formato de muestra"

@ThemePreviews
@Composable
private fun RegionalSettingsFirstRunPreview() {
    PreviewTheme {
        RegionalSettingsScreen(
            previewState(
                firstRun = true,
                currency = CurrencyCode.UNCONFIRMED,
                errors = setOf(RegionalFieldError.CURRENCY_MISSING),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@ThemePreviews
@Composable
private fun RegionalSettingsLockedPreview() {
    PreviewTheme {
        RegionalSettingsScreen(
            previewState(
                locked = true,
                errors = setOf(RegionalFieldError.ZONE_UNKNOWN, RegionalFieldError.SYMBOL_LENGTH),
                saveFailed = true,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@ThemePreviews
@Composable
private fun RegionalSettingsUnavailablePreview() {
    PreviewTheme {
        RegionalSettingsScreen(RegionalSettingsUiState.Unavailable, onAction = {}, onBack = {})
    }
}
