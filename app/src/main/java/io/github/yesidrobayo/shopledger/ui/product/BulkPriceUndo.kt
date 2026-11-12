package io.github.yesidrobayo.shopledger.ui.product

import android.content.res.Resources
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.UndoBulkPriceResult
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Deshacer la actualización masiva recién aplicada, desde el aviso `N precios actualizados`.
 *
 * La pantalla ya se cerró cuando la persona toca `Deshacer`, así que esto no vive en su ViewModel:
 * corre en [scope], que dura lo que la app. Deshacer es una sola transacción: si el proceso se cierra
 * a la mitad, los precios quedan como los dejó la actualización, nunca a medias.
 *
 * @param undoBatch deshace el lote; `null` si la base no abre sin riesgo y la app va al rescate.
 */
class BulkPriceUndo(
    private val scope: CoroutineScope,
    private val undoBatch: suspend (Uuid) -> UndoBulkPriceResult?,
) {
    /** Deshace [batchId] y avisa a [onDone] cómo terminó. */
    fun undo(batchId: Uuid, onDone: (BulkMessage) -> Unit): Job =
        scope.launch { onDone(BulkMessage.ofUndo(undoBatch(batchId))) }
}

/**
 * El texto de [message], ya en el idioma de la app. Se arma con [resources] y no en la composición,
 * porque el aviso de deshacer llega cuando la pantalla ya se cerró.
 */
fun bulkMessageText(resources: Resources, message: BulkMessage): String = when (message) {
    is BulkMessage.Applied ->
        resources.getQuantityString(
            R.plurals.confirm_prices_updated,
            message.changed,
            message.changed,
        )

    BulkMessage.NothingChanged -> resources.getString(R.string.bulk_nothing_changed)

    is BulkMessage.Undone -> {
        val partial = resources.getQuantityString(
            R.plurals.notice_undo_partial,
            message.kept,
            message.kept,
        )
        val undone = resources.getQuantityString(
            R.plurals.confirm_prices_undone,
            message.reverted,
            message.reverted,
        )
        when {
            message.reverted == 0 -> partial
            message.kept == 0 -> undone
            else -> "$undone $partial"
        }
    }

    BulkMessage.UndoGone -> resources.getString(R.string.bulk_undo_gone)

    is BulkMessage.Failed -> resources.getString(saveErrorText(message.outOfSpace))
}

/**
 * Cómo se ve [message]. Un fallo queda hasta que la persona actúe: si fue al deshacer, con
 * [retryLabel], porque los precios siguen cambiados y quien deshizo cree que no.
 */
fun bulkMessageVisuals(
    resources: Resources,
    message: BulkMessage,
    actionLabel: String? = null,
): AppSnackbarVisuals {
    val variant = when (message) {
        is BulkMessage.Applied, BulkMessage.NothingChanged, is BulkMessage.Undone ->
            SnackbarVariant.SUCCESS

        BulkMessage.UndoGone -> SnackbarVariant.INFO

        is BulkMessage.Failed -> SnackbarVariant.ERROR
    }
    return AppSnackbarVisuals(bulkMessageText(resources, message), variant, actionLabel)
}
