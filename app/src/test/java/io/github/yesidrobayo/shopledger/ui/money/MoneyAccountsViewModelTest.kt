package io.github.yesidrobayo.shopledger.ui.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyKind
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyRequest
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.TransferRequest
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyOverview
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyTotals
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAccountLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.RecordManualMoneyResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.TransferMoneyResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementResult
import io.github.yesidrobayo.shopledger.ui.region.RegionalOption
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 * Las cuentas de dinero con respuestas fijas del dominio: la suma solo de la moneda del negocio, lo
 * que falta por acreditar, el vacío, el libro por día, retiro con aviso de saldo negativo, transferir
 * entre monedas con cotización, errores que conservan lo escrito, cuenta nueva, anular y archivar.
 * Nombres y montos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoneyAccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeMoneyOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `the hero only adds accounts in the business currency, and other currencies stay apart`() =
        runTest(dispatcher) {
            operations.accounts.value = listOf(CASH, BANK, FOREIGN)
            operations.pendingByAccount.value = mapOf(BANK.id to Money(4_500))
            val view = ready(open())

            assertEquals(Money(1_134_500), view.available)
            assertEquals(listOf(CASH.id, BANK.id), view.accounts.map { it.account.id })
            assertEquals(listOf(FOREIGN.id), view.others.map { it.account.id })
            assertEquals(Money(4_500), view.accounts[1].pending)
            assertNull(view.accounts[0].pending)
            assertEquals(TODAY, operations.pendingAsked.last())
        }

    @Test
    fun `only the initial cash account and no movements is the empty state`() =
        runTest(dispatcher) {
            operations.inUse.value = false
            val view = ready(open())

            assertFalse(view.inUse)
            assertEquals(listOf(CASH.id), view.active.map { it.id })
        }

    @Test
    fun `a read that fails shows the error and retrying reads again`() = runTest(dispatcher) {
        operations.failReads = true
        val model = open()
        assertEquals(MoneyAccountsContent.ReadError, model.uiState.value.content)

        operations.failReads = false
        model.onAction(MoneyAccountsAction.Retry)
        advanceUntilIdle()
        assertTrue(model.uiState.value.content is MoneyAccountsContent.Ready)
    }

    @Test
    fun `opening an account shows its statement by day with the void marked`() =
        runTest(dispatcher) {
            operations.movements.value = listOf(
                movement(1, MoneyMovementType.OPENING_BALANCE, 50_000, 50_000),
                movement(2, MoneyMovementType.OWNER_DRAW, -10_000, 40_000),
                movement(3, MoneyMovementType.MOVEMENT_VOID, 10_000, 50_000, MoneyOrigin.Void(2)),
            )
            val model = open()
            model.onAction(MoneyAccountsAction.Select(CASH.id))
            advanceUntilIdle()

            val detail = model.uiState.value.detail as AccountDetailContent.Ready
            assertEquals(CASH.id, detail.row.account.id)
            val lines = detail.days.single().lines
            assertEquals(listOf(3L, 2L, 1L), lines.map { it.movement.id })
            assertTrue(lines[1].voided)

            model.onAction(MoneyAccountsAction.Select(null))
            assertNull(model.uiState.value.selectedId)
        }

    @Test
    fun `a draw warns before leaving the account negative, without blocking`() =
        runTest(dispatcher) {
            val model = open()
            model.onAction(MoneyAccountsAction.StartManual(ManualMoneyKind.OWNER_DRAW))
            assertEquals(CASH.id, model.uiState.value.manual?.accountId)

            model.onAction(MoneyAccountsAction.Amount("200.000"))
            assertEquals(Money(-15_500), model.uiState.value.manual?.negativeAfter)
            model.onAction(MoneyAccountsAction.Amount("1.000"))
            assertNull(model.uiState.value.manual?.negativeAfter)

            model.onAction(MoneyAccountsAction.Amount("200.000"))
            model.onAction(MoneyAccountsAction.Note("  Para la casa "))
            model.onAction(MoneyAccountsAction.Save)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    ManualMoneyRequest(
                        CASH.id,
                        ManualMoneyKind.OWNER_DRAW,
                        Money(200_000),
                        "  Para la casa ",
                    ),
                ),
                operations.recorded,
            )
            assertNull(model.uiState.value.manual)
            assertEquals(MoneyMessage.MovementSaved, model.uiState.value.message)
        }

    @Test
    fun `a blank amount and a failed save keep what was written`() = runTest(dispatcher) {
        val model = open()
        model.onAction(MoneyAccountsAction.StartManual(ManualMoneyKind.OTHER_INCOME))
        model.onAction(MoneyAccountsAction.Save)
        assertEquals(setOf(MoneyFormError.AMOUNT_ZERO), model.uiState.value.manual?.errors)
        assertTrue(operations.recorded.isEmpty())

        operations.recordResult = RecordManualMoneyResult.StorageFailed(StorageFailure.DISK_FULL)
        model.onAction(MoneyAccountsAction.Amount("1.500"))
        model.onAction(MoneyAccountsAction.Save)
        advanceUntilIdle()

        val sheet = requireNotNull(model.uiState.value.manual)
        assertEquals(MoneyFormNotice.SaveFailed(outOfSpace = true), sheet.notice)
        assertEquals("1.500", sheet.amountText)
        assertFalse(sheet.working)
    }

    @Test
    fun `a transfer between currencies needs the rate and says what arrives`() =
        runTest(dispatcher) {
            operations.accounts.value = listOf(CASH, FOREIGN)
            val model = open()
            model.onAction(MoneyAccountsAction.StartTransfer)
            assertEquals(CASH.id, model.uiState.value.transfer?.fromId)
            assertEquals(FOREIGN.id, model.uiState.value.transfer?.toId)

            model.onAction(MoneyAccountsAction.Amount("10.000"))
            model.onAction(MoneyAccountsAction.Save)
            assertEquals(setOf(MoneyFormError.RATE_REQUIRED), model.uiState.value.transfer?.errors)

            // Diez mil sin decimales a una cuenta de dos decimales, a 0,25: entran 2.500,00.
            model.onAction(MoneyAccountsAction.Rate("0,25"))
            assertEquals(Money(250_000), model.uiState.value.transfer?.arrives)
            model.onAction(MoneyAccountsAction.Save)
            advanceUntilIdle()

            assertEquals(
                listOf(TransferRequest(CASH.id, FOREIGN.id, Money(10_000), 250_000L, "")),
                operations.transfers,
            )
            assertNull(model.uiState.value.transfer)
        }

    @Test
    fun `a transfer to the same account is said on the destination`() = runTest(dispatcher) {
        val model = open()
        model.onAction(MoneyAccountsAction.StartTransfer)
        model.onAction(MoneyAccountsAction.ChooseTo(CASH.id))
        model.onAction(MoneyAccountsAction.Amount("10"))
        model.onAction(MoneyAccountsAction.Save)

        assertEquals(setOf(MoneyFormError.SAME_ACCOUNT), model.uiState.value.transfer?.errors)
        assertTrue(operations.transfers.isEmpty())
    }

    @Test
    fun `a new account takes the book currency and an opening balance, a taken name is said`() =
        runTest(dispatcher) {
            val model = open()
            model.onAction(MoneyAccountsAction.NewAccount)
            val editor = requireNotNull(model.uiState.value.editor)
            assertEquals(BOOK, editor.currency)

            model.onAction(MoneyAccountsAction.Name("Banco Dos"))
            model.onAction(MoneyAccountsAction.Kind(MoneyAccountKind.BANK))
            model.onAction(MoneyAccountsAction.OpeningBalance("-2.500"))
            operations.saveResult = SaveMoneyAccountResult.DuplicateName
            model.onAction(MoneyAccountsAction.Save)
            advanceUntilIdle()
            assertEquals(setOf(MoneyFormError.NAME_TAKEN), model.uiState.value.editor?.errors)

            operations.saveResult = SaveMoneyAccountResult.Saved(9)
            model.onAction(MoneyAccountsAction.Save)
            advanceUntilIdle()
            assertEquals(
                null to MoneyAccountDraft("Banco Dos", MoneyAccountKind.BANK, BOOK, Money(-2_500)),
                operations.saved.last(),
            )
            assertNull(model.uiState.value.editor)
            assertEquals(MoneyMessage.AccountSaved, model.uiState.value.message)
        }

    @Test
    fun `another currency comes from the list with its own decimals`() = runTest(dispatcher) {
        val model = open()
        model.onAction(MoneyAccountsAction.NewAccount)
        model.onAction(MoneyAccountsAction.OpenCurrencies)
        assertTrue(model.uiState.value.editor?.choosingCurrency == true)

        model.onAction(MoneyAccountsAction.CurrencyQuery("oro"))
        assertEquals(listOf("XAU"), model.uiState.value.editor?.currencyOptions?.map { it.key })
        model.onAction(MoneyAccountsAction.ChooseCurrency(XAU))

        val editor = requireNotNull(model.uiState.value.editor)
        assertEquals(AccountCurrency(XAU, 3), editor.currency)
        assertFalse(editor.choosingCurrency)
    }

    @Test
    fun `editing the initial cash account keeps its translated name until it is touched`() =
        runTest(dispatcher) {
            val model = open()
            model.onAction(MoneyAccountsAction.Select(CASH.id))
            advanceUntilIdle()
            model.onAction(MoneyAccountsAction.EditAccount)
            model.onAction(MoneyAccountsAction.Kind(MoneyAccountKind.CASH))
            model.onAction(MoneyAccountsAction.Save)
            advanceUntilIdle()

            assertEquals(
                CASH.id to MoneyAccountDraft("", MoneyAccountKind.CASH, null, null),
                operations.saved.last(),
            )
        }

    @Test
    fun `only a manual movement offers its void, and a document one leads to its document`() =
        runTest(dispatcher) {
            operations.movements.value = listOf(
                movement(1, MoneyMovementType.OPENING_BALANCE, 50_000, 50_000),
                movement(
                    2,
                    MoneyMovementType.SALE_RECEIPT,
                    5_000,
                    55_000,
                    MoneyOrigin.SalePayment(4),
                ),
            )
            operations.document = MoneyDocument.Sale(30)
            val model = open()
            model.onAction(MoneyAccountsAction.Select(CASH.id))
            advanceUntilIdle()

            model.onAction(MoneyAccountsAction.OpenMovement(2))
            advanceUntilIdle()
            assertEquals(MoneyDocument.Sale(30), model.uiState.value.movement?.document)
            model.onAction(MoneyAccountsAction.AskVoid)
            assertNull(model.uiState.value.dialog)

            model.onAction(MoneyAccountsAction.DismissSheet)
            model.onAction(MoneyAccountsAction.OpenMovement(1))
            model.onAction(MoneyAccountsAction.AskVoid)
            assertEquals(MoneyDialog.Void(1, isTransfer = false), model.uiState.value.dialog)
            model.onAction(MoneyAccountsAction.ConfirmDialog)
            advanceUntilIdle()
            assertEquals(listOf(1L), operations.voided)
            assertEquals(MoneyMessage.MovementSaved, model.uiState.value.message)
        }

    @Test
    fun `archiving asks first and closes the account`() = runTest(dispatcher) {
        operations.accounts.value = listOf(CASH, BANK)
        val model = open()
        model.onAction(MoneyAccountsAction.Select(BANK.id))
        advanceUntilIdle()
        model.onAction(MoneyAccountsAction.AskArchive)
        assertEquals(MoneyDialog.Archive(BANK), model.uiState.value.dialog)

        model.onAction(MoneyAccountsAction.ConfirmDialog)
        advanceUntilIdle()

        assertEquals(listOf(BANK.id), operations.archivedIds)
        assertNull(model.uiState.value.selectedId)
    }

    // ------------------------------------------------------------------ apoyo

    private fun TestScope.open(initial: Long? = null): MoneyAccountsViewModel {
        val model = MoneyAccountsViewModel(initial, operations, NUMBERS, FakeCurrencies)
        advanceUntilIdle()
        return model
    }

    private fun ready(model: MoneyAccountsViewModel): MoneyAccountsView =
        (model.uiState.value.content as MoneyAccountsContent.Ready).view

    private class FakeMoneyOperations : MoneyAccountsOperations {
        val accounts = MutableStateFlow(listOf(CASH))
        val archivedAccounts = MutableStateFlow<List<MoneyAccount>>(emptyList())
        val inUse = MutableStateFlow(true)
        val pendingByAccount = MutableStateFlow<Map<Long, Money>>(emptyMap())
        val movements = MutableStateFlow<List<MoneyMovement>>(emptyList())
        val pendingAsked = mutableListOf<BusinessDate>()
        var failReads = false
        var recordResult: RecordManualMoneyResult = RecordManualMoneyResult.Recorded(1, Money.ZERO)
        var saveResult: SaveMoneyAccountResult = SaveMoneyAccountResult.Saved(1)
        var document: MoneyDocument? = null
        val recorded = mutableListOf<ManualMoneyRequest>()
        val transfers = mutableListOf<TransferRequest>()
        val saved = mutableListOf<Pair<Long?, MoneyAccountDraft>>()
        val voided = mutableListOf<Long>()
        val archivedIds = mutableListOf<Long>()

        override fun overview(): Flow<MoneyOverview> {
            if (failReads) return kotlinx.coroutines.flow.flow { error("Lectura fallida") }
            return accounts.map { MoneyTotals.overview(it, BOOK) }
        }

        override fun archived(): Flow<List<MoneyAccount>> = archivedAccounts

        override fun inUse(): Flow<Boolean> = inUse

        override fun calendar(): Flow<BusinessCalendarResult> =
            flowOf(BusinessCalendarResult.Ready(CALENDAR))

        override fun pending(today: BusinessDate): Flow<Map<Long, Money>> {
            pendingAsked += today
            return pendingByAccount
        }

        override fun movements(accountId: Long): Flow<List<MoneyMovement>> =
            combine(movements, flowOf(accountId)) { rows, _ -> rows }

        override suspend fun record(request: ManualMoneyRequest): RecordManualMoneyResult {
            recorded += request
            return recordResult
        }

        override suspend fun transfer(request: TransferRequest): TransferMoneyResult {
            transfers += request
            return TransferMoneyResult.Transferred("g", 1, 2)
        }

        override suspend fun void(movementId: Long): VoidMoneyMovementResult {
            voided += movementId
            return VoidMoneyMovementResult.Voided(99)
        }

        override suspend fun save(
            editingId: Long?,
            draft: MoneyAccountDraft,
        ): SaveMoneyAccountResult {
            saved += editingId to draft
            return saveResult
        }

        override suspend fun archive(accountId: Long): MoneyAccountLifecycleResult {
            archivedIds += accountId
            return MoneyAccountLifecycleResult.Done
        }

        override suspend fun reactivate(accountId: Long): MoneyAccountLifecycleResult =
            MoneyAccountLifecycleResult.Done

        override suspend fun document(movementId: Long): MoneyDocument? = document

        override fun now(): Instant = AT
    }

    private object FakeCurrencies : CurrencyChoices {
        private val all = listOf(
            RegionalOption("XTS", "Moneda de prueba", "XTS", "moneda de prueba xts"),
            RegionalOption("XAU", "Oro", "XAU", "oro xau"),
        )

        override fun options(): List<RegionalOption> = all

        override fun decimals(code: CurrencyCode): Int? = if (code == XAU) 3 else 2
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-11-13T15:00:00Z")
        val TODAY = BusinessDate(LocalDate(2026, 11, 13))
        val CALENDAR = BusinessCalendar(
            requireNotNull(BusinessTimeZone.of("Etc/UTC")),
            DayOfWeek.MONDAY,
        )
        val BOOK = AccountCurrency(requireNotNull(CurrencyCode.of("XTS")), 0)
        val XAU = requireNotNull(CurrencyCode.of("XAU"))
        val NUMBERS = MoneyInputFormat(
            money = NumberSymbols(',', '-', DigitGrouping('.', 3, 3)),
            rate = NumberSymbols(',', '-', DigitGrouping('.', 3, 3)),
        )

        fun account(
            id: Long,
            name: String?,
            kind: MoneyAccountKind,
            balance: Long,
            currency: AccountCurrency = BOOK,
        ) = MoneyAccount(
            id = id,
            systemKey = if (name == null) "CASH" else null,
            details = MoneyAccountDetails(name, name?.lowercase(), kind),
            currency = currency,
            balance = Money(balance),
            archivedAt = null,
            createdAt = AT,
            updatedAt = AT,
        )

        val CASH = account(1, null, MoneyAccountKind.CASH, 184_500)
        val BANK = account(2, "Cuenta corriente", MoneyAccountKind.BANK, 950_000)
        val FOREIGN = account(
            3,
            "Reserva",
            MoneyAccountKind.OTHER,
            32_000,
            AccountCurrency(requireNotNull(CurrencyCode.of("XBA")), 2),
        )

        fun movement(
            id: Long,
            type: MoneyMovementType,
            amount: Long,
            after: Long,
            origin: MoneyOrigin = MoneyOrigin.None,
        ) = MoneyMovement(
            id,
            MoneyMovementDraft(CASH.id, type, Money(amount), origin),
            Money(after),
            RecordTime(AT, TODAY),
            AT,
        )
    }
}
