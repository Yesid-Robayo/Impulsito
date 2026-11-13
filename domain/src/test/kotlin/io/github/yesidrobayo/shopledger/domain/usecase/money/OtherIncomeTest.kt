package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyKind
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyRequest
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Otros ingresos: entran a la cuenta como un movimiento aparte, se suman por período sin mezclar
 * monedas, y uno anulado deja de contar. Montos inventados, con valores a mano.
 */
class OtherIncomeTest {
    private val world = MoneyWorld()
    private val today = ReportPeriod.Day(requireNotNull(BusinessDate.parse("2026-03-15")))
    private val yesterday = ReportPeriod.Day(requireNotNull(BusinessDate.parse("2026-03-14")))

    private fun record() = RecordManualMoneyUseCase(
        world.transactions,
        world.settings,
        world.accounts,
        world.writer,
        world.clock,
    )

    private suspend fun income(account: Long, amount: Long): Long = (
        record()(
            ManualMoneyRequest(account, ManualMoneyKind.OTHER_INCOME, Money(amount), "Reintegro"),
        )
            as RecordManualMoneyResult.Recorded
        ).movementId

    @Test
    fun `other income adds per period, without other currencies or voided ones`() = runTest {
        val foreign = (
            world.save()(
                null,
                MoneyAccountDraft("Divisas", MoneyAccountKind.OTHER, MoneyWorld.OTHER),
            ) as SaveMoneyAccountResult.Saved
            ).accountId
        val observe = ObserveOtherIncomeUseCase(world.settings, world.ledger)
        assertEquals(Money.ZERO, observe(today).first())

        val first = income(world.cashId, 12_000)
        income(world.cashId, 3_000)
        income(foreign, 99_999)
        // Un retiro no es otro ingreso.
        record()(ManualMoneyRequest(world.cashId, ManualMoneyKind.OWNER_DRAW, Money(500)))

        assertEquals(Money(15_000), observe(today).first())
        assertEquals(Money.ZERO, observe(yesterday).first())
        assertEquals(MoneyMovementType.OTHER_INCOME, world.ledger.findById(first)?.type)

        world.void()(first)
        assertEquals(Money(3_000), observe(today).first())
        assertEquals(Money(2_500), world.accounts.findById(world.cashId)?.balance)
    }
}
