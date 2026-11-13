package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.cash.CashSessionCountDao
import io.github.yesidrobayo.shopledger.data.local.cash.CashSessionCountEntity
import io.github.yesidrobayo.shopledger.data.local.cash.CashSessionDao
import io.github.yesidrobayo.shopledger.data.local.cash.CashSessionEntity
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashClosing
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCountPhase
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSessionStatus
import io.github.yesidrobayo.shopledger.domain.model.cash.DenominationCount
import io.github.yesidrobayo.shopledger.domain.model.cash.NewCashSession
import io.github.yesidrobayo.shopledger.domain.port.CashSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las sesiones de caja sobre `cash_sessions`. La guarda de la tabla impide una segunda abierta en la
 * misma cuenta y cualquier cambio de una cerrada; aquí solo se abre y se cierra.
 */
internal class RoomCashSessionRepository(
    private val sessions: CashSessionDao,
    private val writes: RepositoryWrites,
    private val counts: CashSessionCountDao? = null,
) : CashSessionRepository {
    constructor(database: AppDatabase) : this(
        database.cashSessionDao(),
        RepositoryWrites(database),
        database.cashSessionCountDao(),
    )

    override suspend fun insertCounts(
        sessionId: Long,
        phase: CashCountPhase,
        counts: List<DenominationCount>,
    ) {
        if (counts.isEmpty()) return
        val dao = requireNotNull(this.counts) { "Guardar un conteo pide su tabla" }
        writes.inTransaction {
            dao.insertAll(
                counts.map {
                    CashSessionCountEntity(
                        cashSessionId = sessionId,
                        phase = phase.name,
                        denominationId = it.denominationId,
                        count = it.count,
                    )
                },
            )
        }
    }

    override suspend fun findCounts(
        sessionId: Long,
        phase: CashCountPhase,
    ): List<DenominationCount> = requireNotNull(counts) { "Leer un conteo pide su tabla" }
        .findBySession(sessionId, phase.name)
        .map { DenominationCount(it.denominationId, it.count) }

    override fun observeByAccount(accountId: Long): Flow<List<CashSession>> = sessions
        .observeByAccount(accountId)
        .map { rows -> rows.map(::toSession) }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): CashSession? = sessions.findById(id)?.let(::toSession)

    override suspend fun findOpen(accountId: Long): CashSession? =
        sessions.findOpen(accountId)?.let(::toSession)

    override suspend fun movementsTotal(sessionId: Long): Money =
        Money(sessions.movementsTotal(sessionId))

    override suspend fun insert(session: NewCashSession): Long = writes.inTransaction {
        sessions.insert(
            CashSessionEntity(
                moneyAccountId = session.accountId,
                status = CashSessionStatus.OPEN.name,
                openingAmount = session.openingAmount.minor,
                expectedAmount = null,
                countedAmount = null,
                differenceAmount = null,
                openedAt = session.openedAt.toStoredMillis(),
                closedAt = null,
                businessDate = session.businessDate.toStored(),
                note = null,
            ),
        )
    }

    override suspend fun close(sessionId: Long, closing: CashClosing): Boolean =
        writes.inTransaction {
            sessions.close(
                id = sessionId,
                expectedAmount = closing.expected.minor,
                countedAmount = closing.counted.minor,
                differenceAmount = closing.difference.minor,
                closedAt = closing.closedAt.toStoredMillis(),
                note = closing.note,
            ) == 1
        }

    private fun toSession(row: CashSessionEntity): CashSession {
        val opening = NewCashSession(
            accountId = row.moneyAccountId,
            openingAmount = Money(row.openingAmount),
            openedAt = row.openedAt.toInstant(),
            businessDate = storedDate(row.businessDate),
        )
        val closing = when (storedEnum<CashSessionStatus>(row.status)) {
            CashSessionStatus.OPEN -> null

            CashSessionStatus.CLOSED -> CashClosing(
                expected = Money(row.expectedAmount ?: brokenRow()),
                counted = Money(row.countedAmount ?: brokenRow()),
                difference = Money(row.differenceAmount ?: brokenRow()),
                closedAt = (row.closedAt ?: brokenRow()).toInstant(),
                note = row.note,
            )
        }
        return CashSession(row.id, opening, closing)
    }
}
