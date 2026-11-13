package io.github.yesidrobayo.shopledger.ui.cash

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashClosing
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCounted
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.DenominationCount
import io.github.yesidrobayo.shopledger.domain.model.cash.NewCashSession
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.CloseCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.SaveCashDenominationResult
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La caja con respuestas fijas del dominio: sin uso dice que es opcional; abierta muestra lo
 * esperado del ejemplo y avisa si es de otro día; abrir sugiere el saldo; cerrar pide lo contado,
 * pregunta con diferencia y cuenta por denominación; y las denominaciones no se repiten. Montos
 * inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashRegisterViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeCashOperations()
    private val numbers = CashNumberFormat(NumberSymbols(',', '-', DigitGrouping('.', 3, 3)), 0)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `a business that never opened the register sees it is optional`() = runTest(dispatcher) {
        val view = ready(open())

        assertTrue(view.isEmpty)
        assertNull(view.open)
    }

    @Test
    fun `an open register shows the expected example and says when it is from another day`() =
        runTest(dispatcher) {
            operations.sessions.value = listOf(openSession(TODAY))
            operations.movements.value = EXAMPLE
            val view = ready(open())

            val figures = requireNotNull(view.open).figures
            assertEquals(Money(20_000), figures.opening)
            assertEquals(Money(35_000), figures.sales)
            assertEquals(Money(-15_000), figures.cashOut)
            assertEquals(Money(40_000), figures.expected)
            assertFalse(requireNotNull(view.open).fromAnotherDay)

            operations.sessions.value = listOf(openSession(TODAY.plusDays(-1)))
            val yesterday = ready(open())
            assertTrue(requireNotNull(yesterday.open).fromAnotherDay)
        }

    @Test
    fun `opening suggests the account balance and opens with what was counted`() =
        runTest(dispatcher) {
            val model = open()
            model.onAction(CashRegisterAction.StartOpen)
            assertEquals("12000", model.uiState.value.openSheet?.count?.amountText)

            model.onAction(CashRegisterAction.CountAmount("11.500"))
            model.onAction(CashRegisterAction.ConfirmOpen)
            advanceUntilIdle()

            assertEquals(listOf(ACCOUNT to CashCounted.Amount(Money(11_500))), operations.opened)
            assertNull(model.uiState.value.openSheet)
            assertEquals(CashMessage.Opened, model.uiState.value.message)
        }

    @Test
    fun `a second open register on the account is said in the sheet, keeping what was written`() =
        runTest(dispatcher) {
            operations.openResult = OpenCashSessionResult.AlreadyOpen
            val model = open()
            model.onAction(CashRegisterAction.StartOpen)
            model.onAction(CashRegisterAction.ConfirmOpen)
            advanceUntilIdle()

            val sheet = requireNotNull(model.uiState.value.openSheet)
            assertEquals(CashFormNotice.AlreadyOpen, sheet.notice)
            assertEquals("12000", sheet.count.amountText)
            assertFalse(sheet.working)
        }

    @Test
    fun `closing asks for the count and asks first when there is a difference`() =
        runTest(dispatcher) {
            operations.sessions.value = listOf(openSession(TODAY))
            operations.movements.value = EXAMPLE
            val model = open()
            model.onAction(CashRegisterAction.StartClose)
            model.onAction(CashRegisterAction.ConfirmClose)
            advanceUntilIdle()
            assertEquals(
                setOf(CashFormError.AMOUNT_REQUIRED),
                model.uiState.value.closeForm?.errors,
            )

            // 39500 frente a 40000: faltan 500, y se pregunta antes de cerrar.
            model.onAction(CashRegisterAction.CountAmount("39500"))
            assertEquals(Money(-500), model.uiState.value.closeForm?.difference)
            model.onAction(CashRegisterAction.ConfirmClose)
            advanceUntilIdle()
            assertTrue(requireNotNull(model.uiState.value.closeForm).confirmDifference)
            assertTrue(operations.closed.isEmpty())

            model.onAction(CashRegisterAction.ConfirmDifference)
            advanceUntilIdle()
            assertEquals(
                listOf(
                    Triple(
                        SESSION,
                        CashCounted.Amount(Money(39_500)) as CashCounted,
                        null as String?,
                    ),
                ),
                operations.closed,
            )
            assertNull(model.uiState.value.closeForm)
            assertEquals(CashMessage.Closed, model.uiState.value.message)
        }

    @Test
    fun `no difference closes at once, and counting by denomination sums the pieces`() =
        runTest(dispatcher) {
            operations.sessions.value = listOf(openSession(TODAY))
            operations.movements.value = EXAMPLE
            operations.denominations.value = DENOMINATIONS
            val model = open()
            model.onAction(CashRegisterAction.StartClose)
            model.onAction(CashRegisterAction.CountByDenomination(true))
            // 2 de 10000, 3 de 5000, 4 de 1000 y 1 de 500: 39500.
            model.onAction(CashRegisterAction.CountPieces(1, "2"))
            model.onAction(CashRegisterAction.CountPieces(2, "3"))
            model.onAction(CashRegisterAction.CountPieces(3, "4"))
            model.onAction(CashRegisterAction.CountPieces(4, "1"))
            assertEquals(Money(39_500), model.uiState.value.closeForm?.count?.counted)

            // Una pieza más de 500 da justo lo esperado.
            model.onAction(CashRegisterAction.CountPieces(4, "2"))
            model.onAction(CashRegisterAction.CloseNote("  turno de la tarde  "))
            model.onAction(CashRegisterAction.ConfirmClose)
            advanceUntilIdle()

            val (session, counted, note) = operations.closed.single()
            assertEquals(SESSION, session)
            assertEquals(
                CashCounted.ByDenomination(
                    listOf(
                        DenominationCount(1, 2),
                        DenominationCount(2, 3),
                        DenominationCount(3, 4),
                        DenominationCount(4, 2),
                    ),
                ),
                counted,
            )
            assertEquals("turno de la tarde", note)
        }

    @Test
    fun `a failed close keeps the count and says why`() = runTest(dispatcher) {
        operations.sessions.value = listOf(openSession(TODAY))
        operations.movements.value = EXAMPLE
        operations.closeResult = CloseCashSessionResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = open()
        model.onAction(CashRegisterAction.StartClose)
        model.onAction(CashRegisterAction.CountAmount("40000"))
        model.onAction(CashRegisterAction.ConfirmClose)
        advanceUntilIdle()

        val form = requireNotNull(model.uiState.value.closeForm)
        assertEquals("40000", form.count.amountText)
        assertEquals(CashFormNotice.SaveFailed(outOfSpace = true), form.notice)
    }

    @Test
    fun `a denomination is never repeated nor zero, and saves its value`() = runTest(dispatcher) {
        operations.denominations.value = DENOMINATIONS
        val model = open()
        model.onAction(CashRegisterAction.NewDenomination)
        model.onAction(CashRegisterAction.DenominationValue("5.000"))
        model.onAction(CashRegisterAction.SaveDenomination)
        advanceUntilIdle()
        assertEquals(DenominationError.DUPLICATE, model.uiState.value.denominationEditor?.error)

        model.onAction(CashRegisterAction.DenominationValue("0"))
        model.onAction(CashRegisterAction.SaveDenomination)
        advanceUntilIdle()
        assertEquals(DenominationError.VALUE_ZERO, model.uiState.value.denominationEditor?.error)

        model.onAction(CashRegisterAction.DenominationValue("2.000"))
        model.onAction(CashRegisterAction.SaveDenomination)
        advanceUntilIdle()
        assertEquals(listOf(Money(2_000)), operations.savedValues)
        assertNull(model.uiState.value.denominationEditor)

        // Cambiar la de 5000 a inactiva no choca consigo misma.
        model.onAction(CashRegisterAction.EditDenomination(2))
        model.onAction(CashRegisterAction.DenominationActive(false))
        model.onAction(CashRegisterAction.SaveDenomination)
        advanceUntilIdle()
        assertEquals(Money(5_000), operations.savedValues.last())
        assertEquals(false, operations.savedActive.last())
    }

    @Test
    fun `without a cash account or with an unknown zone the screen says so`() =
        runTest(dispatcher) {
            operations.accounts.value = emptyList()
            assertEquals(CashRegisterContent.NoCashAccount, open().uiState.value.content)

            operations.accounts.value = listOf(account())
            operations.zoneKnown = false
            assertEquals(CashRegisterContent.UnknownZone, open().uiState.value.content)
        }

    @Test
    fun `a closed session opens its detail with the counts`() = runTest(dispatcher) {
        val closed = openSession(TODAY.plusDays(-2)).copy(
            id = 3,
            closing = CashClosing(Money(40_000), Money(39_500), Money(-500), AT, null),
        )
        operations.sessions.value = listOf(closed)
        operations.counts = listOf(DenominationCount(1, 3))
        val model = open()
        model.onAction(CashRegisterAction.OpenClosed(3))
        advanceUntilIdle()

        assertEquals(
            ClosedCashDetail(closed, listOf(DenominationCount(1, 3))),
            model.uiState.value.detail,
        )
        model.onAction(CashRegisterAction.DismissSheet)
        assertNull(model.uiState.value.detail)
    }

    // ------------------------------------------------------------------ apoyo

    private fun TestScope.open(): CashRegisterViewModel {
        val model = CashRegisterViewModel(operations, numbers)
        advanceUntilIdle()
        return model
    }

    private fun ready(model: CashRegisterViewModel): CashRegisterView =
        (model.uiState.value.content as CashRegisterContent.Ready).view

    private class FakeCashOperations : CashOperations {
        val accounts = MutableStateFlow(listOf(account()))
        val sessions = MutableStateFlow<List<CashSession>>(emptyList())
        val movements = MutableStateFlow<List<MoneyMovement>>(emptyList())
        val denominations = MutableStateFlow<List<CashDenomination>>(emptyList())
        var zoneKnown = true
        var openResult: OpenCashSessionResult = OpenCashSessionResult.Opened(SESSION)
        var closeResult: CloseCashSessionResult =
            CloseCashSessionResult.Closed(CashClosing(Money(1), Money(1), Money.ZERO, AT, null))
        var counts: List<DenominationCount> = emptyList()
        val opened = mutableListOf<Pair<Long, CashCounted?>>()
        val closed = mutableListOf<Triple<Long, CashCounted, String?>>()
        val savedValues = mutableListOf<Money>()
        val savedActive = mutableListOf<Boolean>()

        override fun cashAccounts(): Flow<List<MoneyAccount>> = accounts

        override fun sessions(accountId: Long): Flow<List<CashSession>> = sessions

        override fun movements(accountId: Long): Flow<List<MoneyMovement>> = movements

        override fun denominations(): Flow<List<CashDenomination>> = denominations

        override fun calendar(): Flow<BusinessCalendarResult> = flowOf(
            if (zoneKnown) {
                BusinessCalendarResult.Ready(CALENDAR)
            } else {
                BusinessCalendarResult.UnknownZone("Zona/Desconocida")
            },
        )

        override suspend fun open(accountId: Long, counted: CashCounted?): OpenCashSessionResult {
            opened += accountId to counted
            return openResult
        }

        override suspend fun close(
            sessionId: Long,
            counted: CashCounted,
            note: String?,
        ): CloseCashSessionResult {
            closed += Triple(sessionId, counted, note)
            return closeResult
        }

        override suspend fun saveDenomination(
            id: Long?,
            value: Money,
            isActive: Boolean,
            sortOrder: Int,
        ): SaveCashDenominationResult {
            savedValues += value
            savedActive += isActive
            return SaveCashDenominationResult.Saved(id ?: 99)
        }

        override suspend fun closingCounts(sessionId: Long): List<DenominationCount> = counts

        override fun now(): Instant = Instant.parse("${TODAY}T12:00:00Z")
    }

    private companion object {
        const val ACCOUNT = 1L
        const val SESSION = 7L
        val AT: Instant = Instant.fromEpochMilliseconds(0)
        val TODAY = BusinessDate(LocalDate(2026, 11, 13))
        val CALENDAR = BusinessCalendar(
            requireNotNull(BusinessTimeZone.of("Etc/UTC")),
            DayOfWeek.MONDAY,
        )

        fun account() = MoneyAccount(
            id = ACCOUNT,
            systemKey = "cash",
            details = MoneyAccountDetails(null, null, MoneyAccountKind.CASH),
            currency = AccountCurrency(requireNotNull(CurrencyCode.of("XTS")), 0),
            balance = Money(12_000),
            archivedAt = null,
            createdAt = AT,
            updatedAt = AT,
        )

        fun openSession(date: BusinessDate) = CashSession(
            id = SESSION,
            opening = NewCashSession(ACCOUNT, Money(20_000), AT, date),
            closing = null,
        )

        fun movement(id: Long, type: MoneyMovementType, amount: Long, origin: MoneyOrigin) =
            MoneyMovement(
                id = id,
                draft = MoneyMovementDraft(
                    ACCOUNT,
                    type,
                    Money(amount),
                    origin,
                    cashSessionId = SESSION,
                ),
                balanceAfter = Money(amount),
                time = RecordTime(AT, TODAY),
                createdAt = AT,
            )

        /** El ejemplo de lo esperado: apertura 20000, ventas 35000, devolución, gasto y retiro. */
        val EXAMPLE = listOf(
            movement(1, MoneyMovementType.SALE_RECEIPT, 35_000, MoneyOrigin.SalePayment(1)),
            movement(2, MoneyMovementType.REFUND, -2_000, MoneyOrigin.SaleReturn(1)),
            movement(3, MoneyMovementType.EXPENSE_PAYMENT, -3_000, MoneyOrigin.Expense(1)),
            movement(4, MoneyMovementType.OWNER_DRAW, -10_000, MoneyOrigin.None),
        )

        val DENOMINATIONS = listOf(10_000L, 5_000L, 1_000L, 500L).mapIndexed { index, value ->
            CashDenomination(index + 1L, Money(value), true, index, AT, AT)
        }
    }
}
