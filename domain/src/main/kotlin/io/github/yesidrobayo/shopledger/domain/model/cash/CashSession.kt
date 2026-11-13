package io.github.yesidrobayo.shopledger.domain.model.cash

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/** Si una sesión de caja sigue abierta o ya se cerró. Una cerrada no vuelve a abrirse. */
enum class CashSessionStatus {
    OPEN,
    CLOSED,
}

/**
 * Una sesión de caja que el caso de uso va a abrir, todavía sin id: la cuenta de efectivo
 * [accountId], lo que había al abrir, [openingAmount], en la moneda de la cuenta, y cuándo.
 */
data class NewCashSession(
    val accountId: Long,
    val openingAmount: Money,
    val openedAt: Instant,
    val businessDate: BusinessDate,
)

/**
 * Lo que deja el cierre de una sesión: [expected] es la apertura más lo que movió la cuenta en la
 * sesión, [counted] lo contado y [difference] lo contado menos lo esperado.
 */
data class CashClosing(
    val expected: Money,
    val counted: Money,
    val difference: Money,
    val closedAt: Instant,
    val note: String?,
)

/**
 * Una sesión de caja guardada. Abierta solo tiene su apertura; cerrada guarda además [closing], y
 * ya no cambia nunca.
 */
data class CashSession(val id: Long, val opening: NewCashSession, val closing: CashClosing?) {
    val status: CashSessionStatus
        get() = if (closing == null) CashSessionStatus.OPEN else CashSessionStatus.CLOSED
}
