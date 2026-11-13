package io.github.yesidrobayo.shopledger.ui.cash

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCounted
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.DenominationCount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.CloseCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.CloseCashSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.cash.ObserveCashRegisterUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.cash.SaveCashDenominationResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.SaveCashDenominationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Lo que la pantalla de caja le pide al dominio. Es una interfaz para que las pruebas del ViewModel
 * den respuestas fijas sin una base.
 */
interface CashOperations {
    /** Las cuentas de efectivo vigentes, en el orden de las cuentas: la inicial primero. */
    fun cashAccounts(): Flow<List<MoneyAccount>>

    /** Las sesiones de la cuenta, de la más reciente a la más vieja. */
    fun sessions(accountId: Long): Flow<List<CashSession>>

    /** Los movimientos de la cuenta, en el orden del libro: dicen qué entró y salió en la caja. */
    fun movements(accountId: Long): Flow<List<MoneyMovement>>

    /** Los billetes y monedas que escribió la persona, también los inactivos. */
    fun denominations(): Flow<List<CashDenomination>>

    fun calendar(): Flow<BusinessCalendarResult>

    suspend fun open(accountId: Long, counted: CashCounted?): OpenCashSessionResult

    suspend fun close(sessionId: Long, counted: CashCounted, note: String?): CloseCashSessionResult

    suspend fun saveDenomination(
        id: Long?,
        value: Money,
        isActive: Boolean,
        sortOrder: Int,
    ): SaveCashDenominationResult

    /** Lo contado por denominación al cerrar, o vacío si se escribió un monto. */
    suspend fun closingCounts(sessionId: Long): List<DenominationCount>

    fun now(): Instant
}

/** Las operaciones sobre los casos de uso de caja. */
class CashUseCases(
    private val reads: ObserveCashRegisterUseCase,
    private val calendarSource: BusinessCalendarSource,
    private val openSession: OpenCashSessionUseCase,
    private val closeSession: CloseCashSessionUseCase,
    private val saveCashDenomination: SaveCashDenominationUseCase,
    private val clockNow: () -> Instant,
) : CashOperations {
    override fun cashAccounts(): Flow<List<MoneyAccount>> = reads.cashAccounts()

    override fun sessions(accountId: Long): Flow<List<CashSession>> = reads.sessions(accountId)

    override fun movements(accountId: Long): Flow<List<MoneyMovement>> = reads.movements(accountId)

    override fun denominations(): Flow<List<CashDenomination>> = reads.denominations()

    override fun calendar(): Flow<BusinessCalendarResult> = calendarSource.observe()

    override suspend fun open(accountId: Long, counted: CashCounted?): OpenCashSessionResult =
        openSession(accountId, counted)

    override suspend fun close(
        sessionId: Long,
        counted: CashCounted,
        note: String?,
    ): CloseCashSessionResult = closeSession(sessionId, counted, note)

    override suspend fun saveDenomination(
        id: Long?,
        value: Money,
        isActive: Boolean,
        sortOrder: Int,
    ): SaveCashDenominationResult = saveCashDenomination(id, value, isActive, sortOrder)

    override suspend fun closingCounts(sessionId: Long): List<DenominationCount> =
        reads.closingCounts(sessionId)

    override fun now(): Instant = clockNow()

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): CashUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val tx = ports.transactions
            return CashUseCases(
                reads = ObserveCashRegisterUseCase(
                    ports.moneyAccounts,
                    ports.moneyLedger,
                    ports.cashSessions,
                    ports.cashDenominations,
                ),
                calendarSource = BusinessCalendarSource(ports.settings),
                openSession = OpenCashSessionUseCase(
                    tx,
                    ports.settings,
                    ports.moneyAccounts,
                    ports.moneyLedger,
                    ports.cashSessions,
                    clock,
                    ports.cashDenominations,
                ),
                closeSession = CloseCashSessionUseCase(
                    tx,
                    ports.settings,
                    ports.moneyAccounts,
                    ports.moneyLedger,
                    ports.cashSessions,
                    clock,
                    ports.cashDenominations,
                ),
                saveCashDenomination = SaveCashDenominationUseCase(
                    tx,
                    ports.cashDenominations,
                    clock,
                ),
                clockNow = clock::now,
            )
        }
    }
}
