package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las lecturas de la pantalla de cuentas: las archivadas, lo que falta por acreditar y el documento
 * de un movimiento. Montos inventados.
 */
class MoneyAccountReadsTest {
    private val world = MoneyWorld()
    private val today = requireNotNull(BusinessDate.parse("2026-03-15"))
    private val time = RecordTime(MoneyWorld.NOW, today)

    private suspend fun append(draft: MoneyMovementDraft): Long = world.transactions.write {
        TxResult.Commit(
            (world.writer.append(draft, time, MoneyWorld.NOW) as MoneyAppendResult.Appended)
                .movementId,
        )
    }

    @Test
    fun `an archived account leaves the active list and shows among the archived`() = runTest {
        val id = (
            world.save()(null, MoneyAccountDraft("Banco Uno", MoneyAccountKind.BANK)) as
                SaveMoneyAccountResult.Saved
            ).accountId
        val archived = ObserveArchivedMoneyAccountsUseCase(world.accounts)
        assertTrue(archived().first().isEmpty())

        world.archive()(id)

        assertEquals(listOf(id), archived().first().map { it.id })
    }

    @Test
    fun `what settles after today is pending, net of its fee, until it is voided`() = runTest {
        val later = today.plusDays(2)
        val receipt = append(
            MoneyMovementDraft(
                world.cashId,
                MoneyMovementType.SALE_RECEIPT,
                Money(12_000),
                MoneyOrigin.SalePayment(1),
                availableDate = later,
            ),
        )
        append(
            MoneyMovementDraft(
                world.cashId,
                MoneyMovementType.FEE,
                Money(-300),
                MoneyOrigin.SalePayment(1),
                availableDate = later,
            ),
        )
        // Lo que ya llegó hoy no está por acreditar.
        append(
            MoneyMovementDraft(
                world.cashId,
                MoneyMovementType.SALE_RECEIPT,
                Money(5_000),
                MoneyOrigin.SalePayment(2),
                availableDate = today,
            ),
        )
        val pending = ObservePendingSettlementUseCase(world.ledger)

        assertEquals(mapOf(world.cashId to Money(11_700)), pending(today).first())
        assertEquals(emptyMap<Long, Money>(), pending(later).first())

        append(
            MoneyMovementDraft(
                world.cashId,
                MoneyMovementType.MOVEMENT_VOID,
                Money(-12_000),
                MoneyOrigin.Void(receipt),
            ),
        )
        assertEquals(mapOf(world.cashId to Money(-300)), pending(today).first())
    }

    @Test
    fun `a movement from a document leads there, and a manual one leads nowhere`() = runTest {
        val expense = append(
            MoneyMovementDraft(
                world.cashId,
                MoneyMovementType.EXPENSE_PAYMENT,
                Money(-2_000),
                MoneyOrigin.Expense(7),
            ),
        )
        val draw = append(
            MoneyMovementDraft(
                world.cashId,
                MoneyMovementType.OWNER_DRAW,
                Money(-1_000),
                MoneyOrigin.None,
            ),
        )
        val find = FindMoneyDocumentUseCase(world.ledger)

        assertEquals(MoneyDocument.Expense(7), find(expense))
        assertNull(find(draw))
        assertNull(find(MISSING))
    }

    @Test
    fun `accounts are in use only when the usage says so`() = runTest {
        val usage = MutableStateFlow(emptySet<BusinessTool>())
        val inUse = ObserveMoneyAccountsInUseUseCase { usage }
        assertEquals(false, inUse().first())

        usage.value = setOf(BusinessTool.CUSTOMERS, BusinessTool.MONEY_ACCOUNTS)
        assertEquals(true, inUse().first())
    }

    private companion object {
        const val MISSING = 999L
    }
}
