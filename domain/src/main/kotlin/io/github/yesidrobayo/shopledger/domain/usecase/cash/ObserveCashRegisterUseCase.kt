package io.github.yesidrobayo.shopledger.domain.usecase.cash

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCountPhase
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.DenominationCount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.port.CashDenominationRepository
import io.github.yesidrobayo.shopledger.domain.port.CashSessionRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Lo que lee la pantalla de caja: las cuentas de efectivo, las sesiones y los movimientos de una
 * cuenta, las denominaciones y el conteo de un cierre. Solo lee: abrir, cerrar y guardar una
 * denominación son sus propios casos de uso.
 */
class ObserveCashRegisterUseCase(
    private val accounts: MoneyAccountRepository,
    private val ledger: MoneyLedgerRepository,
    private val sessions: CashSessionRepository,
    private val denominations: CashDenominationRepository,
) {
    /** Las cuentas de efectivo vigentes, por id: la inicial `Efectivo` primero. */
    fun cashAccounts(): Flow<List<MoneyAccount>> =
        accounts.observe(ArchiveFilter.ACTIVE).map { list ->
            list.filter { it.details.kind == MoneyAccountKind.CASH }.sortedBy { it.id }
        }

    /** Las sesiones de la cuenta, de la más reciente a la más vieja. */
    fun sessions(accountId: Long): Flow<List<CashSession>> = sessions.observeByAccount(accountId)

    /** Los movimientos de la cuenta, en el orden del libro. */
    fun movements(accountId: Long): Flow<List<MoneyMovement>> = ledger.observeByAccount(accountId)

    /** Los billetes y monedas de la persona, también los inactivos. */
    fun denominations(): Flow<List<CashDenomination>> = denominations.observeAll()

    /** Lo contado por denominación al cerrar la sesión, o vacío si se escribió un monto. */
    suspend fun closingCounts(sessionId: Long): List<DenominationCount> =
        sessions.findCounts(sessionId, CashCountPhase.CLOSING)
}
