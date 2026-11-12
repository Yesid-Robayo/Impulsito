package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.domain.usecase.expense.UndoExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.UndoPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseUseCase
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Deshacer una compra o un gasto recién guardado, desde el aviso que sigue a guardarlo.
 *
 * El documento ya se guardó cuando la persona toca `Deshacer`, y quizá ya salió de su pantalla, así
 * que esto no vive en un ViewModel: corre en [scope], que dura lo que la app. Una sola transacción
 * lo anula, así que si el proceso se cierra a la mitad, el documento queda activo entero.
 *
 * @param undo anula el documento con el motivo de deshacer: `true` si quedó anulado, también si ya
 *   lo estaba; `false` si no se pudo, y sigue contando.
 */
class DocumentUndo(private val scope: CoroutineScope, private val undo: suspend (Long) -> Boolean) {
    /** Deshace el documento [id] y avisa a [onDone] si quedó anulado. */
    fun undo(id: Long, onDone: (Boolean) -> Unit): Job = scope.launch { onDone(undo(id)) }
}

/** Los textos del aviso de deshacer, ya en el idioma de la app. */
data class DocumentUndoTexts(
    val undoLabel: String,
    val undone: String,
    val failed: String,
    val retry: String,
)

/**
 * Muestra [saved], el aviso de documento guardado, con `Deshacer`. Por llevar acción dura 10
 * segundos, o más con TalkBack según la preferencia del sistema. Si deshacer falla, el aviso de
 * error queda hasta que la persona actúe, con `Reintentar`: el documento sigue contando y quien lo
 * deshizo cree que ya no.
 */
fun AppMessages.showUndoable(
    saved: String,
    id: Long,
    undo: DocumentUndo,
    texts: DocumentUndoTexts,
) {
    show(AppSnackbarVisuals(saved, SnackbarVariant.SUCCESS, texts.undoLabel)) {
        runUndo(id, undo, texts)
    }
}

private fun AppMessages.runUndo(id: Long, undo: DocumentUndo, texts: DocumentUndoTexts) {
    undo.undo(id) { undone ->
        if (undone) {
            show(AppSnackbarVisuals(texts.undone, SnackbarVariant.SUCCESS))
        } else {
            show(AppSnackbarVisuals(texts.failed, SnackbarVariant.ERROR, texts.retry)) {
                runUndo(id, undo, texts)
            }
        }
    }
}

/**
 * Si deshacer un gasto lo dejó anulado. Ya anulado cuenta como hecho: lo que se pidió es que no
 * cuente, y no cuenta. Cada rechazo se nombra, sin rama por defecto.
 */
internal fun undoneExpense(result: VoidExpenseResult?): Boolean = when (result) {
    VoidExpenseResult.Voided, VoidExpenseResult.AlreadyVoided -> true

    null,
    VoidExpenseResult.ExpenseNotFound,
    VoidExpenseResult.ReasonNotAllowed,
    VoidExpenseResult.NoteTooLong,
    is VoidExpenseResult.StorageFailed,
    -> false
}

/** Si deshacer una compra la dejó anulada, con el mismo criterio que un gasto. */
internal fun undonePurchase(result: VoidPurchaseResult?): Boolean = when (result) {
    VoidPurchaseResult.Voided, VoidPurchaseResult.AlreadyVoided -> true

    null,
    VoidPurchaseResult.PurchaseNotFound,
    VoidPurchaseResult.ReasonNotAllowed,
    VoidPurchaseResult.NoteTooLong,
    is VoidPurchaseResult.UnknownTimeZone,
    VoidPurchaseResult.LedgerDamaged,
    is VoidPurchaseResult.StorageFailed,
    -> false
}

/**
 * Deshacer el gasto recién guardado, armado con la base de la app. Si la base fue al rescate en el
 * intermedio, deshacer falla y el gasto sigue activo.
 */
@Composable
fun rememberExpenseUndo(): DocumentUndo {
    val container = (LocalContext.current.applicationContext as ShopLedgerApp).container
    return remember(container) {
        DocumentUndo(container.applicationScope) { expenseId ->
            val result = when (val access = container.data.open()) {
                is DataAccess.Ready -> {
                    val ports = access.ports
                    val void =
                        VoidExpenseUseCase(ports.transactions, ports.expenses, container.clock)
                    UndoExpenseUseCase(void)(expenseId)
                }

                is DataAccess.Rescue -> null
            }
            undoneExpense(result)
        }
    }
}

/**
 * Deshacer la compra recién registrada, armado con la base de la app, con sus alertas de stock en la
 * misma transacción. Si la base fue al rescate, deshacer falla y la compra sigue activa.
 */
@Composable
fun rememberPurchaseUndo(): DocumentUndo {
    val container = (LocalContext.current.applicationContext as ShopLedgerApp).container
    return remember(container) {
        DocumentUndo(container.applicationScope) { purchaseId ->
            val result = when (val access = container.data.open()) {
                is DataAccess.Ready -> {
                    val ports = access.ports
                    val void = VoidPurchaseUseCase(
                        transactions = ports.transactions,
                        settings = ports.settings,
                        products = ports.products,
                        purchases = ports.purchases,
                        ledger = ports.stockLedger,
                        clock = container.clock,
                        alerts = ports.lowStockAlerts,
                    )
                    UndoPurchaseUseCase(void)(purchaseId)
                }

                is DataAccess.Rescue -> null
            }
            undonePurchase(result)
        }
    }
}
