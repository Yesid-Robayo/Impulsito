package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.SaleFiscalMark
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.SaleFiscalMarkRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.document.FiscalInputs
import io.github.yesidrobayo.shopledger.domain.rule.document.ReferenceInput

/** Qué hacer con la referencia externa al marcar. */
sealed interface ReferenceChange {
    /** Conservar la que ya tenga cada venta: así marca la selección de varias, sin referencia. */
    data object Keep : ReferenceChange

    /** Guardar [text], tal como lo escribió la persona en la hoja de una venta. Vacío la quita. */
    data class Write(val text: String) : ReferenceChange
}

/** Cómo terminó marcar una o varias ventas. */
sealed interface MarkFiscalResult {
    /** Quedaron marcadas las [count] ventas. Si ya estaban así, no se escribió nada. */
    data class Marked(val count: Int) : MarkFiscalResult

    /** No se eligió ninguna venta. */
    data object NothingSelected : MarkFiscalResult

    /** La referencia pasa del largo permitido. Nada se guardó. */
    data object ReferenceTooLong : MarkFiscalResult

    /** Alguna venta ya no existe o se anuló mientras tanto. Nada se guardó, ni de las demás. */
    data object SaleUnavailable : MarkFiscalResult

    /** La base no pudo escribir. Nada quedó a medias, y la venta no cambió. */
    data class Failed(val failure: StorageFailure) : MarkFiscalResult
}

/**
 * Marca una o varias ventas activas como pendientes, registradas aparte o sin necesidad de
 * registro, en una sola transacción: con varias, o todas quedan marcadas o ninguna.
 *
 * - Nunca toca la venta: la marca vive en su propia tabla.
 * - La fecha de registro se pone al marcar `RECORDED` y se conserva si ya lo estaba; con otra
 *   marca se quita.
 * - Volver a pendiente una venta sin marca y sin referencia no escribe nada: sin marca ya está
 *   pendiente.
 * - Si nada cambia, deshace la transacción para no contar un cambio que no existe.
 *
 * No usa la red ni habla con ninguna autoridad.
 */
class MarkSalesFiscalUseCase(
    private val transactions: TransactionRunner,
    private val marks: SaleFiscalMarkRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        saleIds: Collection<Long>,
        status: FiscalMarkStatus,
        reference: ReferenceChange,
    ): MarkFiscalResult {
        val ids = saleIds.distinct()
        if (ids.isEmpty()) return MarkFiscalResult.NothingSelected
        val written = when (reference) {
            ReferenceChange.Keep -> null

            is ReferenceChange.Write -> when (val input = FiscalInputs.reference(reference.text)) {
                ReferenceInput.TooLong -> return MarkFiscalResult.ReferenceTooLong
                is ReferenceInput.Valid -> input
            }
        }
        return try {
            transactions.write { markAll(ids, status, written) }
        } catch (failure: StorageFailureException) {
            MarkFiscalResult.Failed(failure.failure)
        }
    }

    /**
     * Marca cada venta con [status]. [reference] nulo conserva la referencia de cada una; si no,
     * la reemplaza por su valor.
     */
    private suspend fun markAll(
        ids: List<Long>,
        status: FiscalMarkStatus,
        reference: ReferenceInput.Valid?,
    ): TxResult<MarkFiscalResult> {
        val now = clock.now()
        var changed = false
        for (saleId in ids) {
            if (marks.findSaleStatus(saleId) != DocumentStatus.ACTIVE) {
                return TxResult.Rollback(MarkFiscalResult.SaleUnavailable)
            }
            val current = marks.findMark(saleId)
            val externalReference =
                if (reference == null) current?.externalReference else reference.value
            val recordedAt = when (status) {
                FiscalMarkStatus.RECORDED -> current?.recordedAt ?: now
                FiscalMarkStatus.PENDING, FiscalMarkStatus.NOT_NEEDED -> null
            }
            val next = SaleFiscalMark(saleId, status, externalReference, recordedAt)
            when {
                current == next -> Unit

                current == null && status == FiscalMarkStatus.PENDING && externalReference == null
                -> Unit

                current == null -> {
                    marks.insert(next, now)
                    changed = true
                }

                else -> {
                    marks.update(next, now)
                    changed = true
                }
            }
        }
        val result = MarkFiscalResult.Marked(ids.size)
        return if (changed) TxResult.Commit(result) else TxResult.Rollback(result)
    }
}
