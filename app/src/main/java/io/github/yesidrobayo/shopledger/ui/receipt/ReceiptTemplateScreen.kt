package io.github.yesidrobayo.shopledger.ui.receipt

import android.icu.text.ListFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateField
import io.github.yesidrobayo.shopledger.domain.usecase.settings.MAX_RECEIPT_TEXT_LENGTH
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.FieldError
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PhotoThumb
import io.github.yesidrobayo.shopledger.ui.components.PhotoThumbSize
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ReceiptPreview
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.document.ReceiptAssets
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.payment.rememberStoredPhoto
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La plantilla del comprobante: arriba la vista previa con datos de muestra, que cambia al
 * escribir, y debajo el logo, el nombre del negocio de solo lectura, los datos, el pie y la
 * leyenda fija, que se muestra y no se puede quitar. `Guardar plantilla` va fija abajo.
 *
 * Desde una ventana mediana, el formulario va a la izquierda y la vista previa queda fija a la
 * derecha. Es pura: todo llega en [uiState] y sale por [onAction]. [onPickLogo] abre el selector
 * de fotos del sistema; lo da la ruta.
 */
@Composable
fun ReceiptTemplateScreen(
    uiState: ReceiptTemplateUiState,
    onAction: (ReceiptTemplateAction) -> Unit,
    modifier: Modifier = Modifier,
    onPickLogo: () -> Unit = {},
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = stringResource(R.string.receipt_template_title),
            navigation = TopBarNavigation.CLOSE,
            onNavigate = { onAction(ReceiptTemplateAction.Leave) },
            navigationLabel = stringResource(R.string.a11y_close),
            titleStyle = MaterialTheme.typography.titleLarge,
            actions = { ScreenHelpLink(HelpTopic.RECEIPTS) },
        )
        when (uiState) {
            ReceiptTemplateUiState.Loading ->
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    DelayedSkeleton { TemplateSkeleton() }
                }

            ReceiptTemplateUiState.ReadFailed ->
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                    modifier = Modifier.weight(1f),
                ) {
                    InlineTextButton(
                        stringResource(R.string.action_retry),
                        { onAction(ReceiptTemplateAction.Retry) },
                    )
                }

            is ReceiptTemplateUiState.Editing -> {
                Editing(uiState, onAction, onPickLogo, Modifier.weight(1f))
                SaveBar(uiState, onAction)
                if (uiState.confirmLeave) {
                    ConfirmDialog(
                        title = stringResource(R.string.dialog_discard_title),
                        body = stringResource(R.string.dialog_discard_body),
                        confirmLabel = stringResource(R.string.dialog_discard_action),
                        onConfirm = { onAction(ReceiptTemplateAction.ConfirmLeave) },
                        onDismiss = { onAction(ReceiptTemplateAction.DismissLeave) },
                        dismissLabel = stringResource(R.string.dialog_discard_exit),
                    )
                }
            }
        }
    }
}

@Composable
private fun Editing(
    editing: ReceiptTemplateUiState.Editing,
    onAction: (ReceiptTemplateAction) -> Unit,
    onPickLogo: () -> Unit,
    modifier: Modifier,
) {
    val document = rememberSampleReceipt(
        businessName = editing.businessName,
        logoMediaId = editing.logo?.let { SAMPLE_LOGO_ID },
        details = editing.details,
        footer = editing.footer,
    )
    val image = rememberReceiptImage(document, editing.logo, NO_ASSETS)
    val spacing = AppTheme.spacing
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= MEDIUM_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = spacing.s8),
                ) {
                    Fields(editing, onAction, onPickLogo)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(spacing.s16),
                ) {
                    Preview(document, image)
                }
            }
        } else {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Preview(document, image, Modifier.padding(spacing.s16))
                Fields(editing, onAction, onPickLogo)
            }
        }
    }
}

