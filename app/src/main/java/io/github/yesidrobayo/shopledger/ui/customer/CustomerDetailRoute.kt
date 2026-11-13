package io.github.yesidrobayo.shopledger.ui.customer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.rule.customer.ReminderRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.ReminderValues
import io.github.yesidrobayo.shopledger.lock.rememberSensitiveCheck
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.rescue.ExportFolder
import io.github.yesidrobayo.shopledger.ui.rescue.exportFile
import io.github.yesidrobayo.shopledger.ui.rescue.pdfShareIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** El detalle de un cliente en su propia pantalla. */
@Composable
fun CustomerDetailRoute(key: AppNavKey.CustomerDetail, navigator: AppNavigator) {
    CustomerDetailPane(
        customerId = key.customerId,
        navigator = navigator,
        navigation = TopBarNavigation.BACK,
        onClose = { navigator.back() },
    )
}

/**
 * El detalle con su ViewModel, en su propia pantalla o en el panel derecho de una ventana ancha:
 * cada cliente elegido tiene el suyo.
 *
 * - Compartir el extracto lo escribe como PDF en `cache/exports/` y abre el selector del sistema,
 *   siempre por un toque de la persona.
 * - Anonimizar pide antes la verificación del sistema si el bloqueo está activo; sin ella no sigue.
 * - Atrás con una hoja abierta la cierra primero.
 */
@Composable
fun CustomerDetailPane(
    customerId: Long,
    navigator: AppNavigator,
    navigation: TopBarNavigation,
    onClose: () -> Unit,
) {
    val formats = LocalAppFormats.current
    val numbers = CustomerNumberFormat(formats.money.inputSymbols, formats.money.currencyDecimals)
    // La plantilla de los recursos y su idioma: la persona edita la del idioma de la interfaz.
    val reminderTexts = ReminderTexts(
        languageTag = formats.uiLocale.toLanguageTag(),
        defaultTemplate = stringResource(R.string.customer_reminder_template),
    )
    val model = rememberAppViewModel(key = "customer-$customerId") {
        CustomerDetailViewModel(
            customerId,
            CustomerDetailUseCases.of(it),
            numbers,
            reminderTexts,
            priceLists = { it.ports.priceLists.observeActive() },
        )
    }
    when (model) {
        ScreenModel.Opening ->
            CustomerDetailScreen(CustomerDetailUiState(), {}, navigation = navigation)

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.CustomerDetail(customerId), navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val check = rememberSensitiveCheck()
            val anonymizeTitle = stringResource(R.string.customer_anonymize_confirm_action)
            ShowMessage(uiState.message, viewModel::onAction)
            ShareStatement(uiState.statement, formats, viewModel::onAction)
            (uiState.content as? CustomerDetailContent.Ready)?.view?.let { view ->
                SendReminder(uiState.outgoing, view, formats, viewModel::onAction)
            }
            val editor by viewModel.editor.editor.collectAsStateWithLifecycle()
            val pickContact = rememberContactPicker(viewModel.editor)
            BackHandler(
                enabled = uiState.payment != null || uiState.adjust != null ||
                    uiState.reminder != null,
            ) {
                viewModel.onAction(CustomerDetailAction.DismissSheet)
            }
            BackHandler(enabled = editor != null) {
                viewModel.editor.onAction(CustomerEditorAction.Dismiss)
            }
            val onAction: (CustomerDetailAction) -> Unit = { action ->
                when (action) {
                    CustomerDetailAction.Back -> onClose()

                    is CustomerDetailAction.Dial -> openDialer(context, action.phone)

                    is CustomerDetailAction.OpenSale -> navigator.open(
                        AppNavKey.SaleDetail(action.saleId),
                    )

                    CustomerDetailAction.SellToCustomer ->
                        navigator.open(AppNavKey.NewSale(prefillCustomerId = customerId))

                    CustomerDetailAction.ChooseZone, CustomerDetailAction.ConfirmCurrency ->
                        navigator.open(AppNavKey.RegionalSettings())

                    CustomerDetailAction.ConfirmDialog ->
                        if (uiState.dialog is CustomerDetailDialog.Anonymize) {
                            scope.launch {
                                if (check.confirm(anonymizeTitle)) {
                                    viewModel.onAction(action)
                                } else {
                                    viewModel.onAction(CustomerDetailAction.DismissDialog)
                                }
                            }
                        } else {
                            viewModel.onAction(action)
                        }

                    CustomerDetailAction.Edit, CustomerDetailAction.Retry,
                    is CustomerDetailAction.SelectTab,
                    CustomerDetailAction.LoadMoreSales, CustomerDetailAction.ShareStatement,
                    is CustomerDetailAction.StatementShared, CustomerDetailAction.OpenPayment,
                    is CustomerDetailAction.PaymentAmount, is CustomerDetailAction.ChooseMethod,
                    is CustomerDetailAction.PaymentAccount, is CustomerDetailAction.PaymentDate,
                    is CustomerDetailAction.PaymentNote, CustomerDetailAction.SavePayment,
                    CustomerDetailAction.DismissSheet, is CustomerDetailAction.AskVoidPayment,
                    CustomerDetailAction.OpenAdjust, is CustomerDetailAction.AdjustAmount,
                    is CustomerDetailAction.AdjustNote, CustomerDetailAction.SaveAdjust,
                    CustomerDetailAction.AskArchive, CustomerDetailAction.Reactivate,
                    CustomerDetailAction.AskAnonymize, CustomerDetailAction.DismissDialog,
                    CustomerDetailAction.UpdateCopies, CustomerDetailAction.MessageShown,
                    CustomerDetailAction.OpenReminder, is CustomerDetailAction.ReminderText,
                    is CustomerDetailAction.ReminderDetails, CustomerDetailAction.ResetReminder,
                    is CustomerDetailAction.SendReminder, is CustomerDetailAction.ReminderSent,
                    -> viewModel.onAction(action)
                }
            }
            CustomerDetailScreen(uiState, onAction, navigation = navigation)
            uiState.payment?.let { PaymentSheetHost(uiState, it, onAction) }
            uiState.adjust?.let { AdjustSheetHost(it, onAction) }
            uiState.reminder?.let { sheet ->
                val phone = (uiState.content as? CustomerDetailContent.Ready)
                    ?.view?.customer?.contact?.phone
                ReminderSheetHost(sheet, hasPhone = phone != null, onAction = onAction)
            }
            uiState.dialog?.let { CustomerDetailConfirm(it, onAction) }
            editor?.let {
                CustomerEditorSheet(it, { action ->
                    when (action) {
                        CustomerEditorAction.PickContact -> {
                            viewModel.editor.onAction(action)
                            pickContact()
                        }

                        is CustomerEditorAction.Name, is CustomerEditorAction.Phone,
                        is CustomerEditorAction.Email, is CustomerEditorAction.Notes,
                        is CustomerEditorAction.ChoosePriceList,
                        CustomerEditorAction.Save, CustomerEditorAction.Dismiss,
                        -> viewModel.editor.onAction(action)
                    }
                })
            }
        }
    }
}

