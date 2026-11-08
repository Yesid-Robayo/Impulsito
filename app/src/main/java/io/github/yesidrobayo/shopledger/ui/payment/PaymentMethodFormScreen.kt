package io.github.yesidrobayo.shopledger.ui.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.payment.PaymentMethodRules
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AdaptiveFieldRow
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PercentField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.WholeNumberField
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El formulario de un método de pago, nuevo o guardado. Es puro: todo llega en [uiState] y sale por
 * [onAction]. Los campos guardan lo escrito tal cual, y un error, del campo o de la base, nunca lo
 * borra.
 */
@Composable
fun PaymentMethodFormScreen(
    uiState: PaymentMethodFormUiState,
    onAction: (PaymentMethodFormAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = uiState as? PaymentMethodFormUiState.Editing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        AppTopBar(
            title =
                stringResource(
                    if (editing != null && editing.methodId == null) {
                        R.string.payment_method_new_action
                    } else {
                        R.string.payment_method_edit_title
                    },
                ),
            navigation = TopBarNavigation.BACK,
            onNavigate = { onAction(PaymentMethodFormAction.Back) },
            navigationLabel = stringResource(R.string.a11y_back),
            titleStyle = MaterialTheme.typography.titleLarge,
            actions = { if (editing != null) FormMenu(editing, onAction) },
        )
        when (uiState) {
            PaymentMethodFormUiState.Loading ->
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    DelayedSkeleton { FormSkeleton() }
                }

            PaymentMethodFormUiState.NotFound ->
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                    modifier = Modifier.weight(1f),
                )

            is PaymentMethodFormUiState.Editing -> {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FormFields(
                        uiState,
                        onAction,
                        Modifier.widthIn(max = AppTheme.sizes.maxContentWidth),
                    )
                }
                SaveBar(uiState, onAction)
                FormDialogs(uiState, onAction)
            }
        }
    }
}

@Composable
private fun FormMenu(
    editing: PaymentMethodFormUiState.Editing,
    onAction: (PaymentMethodFormAction) -> Unit,
) {
    val options = editing.options ?: return
    val actions =
        buildList {
            if (options.canReactivate) {
                add(
                    OverflowAction(stringResource(R.string.action_reactivate), {
                        onAction(PaymentMethodFormAction.Reactivate)
                    }),
                )
            }
            if (options.canArchive) {
                add(
                    OverflowAction(stringResource(R.string.action_archive), {
                        onAction(PaymentMethodFormAction.AskArchive)
                    }),
                )
            }
            if (options.canDelete) {
                add(
                    OverflowAction(stringResource(R.string.action_delete), {
                        onAction(PaymentMethodFormAction.AskDelete)
                    }, destructive = true),
                )
            }
        }
    if (actions.isNotEmpty()) OverflowMenu(actions)
}

