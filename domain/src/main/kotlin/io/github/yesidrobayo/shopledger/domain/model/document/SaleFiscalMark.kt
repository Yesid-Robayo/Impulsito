package io.github.yesidrobayo.shopledger.domain.model.document

import kotlin.time.Instant

/**
 * La marca de una venta: lo que la persona anotó de ella respecto del sistema que usa fuera de la
 * app. Vive aparte de la venta, que nunca cambia por marcarla.
 *
 * - [externalReference] es el número que la persona copió del otro sistema, tal cual, o `null`.
 * - [recordedAt] es cuándo la marcó como registrada, y solo existe con
 *   [FiscalMarkStatus.RECORDED].
 *
 * Lanza [IllegalArgumentException] si [recordedAt] no corresponde a [status].
 */
data class SaleFiscalMark(
    val saleId: Long,
    val status: FiscalMarkStatus,
    val externalReference: String?,
    val recordedAt: Instant?,
) {
    init {
        require((status == FiscalMarkStatus.RECORDED) == (recordedAt != null)) {
            "Solo una venta registrada aparte tiene fecha de registro"
        }
    }
}