/**
 * Escribe [document] como PDF en `cache/exports/` y abre el selector del sistema para compartirlo.
 * El archivo se llama igual para todos los clientes: el nombre no dice de quién es. Al volver del
 * selector, la carpeta se vacía: nada de lo que sale queda guardado.
 */
@Composable
private fun ShareStatement(
    document: StatementDocument?,
    formats: AppFormats,
    onAction: (CustomerDetailAction) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val fileName = stringResource(R.string.customer_statement_file) + PDF_EXTENSION
    val chooser =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            scope.launch { withContext(Dispatchers.IO) { ExportFolder.clear(context) } }
        }
    LaunchedEffect(document) {
        if (document == null) return@LaunchedEffect
        val writer = CustomerStatementWriter(resources, formats)
        val uri = withContext(Dispatchers.IO) {
            exportFile(context, fileName) { target -> writer.write(document, target) }
        }
        val shared = uri != null && try {
            chooser.launch(pdfShareIntent(uri))
            true
        } catch (_: ActivityNotFoundException) {
            withContext(Dispatchers.IO) { ExportFolder.clear(context) }
            false
        }
        onAction(CustomerDetailAction.StatementShared(shared))
    }
}

/**
 * Muestra [message] con los mensajes de la navegación. Tras anonimizar, el aviso explica que el dato
 * sigue en copias anteriores y ofrece `Actualizar copias`.
 */
