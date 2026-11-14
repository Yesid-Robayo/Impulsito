package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.stock.CountSessionDao
import io.github.yesidrobayo.shopledger.data.local.stock.CountSessionEntity
import io.github.yesidrobayo.shopledger.data.local.stock.CountSessionLineEntity
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSession
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionLine
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewCountSession
import io.github.yesidrobayo.shopledger.domain.port.CountSessionRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las sesiones de conteo sobre `count_sessions` y `count_session_lines`. Los ajustes que deja
 * aplicar una sesión los agrega el libro, en la misma transacción.
 */
internal class RoomCountSessionRepository(
    private val sessions: CountSessionDao,
    private val writes: RepositoryWrites,
) : CountSessionRepository {
    constructor(database: AppDatabase) : this(
        database.countSessionDao(),
        RepositoryWrites(database),
    )

    override fun observeOpen(): Flow<List<CountSession>> =
        sessions.observeOpen().map { rows -> rows.map(::toSession) }.distinctUntilChanged()

    override fun observeFinished(): Flow<List<CountSession>> =
        sessions.observeFinished().map { rows -> rows.map(::toSession) }.distinctUntilChanged()

    override fun observeLines(sessionId: Long): Flow<List<CountSessionLine>> =
        sessions.observeLines(sessionId).map { rows -> rows.map(::toLine) }.distinctUntilChanged()

    override suspend fun findById(id: Long): CountSession? = sessions.findById(id)?.let(::toSession)

    override suspend fun findOpenAt(locationId: Long): CountSession? =
        sessions.findOpenAt(locationId)?.let(::toSession)

    override suspend fun findLines(sessionId: Long): List<CountSessionLine> =
        sessions.findLines(sessionId).map(::toLine)

    override suspend fun insert(session: NewCountSession): Long = writes.inTransaction {
        sessions.insert(
            CountSessionEntity(
                status = CountSessionStatus.OPEN.name,
                isBlind = if (session.isBlind) 1L else 0L,
                locationId = session.locationId,
                categoryId = session.categoryId,
                note = session.note,
                startedAt = session.startedAt.toStoredMillis(),
                finishedAt = null,
                businessDate = session.businessDate.toStored(),
            ),
        )
    }

    override suspend fun insertLine(
        sessionId: Long,
        productId: Long,
        lotId: Long?,
        expected: QuantityMilli,
    ): Long = writes.inTransaction {
        sessions.insertLine(
            CountSessionLineEntity(
                countSessionId = sessionId,
                productId = productId,
                lotId = lotId,
                expectedMilli = expected.milli,
                countedMilli = null,
            ),
        )
    }

    override suspend fun setCounted(lineId: Long, counted: QuantityMilli?) = writes.inTransaction {
        requireOneRow(sessions.setCounted(lineId, counted?.milli))
    }

    override suspend fun finish(id: Long, status: CountSessionStatus, finishedAt: Instant) =
        writes.inTransaction {
            requireOneRow(sessions.finish(id, status.name, finishedAt.toStoredMillis()))
        }

    override suspend fun productsMovedSinceOpen(sessionId: Long): Set<Long> =
        sessions.productsMovedSinceOpen(sessionId).toSet()

    private fun toSession(row: CountSessionEntity): CountSession = CountSession(
        id = row.id,
        status = storedEnum<CountSessionStatus>(row.status),
        isBlind = row.isBlind == 1L,
        locationId = row.locationId,
        categoryId = row.categoryId,
        note = row.note,
        startedAt = row.startedAt.toInstant(),
        finishedAt = row.finishedAt?.toInstant(),
        businessDate = storedDate(row.businessDate),
    )

    private fun toLine(row: CountSessionLineEntity): CountSessionLine = CountSessionLine(
        id = row.id,
        sessionId = row.countSessionId,
        productId = row.productId,
        lotId = row.lotId,
        expected = QuantityMilli(row.expectedMilli),
        counted = row.countedMilli?.let { QuantityMilli(it) },
    )
}
