package io.github.yesidrobayo.shopledger.ui.sale

import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Cómo terminó deshacer la última venta, como lo ve la persona. */
enum class SaleUndoOutcome {
    /** La venta quedó anulada: el stock volvió y deja de contar. */
    UNDONE,

    /** No se pudo anular: la venta sigue activa, y se puede intentar otra vez. */
    FAILED,
}

/**
 * Deshacer la venta recién registrada, desde el mensaje que sigue a confirmarla.
 *
 * La venta nueva ya se cerró cuando la persona toca `Deshacer`, así que esto no vive en su
 * ViewModel: corre en [scope], que dura lo que la app. Una sola transacción anula la venta, así que
 * si el proceso se cierra a la mitad, la venta queda activa entera, nunca a medias.
 *
 * @param undoSale anula la venta con el motivo de deshacer; `null` si la base no abre sin riesgo y
 *   la app va al rescate.
 */
class SaleUndo(
    private val scope: CoroutineScope,
    private val undoSale: suspend (Long) -> VoidSaleResult?,
) {
    /** Deshace la venta [saleId] y avisa a [onDone] cómo terminó. */
    fun undo(saleId: Long, onDone: (SaleUndoOutcome) -> Unit): Job =
        scope.launch { onDone(outcomeOf(undoSale(saleId))) }

    companion object {
        /**
         * Lo que ve la persona de cada resultado. Ya anulada cuenta como hecho: lo que pidió es que
         * la venta no cuente, y no cuenta. Cada rechazo se nombra, sin rama por defecto, para que un
         * resultado nuevo obligue a decidir aquí.
         */
        fun outcomeOf(result: VoidSaleResult?): SaleUndoOutcome = when (result) {
            VoidSaleResult.Voided, VoidSaleResult.AlreadyVoided -> SaleUndoOutcome.UNDONE

            null,
            VoidSaleResult.SaleNotFound,
            VoidSaleResult.HasActiveReturns,
            VoidSaleResult.ReasonNotAllowed,
            VoidSaleResult.NoteTooLong,
            is VoidSaleResult.UnknownTimeZone,
            VoidSaleResult.LedgerDamaged,
            is VoidSaleResult.StorageFailed,
            -> SaleUndoOutcome.FAILED
        }

        /**
         * El mensaje de venta registrada. Lleva `Deshacer`, y por llevar acción dura 10 segundos;
         * pasado ese tiempo, la venta se anula desde su detalle. Con TalkBack, el sistema lo alarga
         * según la preferencia de la persona.
         */
        fun registeredMessage(text: String, undoLabel: String): AppSnackbarVisuals =
            AppSnackbarVisuals(text, SnackbarVariant.SUCCESS, undoLabel)

        /**
         * El mensaje tras deshacer. Si falló, queda hasta que la persona actúe, con `Reintentar`,
         * porque la venta sigue contando y quien la deshizo cree que ya no.
         */
        fun outcomeMessage(outcome: SaleUndoOutcome, texts: SaleUndoTexts): AppSnackbarVisuals =
            when (outcome) {
                SaleUndoOutcome.UNDONE -> AppSnackbarVisuals(texts.undone, SnackbarVariant.SUCCESS)

                SaleUndoOutcome.FAILED ->
                    AppSnackbarVisuals(texts.failed, SnackbarVariant.ERROR, texts.retry)
            }
    }
}

/** Los textos de deshacer, ya en el idioma de la app. */
data class SaleUndoTexts(val undone: String, val failed: String, val retry: String)
