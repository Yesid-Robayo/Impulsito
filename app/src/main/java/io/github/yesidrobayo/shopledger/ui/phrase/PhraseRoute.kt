package io.github.yesidrobayo.shopledger.ui.phrase

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.github.yesidrobayo.shopledger.ui.sale.SaleUndo
import io.github.yesidrobayo.shopledger.ui.sale.SaleUndoTexts
import kotlinx.coroutines.withContext

/**
 * `Decir o escribir` en la navegación: arma su ViewModel con los casos de uso de la base abierta,
 * abre el servicio de voz cuando se pide y dice cómo terminó registrar.
 *
 * - **La voz directa** solo existe si la persona la encendió en Ajustes y el teléfono tiene un
 *   servicio de voz. Entonces `Decir` abre ese servicio de una vez, con el idioma de la app y
 *   pidiendo que prefiera trabajar sin conexión, y el texto que devuelve se lee como si se hubiera
 *   escrito. La app nunca usa el micrófono: lo opera el servicio del teléfono, por el toque de la
 *   persona. Si no abre, queda el teclado.
 * - **Al registrar,** el mensaje dice qué se registró y, si la pantalla de esa operación lo ofrece,
 *   trae `Deshacer` durante 10 segundos: la venta, la compra y el gasto. La hoja sigue abierta y
 *   lista para otra frase.
 * - **Cerrar** descarta la tarjeta: nada se registró.
 */
@Composable
fun PhraseRoute(key: AppNavKey.Phrase, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val resources = LocalResources.current
    val appContext = LocalContext.current.applicationContext
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
                            VoiceInput.isAvailable(appContext)
                        }
                    available && container.voiceInput.load()
                },
                initialText = key.text,
            )
        }
    when (model) {
        ScreenModel.Opening ->
            PhraseScreen(
                PhraseUiState(),
                onAction = { if (it == PhraseAction.Close) navigator.back() },
            )

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val haptics = LocalHapticFeedback.current
            // El servicio de voz del teléfono: devuelve el texto, o nada si la persona cancela.
            val languageTag = formats.uiLocale.toLanguageTag()
            val voice =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    VoiceInput.textOf(result.resultCode, result.data)?.let {
                        viewModel.onAction(PhraseAction.Heard(it))
                    }
                }
            LaunchedEffect(uiState.dictate) {
                if (!uiState.dictate) return@LaunchedEffect
                val opened =
                    try {
                        voice.launch(VoiceInput.intent(languageTag))
                        true
                    } catch (_: ActivityNotFoundException) {
                        // El servicio se desinstaló o se desactivó: queda el teclado.
                        false
                    }
                viewModel.onAction(PhraseAction.DictateHandled(opened))
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
                    if (action == PhraseAction.Close) {
                        navigator.back()
                    } else {
                        viewModel.onAction(action)
                    }
                },
            )
        }
    }
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
