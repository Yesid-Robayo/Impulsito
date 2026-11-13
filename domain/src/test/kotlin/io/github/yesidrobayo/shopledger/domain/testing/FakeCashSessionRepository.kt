package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashClosing
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.NewCashSession
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.sum
import io.github.yesidrobayo.shopledger.domain.port.CashSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un [CashSessionRepository] sobre [InMemoryDatabase]. Como la guarda de la base: una sola abierta
 * por cuenta, y una cerrada no cambia.
 */
internal class FakeCashSessionRepository(private val database: InMemoryDatabase) :
    CashSessionRepository {
    override fun observeByAccount(accountId: Long): Flow<List<CashSession>> =
        database.tables.map { tables ->
            tables.cashSessions.values
                .filter { it.opening.accountId == accountId }
                .sortedByDescending { it.id }
        }

    override suspend fun findById(id: Long): CashSession? = database.read().cashSessions[id]

    override suspend fun findOpen(accountId: Long): CashSession? =
        database.read().cashSessions.values.firstOrNull {
            it.opening.accountId == accountId && it.closing == null
        }

    override suspend fun movementsTotal(sessionId: Long): Money =
        database.read().moneyMovements.values
            .filter {
                it.draft.cashSessionId == sessionId && it.type != MoneyMovementType.CASH_DIFFERENCE
            }
            .map { it.amount }
            .sum()

    override suspend fun insert(session: NewCashSession): Long {
        check(findOpen(session.accountId) == null) { "Ya hay una abierta" }
        val id = database.nextId(TABLE)
        database.write {
            it.copy(cashSessions = it.cashSessions + (id to CashSession(id, session, null)))
        }
        return id
    }

    override suspend fun close(sessionId: Long, closing: CashClosing): Boolean {
        val session = database.read().cashSessions[sessionId] ?: return false
        if (session.closing != null) return false
        check(closing.difference == closing.counted - closing.expected) { "Diferencia mal hecha" }
        database.write {
            it.copy(cashSessions = it.cashSessions + (sessionId to session.copy(closing = closing)))
        }
        return true
    }

    private companion object {
        const val TABLE = "cash_sessions"
    }
}