@Composable
private fun FormFields(
    editing: PaymentMethodFormUiState.Editing,
    onAction: (PaymentMethodFormAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val form = editing.form
    val errors = editing.errors
    val translated = editing.systemKey?.let { paymentMethodName(it, null) }
    // Los encabezados de sección traen su propio margen lateral; lo demás lleva el de la pantalla.
    val side = Modifier.padding(horizontal = spacing.s16)
    Column(
        modifier.fillMaxWidth().padding(vertical = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        // Un método inicial muestra su nombre traducido hasta que la persona lo toca.
        val shownName = if (!form.nameEdited && translated != null) translated else form.name
        AppTextField(
            value = shownName,
            onValueChange = { onAction(PaymentMethodFormAction.NameChanged(it)) },
            label = stringResource(R.string.payment_method_name),
            error = errors[FormField.NAME]?.let { problemText(it) },
            modifier = side,
        )
        InitialNameNotice(editing, shownName, onAction, side)
        KindChooser(editing, onAction, side)
        if (form.kind != PaymentMethodKind.ON_ACCOUNT) {
            SectionHeader(stringResource(R.string.payment_method_section_fee))
            val feePercentLabel = stringResource(R.string.payment_method_fee_percent)
            val feeFixedLabel = stringResource(R.string.payment_method_fee_fixed)
            AdaptiveFieldRow(
                firstLabel = feePercentLabel,
                secondLabel = feeFixedLabel,
                modifier = side,
                first = { fieldModifier ->
                    PercentField(
                        value = form.feeRate,
                        onValueChange = { onAction(PaymentMethodFormAction.FeeRateChanged(it)) },
                        label = feePercentLabel,
                        error = errors[FormField.FEE_RATE]?.let { problemText(it) },
                        modifier = fieldModifier,
                    )
                },
                second = { fieldModifier ->
                    MoneyField(
                        value = form.feeFixed,
                        onValueChange = { onAction(PaymentMethodFormAction.FeeFixedChanged(it)) },
                        label = feeFixedLabel,
                        error = errors[FormField.FEE_FIXED]?.let { problemText(it) },
                        modifier = fieldModifier,
                    )
                },
            )
            WholeNumberField(
                value = form.settlementDays,
                onValueChange = { onAction(PaymentMethodFormAction.SettlementDaysChanged(it)) },
                label = stringResource(R.string.payment_method_settlement),
                error = errors[FormField.SETTLEMENT_DAYS]?.let { problemText(it) },
                modifier = side,
            )
        }
        PercentField(
            value = form.priceAdjustment,
            onValueChange = { onAction(PaymentMethodFormAction.PriceAdjustmentChanged(it)) },
            label = stringResource(R.string.payment_method_adjustment),
            allowNegative = true,
            error = errors[FormField.PRICE_ADJUSTMENT]?.let { problemText(it) },
            modifier = side,
        )
        SectionHeader(stringResource(R.string.payment_method_section_instructions))
        AppTextField(
            value = form.instructions,
            onValueChange = { onAction(PaymentMethodFormAction.InstructionsChanged(it)) },
            label = stringResource(R.string.payment_method_instructions),
            error = errors[FormField.INSTRUCTIONS]?.let { problemText(it) },
            singleLine = false,
            imeAction = ImeAction.Default,
            modifier = side,
        )
        NoticeCard(
            text =
                stringResource(
                    R.string.payment_method_manual_notice,
                    stringResource(R.string.app_name),
                ),
            variant = NoticeVariant.INFO,
            modifier = side,
        )
        if (editing.options?.isLastActive == true) {
            NoticeCard(
                stringResource(R.string.payment_method_last_active),
                NoticeVariant.INFO,
                side,
            )
        }
    }
}

/**
 * Si el nombre escrito es el texto de un método inicial vigente, lo dice y ofrece abrirlo, sin
 * impedir guardar: dos métodos con el mismo texto visible confunden al cobrar.
 */
@Composable
private fun InitialNameNotice(
    editing: PaymentMethodFormUiState.Editing,
    shownName: String,
    onAction: (PaymentMethodFormAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!editing.form.nameEdited) return
    val typed = PaymentMethodRules.normalizedNameOf(shownName) ?: return
    val match =
        editing.initialMethods.entries.firstOrNull { (key, _) ->
            TextNormalizer.normalize(paymentMethodName(key, null)) == typed
        } ?: return
    val name = paymentMethodName(match.key, null)
    NoticeCard(
        text = stringResource(R.string.payment_method_name_matches_initial, name),
        variant = NoticeVariant.WARNING,
        actionLabel = stringResource(R.string.payment_method_name_matches_initial_action, name),
        onAction = { onAction(PaymentMethodFormAction.OpenMethod(match.value)) },
        modifier = modifier,
    )
}

@Composable
private fun KindChooser(
    editing: PaymentMethodFormUiState.Editing,
    onAction: (PaymentMethodFormAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = editing.form.kind
    val locked = editing.options?.isKindLocked == true
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        Text(
            stringResource(R.string.payment_method_kind),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Con ventas, el tipo queda en solo lectura: se muestra solo el elegido.
        CompactChipRow(
            options = if (locked) listOf(kind) else PaymentMethodKind.entries,
            label = { stringResource(kindLabel(it)) },
            selected = setOf(kind),
            onToggle = { onAction(PaymentMethodFormAction.KindChanged(it)) },
            multiple = false,
        )
        val note: AnnotatedString? =
            when {
                editing.errors[FormField.KIND] != null || locked ->
                    AnnotatedString(stringResource(R.string.payment_method_error_kind_locked))

                kind == PaymentMethodKind.ON_ACCOUNT ->
                    AnnotatedString(stringResource(R.string.payment_method_on_account_requires))

                // Con efectivo se ve el redondeo de los ajustes regionales, si hay uno.
                kind == PaymentMethodKind.CASH && editing.cashRounding > Money.ZERO -> {
                    val step =
                        rememberMoneyDisplay(editing.cashRounding, LocalAppFormats.current.money)
                    formatAnnotated(
                        stringResource(R.string.payment_method_cash_rounding_note),
                        step.text,
                    )
                }

                else -> null
            }
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SaveBar(
    editing: PaymentMethodFormUiState.Editing,
    onAction: (PaymentMethodFormAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(spacing.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        editing.notice?.let { notice ->
            NoticeCard(
                text = noticeText(notice),
                variant =
                    when (notice) {
                        is FormNotice.SaveFailed -> NoticeVariant.ERROR

                        FormNotice.AccountUnavailable,
                        FormNotice.LastActive,
                        FormNotice.ReactivateNameTaken,
                        FormNotice.HasSales,
                        -> NoticeVariant.WARNING
                    },
                modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth),
            )
        }
        PrimaryActionButton(
            text = stringResource(R.string.payment_method_save_action),
            onClick = { onAction(PaymentMethodFormAction.Save) },
            variant = ActionVariant.PRIMARY,
            state = if (editing.working) ActionState.WORKING else ActionState.IDLE,
            modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth),
        )
    }
}

@Composable
private fun FormDialogs(
    editing: PaymentMethodFormUiState.Editing,
    onAction: (PaymentMethodFormAction) -> Unit,
) {
    val dialog = editing.dialog ?: return
    val name =
        if (editing.form.nameEdited || editing.systemKey == null) {
            editing.form.name.trim()
        } else {
            paymentMethodName(editing.systemKey, null)
        }
    when (dialog) {
        FormDialog.ARCHIVE ->
            ConfirmDialog(
                title = stringResource(R.string.payment_method_archive_confirm_title, name),
                body = stringResource(R.string.payment_method_archive_confirm_body),
                confirmLabel = stringResource(R.string.payment_method_archive_confirm_action),
                onConfirm = { onAction(PaymentMethodFormAction.ConfirmDialog) },
                onDismiss = { onAction(PaymentMethodFormAction.DismissDialog) },
            )

        FormDialog.DELETE ->
            ConfirmDialog(
                title = stringResource(R.string.payment_method_delete_confirm_title, name),
                body = stringResource(R.string.payment_method_delete_confirm_body),
                confirmLabel = stringResource(R.string.payment_method_delete_confirm_action),
                onConfirm = { onAction(PaymentMethodFormAction.ConfirmDialog) },
                onDismiss = { onAction(PaymentMethodFormAction.DismissDialog) },
                variant = ConfirmVariant.DESTRUCTIVE,
            )
    }
}

/** El texto de un error de campo, que dice cómo arreglarlo. */
@Composable
private fun problemText(problem: FieldProblem): String {
    val formats = LocalAppFormats.current
    return when (problem) {
        FieldProblem.NAME_BLANK -> stringResource(R.string.payment_method_error_name_empty)

        FieldProblem.NAME_TOO_LONG -> stringResource(R.string.payment_method_error_name_long)

        FieldProblem.NAME_TAKEN -> stringResource(R.string.payment_method_error_name_duplicate)

        FieldProblem.KIND_LOCKED -> stringResource(R.string.payment_method_error_kind_locked)

        FieldProblem.FEE_RATE_RANGE -> stringResource(R.string.payment_method_error_fee_range)

        FieldProblem.FEE_FIXED_NEGATIVE -> stringResource(R.string.payment_method_error_fee_fixed)

        FieldProblem.FEE_FIXED_TOO_HIGH -> stringResource(R.string.error_value_high)

        FieldProblem.SETTLEMENT_TOO_HIGH ->
            stringResource(
                R.string.payment_method_error_settlement_range,
                formats.quantity.format(
                    QuantityMilli.ofUnits(PaymentMethodRules.MAX_SETTLEMENT_DAYS.toLong()),
                    allowsFraction = false,
                ),
            )

        FieldProblem.PRICE_ADJUSTMENT_RANGE -> {
            val range = PaymentMethodRules.PRICE_ADJUSTMENT_RANGE
            stringResource(
                R.string.payment_method_error_adjustment_range,
                formats.percent.format(range.start),
                formats.percent.format(range.endInclusive),
            )
        }

        FieldProblem.INSTRUCTIONS_TOO_LONG ->
            pluralStringResource(
                R.plurals.error_text_long,
                PaymentMethodRules.MAX_INSTRUCTIONS_LENGTH,
                PaymentMethodRules.MAX_INSTRUCTIONS_LENGTH,
            )
    }
}

@Composable
private fun noticeText(notice: FormNotice): String = when (notice) {
    is FormNotice.SaveFailed -> saveProblemText(notice.problem)
    FormNotice.AccountUnavailable -> stringResource(R.string.payment_method_error_account)
    FormNotice.LastActive -> stringResource(R.string.payment_method_last_active)
    FormNotice.ReactivateNameTaken -> stringResource(R.string.payment_method_reactivate_name_taken)
    FormNotice.HasSales -> stringResource(R.string.payment_method_has_sales)
}

@Composable
private fun FormSkeleton() {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        repeat(FORM_SKELETON_FIELDS) {
            SkeletonBlock(Modifier.fillMaxWidth().padding(vertical = spacing.s24))
        }
    }
}

private const val FORM_SKELETON_FIELDS = 4
