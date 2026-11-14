package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant

/**
 * Un lote abierto, con vencimiento y con stock, tal como lo lee el aviso de vencimiento: lo que
 * tiene sumado en todas las ubicaciones, porque el aviso es del lote y no de dónde está.
 *
 * [noticeDays] son los días de aviso propios del producto, o `null` si sigue los de los ajustes.
 * [expiryNotifiedAt] dice si ya se avisó, para avisar una sola vez. [baseUnitId] es la unidad en
 * que se cuenta [onHand].
 */
data class DatedLotStock(
    val lotId: Long,
    val productId: Long,
    val productName: String,
    val code: String?,
    val expiryDate: BusinessDate,
    val onHand: QuantityMilli,
    val baseUnitId: Long,
    val noticeDays: Int?,
    val expiryNotifiedAt: Instant?,
)

/**
 * Un lote que ya está dentro de sus días de aviso, o vencido. [daysLeft] son los días que faltan
 * desde hoy: cero vence hoy, y negativo ya venció.
 */
data class ExpiringLot(val lot: DatedLotStock, val daysLeft: Int) {
    val isExpired: Boolean get() = daysLeft < 0
}
