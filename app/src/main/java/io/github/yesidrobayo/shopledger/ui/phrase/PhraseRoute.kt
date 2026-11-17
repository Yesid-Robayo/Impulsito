package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.navigation.AppMessages
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.DocumentUndoTexts
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import io.github.yesidrobayo.shopledger.navigation.rememberExpenseUndo
import io.github.yesidrobayo.shopledger.navigation.rememberPurchaseUndo
import io.github.yesidrobayo.shopledger.navigation.rememberSaleUndo
import io.github.yesidrobayo.shopledger.navigation.showUndoable
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpArticleId
import io.github.yesidrobayo.shopledger.ui.sale.SaleUndo
import io.github.yesidrobayo.shopledger.ui.sale.SaleUndoTexts
import kotlinx.coroutines.withContext

/**
 * `Decir o escribir` en la navegación: arma su ViewModel con los casos de uso de la base abierta,
 * toma lo que `Decir` escuchó antes de abrirla, escucha otra vez con el micrófono del campo y dice
 * cómo terminó registrar.
 *
 * - **Lo oído por `Decir`** llega por la navegación, en memoria: la frase se lee al abrir, como si
 *   se hubiera escrito, y la persona ve la tarjeta. Si `Decir` no pudo escuchar, la hoja abre con
 *   el teclado y el aviso de por qué. Si la hoja ya estaba abierta, lo toma igual.
 * - **El micrófono del campo** existe si escuchar está encendido en Ajustes y el teléfono tiene un
 *   servicio de voz. Abre la misma pantalla de escuchar encima de la hoja; cancelarla deja el
 *   teclado.
 * - **Al registrar,** el mensaje dice qué se registró y, si la pantalla de esa operación lo ofrece,
 *   trae `Deshacer` durante 10 segundos: la venta, la compra y el gasto. La hoja sigue abierta y
 *   lista para otra frase.
 * - **Cerrar** descarta la tarjeta: nada se registró.
 */