@Composable
private fun ShowMessage(message: CustomerDetailMessage?, onAction: (CustomerDetailAction) -> Unit) {
    val messages = LocalAppMessages.current
    val text = message?.let {
        stringResource(
            when (it) {
                CustomerDetailMessage.Saved -> R.string.customer_saved
                CustomerDetailMessage.PaymentRecorded -> R.string.customer_payment_recorded
                CustomerDetailMessage.PaymentVoided -> R.string.customer_payment_voided
                CustomerDetailMessage.Adjusted -> R.string.customer_adjust_saved
                CustomerDetailMessage.Archived -> R.string.customer_archived
                CustomerDetailMessage.Reactivated -> R.string.customer_reactivated
                CustomerDetailMessage.Anonymized -> R.string.customer_anonymize_copies_notice
                CustomerDetailMessage.StatementFailed -> R.string.customer_statement_failed
                CustomerDetailMessage.ReminderNoApp -> R.string.customer_reminder_no_app
                is CustomerDetailMessage.Failed -> saveErrorText(it.outOfSpace)
            },
        )
    }
    val actionLabel = stringResource(R.string.customer_update_copies_action)
    val variant = when (message) {
        null, CustomerDetailMessage.Saved, CustomerDetailMessage.PaymentRecorded,
        CustomerDetailMessage.PaymentVoided,
        CustomerDetailMessage.Adjusted, CustomerDetailMessage.Archived,
        CustomerDetailMessage.Reactivated, CustomerDetailMessage.Anonymized,
        -> SnackbarVariant.SUCCESS

        CustomerDetailMessage.StatementFailed, CustomerDetailMessage.ReminderNoApp,
        is CustomerDetailMessage.Failed,
        ->
            SnackbarVariant.ERROR
    }
    LaunchedEffect(message) {
        if (text != null) {
            val anonymized = message == CustomerDetailMessage.Anonymized
            messages.show(AppSnackbarVisuals(text, variant, actionLabel.takeIf { anonymized })) {
                onAction(CustomerDetailAction.UpdateCopies)
            }
            onAction(CustomerDetailAction.MessageShown)
        }
    }
}

/**
 * Entrega [outgoing] a otra app, por un toque de la persona: con teléfono, `ACTION_SENDTO` con
 * `smsto:`; si no, `ACTION_SEND` con el selector del sistema. Sin hosts fijos de terceros: la
 * persona elige la app y envía desde allí. Los marcadores se llenan aquí, con el formato del
 * negocio.
 */
@Composable
private fun SendReminder(
    outgoing: OutgoingReminder?,
    view: CustomerDetailView,
    formats: AppFormats,
    onAction: (CustomerDetailAction) -> Unit,
) {
    val context = LocalContext.current
    val amount = rememberMoneyDisplay(view.customer.balance, formats.money).text.text
    val dueDate = when (val due = view.due) {
        is DueStatus.DueOn -> formats.date.dayMonthYear(due.on)
        is DueStatus.Overdue -> formats.date.dayMonthYear(due.since)
        DueStatus.Clear, DueStatus.Credit, DueStatus.NoDue -> null
    }
    val name = customerName(view)
    LaunchedEffect(outgoing) {
        if (outgoing == null) return@LaunchedEffect
        val values = ReminderValues(name, amount, outgoing.businessName.orEmpty(), dueDate)
        val text = ReminderRules.fill(outgoing.template, values, outgoing.details)
        val intent = outgoing.phone?.let { phone ->
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null))
                .putExtra(SMS_BODY, text)
        } ?: Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(TEXT_TYPE).putExtra(Intent.EXTRA_TEXT, text),
            null,
        )
        val sent = try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
        onAction(CustomerDetailAction.ReminderSent(sent))
    }
}

/**
 * Abre el marcador del sistema con [phone] escrito, sin llamar: la persona decide si llama. No pide
 * ningún permiso. Si no hay marcador, como en una tableta sin teléfono, no pasa nada.
 */
private fun openDialer(context: Context, phone: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
    } catch (_: ActivityNotFoundException) {
        // Sin marcador no hay nada que abrir; el número sigue a la vista.
    }
}

private const val PDF_EXTENSION = ".pdf"

/** El texto que la app de mensajes pone en el cuerpo del SMS. */
private const val SMS_BODY = "sms_body"

private const val TEXT_TYPE = "text/plain"
