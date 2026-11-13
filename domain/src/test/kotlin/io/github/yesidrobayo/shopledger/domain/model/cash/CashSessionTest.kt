package io.github.yesidrobayo.shopledger.domain.model.cash

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Una sesión sin cierre está abierta; con su cierre, cerrada para siempre. */
class CashSessionTest {
    private val opening = NewCashSession(
        accountId = 1,
        openingAmount = Money(20_000),
        openedAt = AT,
        businessDate = BusinessDate(LocalDate(2026, 3, 15)),
    )

    @Test
    fun `a session without closing is open`() {
        assertEquals(CashSessionStatus.OPEN, CashSession(1, opening, closing = null).status)
    }

    @Test
    fun `a session with its closing is closed`() {
        val closing = CashClosing(Money(40_000), Money(39_500), Money(-500), AT, note = null)

        assertEquals(CashSessionStatus.CLOSED, CashSession(1, opening, closing).status)
    }

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(0)
    }
}
