package io.github.yesidrobayo.shopledger.domain.model.cash

import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * Un billete o una moneda que la persona escribió, con su [value] en la unidad menor. No hay
 * denominaciones iniciales: la app no supone ningún país. Una inactiva ya no se ofrece al contar,
 * pero los conteos viejos la conservan.
 */
data class CashDenomination(
    val id: Long,
    val value: Money,
    val isActive: Boolean,
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Cuántas piezas, [count], de la denominación [denominationId] se contaron. */
data class DenominationCount(val denominationId: Long, val count: Long)

/** En qué momento se contó: al abrir o al cerrar la caja. */
enum class CashCountPhase {
    OPENING,
    CLOSING,
}

/**
 * Lo que la persona contó: un monto escrito, o piezas por denominación, que se multiplican por su
 * valor y se suman.
 */
sealed interface CashCounted {
    data class Amount(val amount: Money) : CashCounted

    data class ByDenomination(val counts: List<DenominationCount>) : CashCounted
}