@Composable
fun PhraseRoute(key: AppNavKey.Phrase, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val appContext = context.applicationContext
    // Lo que `Decir` escuchó antes de abrir la hoja: se toma una vez, al aparecer.
    val said = remember { navigator.said.take() }
    val heardOnOpen = (said as? VoiceResult.Heard)?.text
    val numbers =
        PhraseInputFormat(
            moneySymbols = formats.money.inputSymbols,
            currencyDecimals = formats.money.currencyDecimals,
            quantitySymbols = formats.quantity.inputSymbols,
        )
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            val container = dependencies.container
            val useCases = phraseUseCases(dependencies, resources, formats)
            PhraseViewModel(
                read = useCases.read,
                correct = useCases.correct,
                register = useCases.register,
                savedState = savedState,
                numbers = numbers,
                computation = container.dispatchers.default,
                voiceReady = {
                    val available =
                        withContext(container.dispatchers.io) {
                            PhoneSpeechEngine.isAvailable(appContext)
                        }
                    available && container.voiceInput.load()
                },
                initialText = heardOnOpen ?: key.text,
                initialNotice = (said as? VoiceResult.Keyboard)?.notice,
            )
        }
    when (model) {
        // Con una frase oída, la hoja ya se ve leyéndola: el teclado no salta antes de la tarjeta.
        ScreenModel.Opening ->
            PhraseScreen(
                if (heardOnOpen != null) {
                    PhraseUiState(text = heardOnOpen, stage = PhraseStage.Reading)
                } else {
                    PhraseUiState()
                },
                onAction = { if (it == PhraseAction.Close) navigator.back() },
            )

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val haptics = LocalHapticFeedback.current
            // El micrófono del campo: la misma pantalla de escuchar, encima de la hoja.
            val capture = remember { VoiceCapture() }
            VoiceCaptureHost(capture, offerTyping = false)
            LaunchedEffect(uiState.listening) {
                if (!uiState.listening) return@LaunchedEffect
                capture.start { result -> viewModel.onAction(result.toAction()) }
            }
            // `Decir` desde el riel con la hoja ya abierta: lo oído llega después de abrirla.
            val pendingSaid by navigator.said.toTake.collectAsStateWithLifecycle()
            LaunchedEffect(pendingSaid) {
                if (pendingSaid == null) return@LaunchedEffect
                navigator.said.take()?.let { viewModel.onAction(it.toAction()) }
            }
            val registered = uiState.registered
            val doneText = registered?.let { stringResource(doneMessage(it.intent)) }
            val undoLabel = stringResource(R.string.action_undo)
            val retry = stringResource(R.string.action_retry)
            val saleTexts =
                SaleUndoTexts(
                    undone = stringResource(R.string.confirm_sale_voided),
                    failed = stringResource(R.string.error_sale_void),
                    retry = retry,
                )
            val purchaseTexts =
                DocumentUndoTexts(
                    undoLabel = undoLabel,
                    undone = stringResource(R.string.confirm_purchase_voided),
                    failed = stringResource(R.string.error_purchase_void),
                    retry = retry,
                )
            val expenseTexts =
                DocumentUndoTexts(
                    undoLabel = undoLabel,
                    undone = stringResource(R.string.confirm_expense_voided),
                    failed = stringResource(R.string.error_expense_void),
                    retry = retry,
                )
            val saleUndo = rememberSaleUndo()
            val purchaseUndo = rememberPurchaseUndo()
            val expenseUndo = rememberExpenseUndo()
            LaunchedEffect(registered) {
                if (registered == null || doneText == null) return@LaunchedEffect
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.onAction(PhraseAction.RegisteredShown)
                // El mensaje vive en la navegación: sigue a la vista aunque se cierre la hoja.
                val id = registered.documentId
                when {
                    id == null ->
                        messages.show(AppSnackbarVisuals(doneText, SnackbarVariant.SUCCESS))

                    registered.intent == PhraseIntent.SALE ->
                        messages.show(SaleUndo.registeredMessage(doneText, undoLabel)) {
                            undoSale(saleUndo, messages, id, saleTexts)
                        }

                    registered.intent == PhraseIntent.PURCHASE ->
                        messages.showUndoable(doneText, id, purchaseUndo, purchaseTexts)

                    registered.intent == PhraseIntent.EXPENSE ->
                        messages.showUndoable(doneText, id, expenseUndo, expenseTexts)

                    // Las demás pantallas tampoco ofrecen deshacer: se corrigen desde su detalle.
                    else -> messages.show(AppSnackbarVisuals(doneText, SnackbarVariant.SUCCESS))
                }
            }
            PhraseScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        PhraseAction.Close -> navigator.back()

                        // La hoja se cierra y queda la venta de siempre en su lugar.
                        PhraseAction.SellByHand -> {
                            navigator.back()
                            navigator.sell()
                        }

                        PhraseAction.MicHelp -> navigator.open(MIC_HELP)

                        PhraseAction.OpenMicSettings -> context.openMicrophoneSettings()

                        else -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/** Lo que hace la hoja con lo que terminó de escuchar. */
private fun VoiceResult.toAction(): PhraseAction = when (this) {
    is VoiceResult.Heard -> PhraseAction.Heard(text)
    is VoiceResult.Keyboard -> PhraseAction.ListenEnded(notice)
    VoiceResult.Cancelled -> PhraseAction.ListenEnded(null)
}

/** Deshace la venta [saleId] y dice cómo terminó. Si falló, `Reintentar` lo intenta otra vez. */
private fun undoSale(undo: SaleUndo, messages: AppMessages, saleId: Long, texts: SaleUndoTexts) {
    undo.undo(saleId) { outcome ->
        messages.show(SaleUndo.outcomeMessage(outcome, texts)) {
            undoSale(undo, messages, saleId, texts)
        }
    }
}

/** El mensaje de lo que se acaba de registrar, sin montos: la tarjeta ya los mostró. */
@StringRes
internal fun doneMessage(intent: PhraseIntent): Int = when (intent) {
    PhraseIntent.SALE -> R.string.phrase_done_sale
    PhraseIntent.PURCHASE -> R.string.phrase_done_purchase
    PhraseIntent.EXPENSE -> R.string.phrase_done_expense
    PhraseIntent.PRODUCTION -> R.string.phrase_done_production
    PhraseIntent.STOCK_ADJUSTMENT -> R.string.phrase_done_stock
    PhraseIntent.CUSTOMER_PAYMENT -> R.string.phrase_done_customer_payment
    PhraseIntent.SUPPLIER_PAYMENT -> R.string.phrase_done_supplier_payment
    PhraseIntent.RECIPE -> R.string.phrase_done_recipe
    PhraseIntent.NEW_PRODUCT -> R.string.phrase_done_product
    PhraseIntent.COST_CORRECTION -> R.string.phrase_done_cost
    PhraseIntent.PRICE_CHANGE -> R.string.phrase_done_price
}

/** El artículo con los pasos para activar el dictado del teclado o la voz del teléfono. */
private val MIC_HELP = AppNavKey.Help(articleKey = HelpArticleId.REGISTER_BY_PHRASE.key)