/** La vista previa con datos de muestra, y debajo la nota que lo dice. */
@Composable
private fun Preview(
    document: ReceiptDocument,
    image: androidx.compose.ui.graphics.ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        ReceiptPreview(image = image, description = previewDescription(document))
        Text(
            stringResource(R.string.receipt_sample_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Fields(
    editing: ReceiptTemplateUiState.Editing,
    onAction: (ReceiptTemplateAction) -> Unit,
    onPickLogo: () -> Unit,
) {
    val spacing = AppTheme.spacing
    val side = Modifier.padding(horizontal = spacing.s16)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s12)) {
        Logo(editing, onAction, onPickLogo, side)
        SettingsRow(
            title = stringResource(R.string.receipt_business_name),
            summary = editing.businessName ?: stringResource(R.string.draft_no_name),
            action = SettingsRowAction.Navigation {
                onAction(ReceiptTemplateAction.OpenBusinessName)
            },
        )
        val lengthError = stringResource(R.string.receipt_error_length, MAX_RECEIPT_TEXT_LENGTH)
        AppTextField(
            value = editing.details,
            onValueChange = { onAction(ReceiptTemplateAction.DetailsChanged(it)) },
            label = stringResource(R.string.receipt_business_details),
            error = lengthError.takeIf {
                ReceiptTemplateField.BUSINESS_DETAILS in editing.tooLong
            },
            singleLine = false,
            imeAction = ImeAction.Default,
            modifier = side,
        )
        AppTextField(
            value = editing.footer,
            onValueChange = { onAction(ReceiptTemplateAction.FooterChanged(it)) },
            label = stringResource(R.string.receipt_footer),
            error = lengthError.takeIf { ReceiptTemplateField.FOOTER in editing.tooLong },
            singleLine = false,
            imeAction = ImeAction.Default,
            modifier = side,
        )
        FixedLegend(side)
    }
}

/**
 * El logo: su miniatura con `Cambiar imagen` y `Quitar logo`, o solo `Elegir imagen`. Se
 * recodifica sin metadatos al elegirlo y se asigna al guardar. Si la imagen no sirve, se conserva
 * la anterior y se dice por qué.
 */
@Composable
private fun Logo(
    editing: ReceiptTemplateUiState.Editing,
    onAction: (ReceiptTemplateAction) -> Unit,
    onPickLogo: () -> Unit,
    modifier: Modifier,
) {
    val spacing = AppTheme.spacing
    val label = stringResource(R.string.receipt_logo_label)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val state = if (editing.importingLogo) ActionState.WORKING else ActionState.IDLE
        val logo = editing.logo
        if (logo == null) {
            PrimaryActionButton(
                stringResource(R.string.receipt_logo_choose_action),
                onPickLogo,
                ActionVariant.OUTLINED,
                state = state,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val photo = rememberStoredPhoto(logo)
                if (photo != null) PhotoThumb(photo, label, size = PhotoThumbSize.DETAIL)
                Column(Modifier.weight(1f)) {
                    InlineTextButton(
                        stringResource(R.string.receipt_logo_change_action),
                        onPickLogo,
                    )
                    InlineTextButton(
                        stringResource(R.string.receipt_logo_remove_action),
                        { onAction(ReceiptTemplateAction.RemoveLogo) },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        editing.logoProblem?.let { problem ->
            FieldError(
                stringResource(
                    when (problem) {
                        LogoProblem.UNREADABLE -> R.string.receipt_error_image
                        LogoProblem.OUT_OF_SPACE -> R.string.error_device_storage
                    },
                ),
            )
        }
    }
}

/** La leyenda de que no es una factura, de solo lectura: siempre va, y aquí se dice. */
@Composable
private fun FixedLegend(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        Text(
            stringResource(R.string.receipt_fixed_legend),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.legal_receipt_not_invoice),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.receipt_fixed_legend_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SaveBar(
    editing: ReceiptTemplateUiState.Editing,
    onAction: (ReceiptTemplateAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .keepsMessagesAbove()
            .navigationBarsPadding()
            .imePadding()
            .padding(spacing.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        if (editing.saveFailed) {
            NoticeCard(
                text = stringResource(R.string.state_error_save),
                variant = NoticeVariant.ERROR,
                modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth),
            )
        }
        PrimaryActionButton(
            text = stringResource(R.string.receipt_save_action),
            onClick = { onAction(ReceiptTemplateAction.Save) },
            variant = ActionVariant.PRIMARY,
            state = if (editing.saving) ActionState.WORKING else ActionState.IDLE,
            modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth),
        )
    }
}

/** El papel y dos campos, mientras se leen los ajustes. */
@Composable
private fun TemplateSkeleton() {
    val spacing = AppTheme.spacing
    Column(
        Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        SkeletonBlock(Modifier.fillMaxWidth().padding(bottom = spacing.s8))
        SkeletonBlock(Modifier.fillMaxWidth())
        SkeletonBlock(Modifier.fillMaxWidth())
    }
}

/**
 * Lo que lee TalkBack de la vista previa: que es la vista previa del comprobante, y un resumen con
 * el número, la fecha, el total y el método, en lista del idioma activo.
 */
@Composable
internal fun previewDescription(document: ReceiptDocument): String {
    val formats = LocalAppFormats.current
    val number = formats.quantity.format(QuantityMilli.ofUnits(document.number), false)
    val total = document.amounts.filterIsInstance<ReceiptAmount.Total>().firstOrNull()
    val totalText = total?.let { rememberMoneyDisplay(it.amount, formats.money).description }
    val parts = buildList {
        add(stringResource(R.string.receipt_doc_sale_number, number))
        add(formats.date.dayMonthYear(document.businessDate))
        totalText?.let { add(stringResource(R.string.share_receipt_total, it)) }
        document.amounts.filterIsInstance<ReceiptAmount.Payment>().forEach { add(it.methodName) }
    }
    val locale = LocalConfiguration.current.locales[0]
    val summary = ListFormatter.getInstance(locale).format(parts)
    return stringResource(R.string.a11y_receipt_preview, summary)
}

/** Un id cualquiera: el de muestra solo dice que hay logo; la imagen llega aparte. */
private const val SAMPLE_LOGO_ID = 1L
private val NO_ASSETS = ReceiptAssets(logo = null, units = emptyMap(), zone = null)

/** Desde aquí, el formulario y la vista previa van lado a lado. */
private val MEDIUM_WIDTH = 600.dp
