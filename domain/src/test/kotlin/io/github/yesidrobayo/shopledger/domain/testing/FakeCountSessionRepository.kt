package io.github.yesidrobayo.shopledger.domain.testing

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
 * Un [CountSessionRepository] sobre [InMemoryDatabase]. Repite lo que la guarda impide: cambiar
 * una línea o terminar una sesión que ya no está abierta.
 */
internal class FakeCountSessionRepository(private val database: InMemoryDatabase) :
    CountSessionRepository {
    override fun observeOpen(): Flow<List<CountSession>> = database.tables.map { tables ->
        tables.countSessions.values.filter { it.status == CountSessionStatus.OPEN }
            .sortedBy { it.id }
    }.distinctUntilChanged()

    override fun observeFinished(): Flow<List<CountSession>> = database.tables.map { tables ->
        tables.countSessions.values.filter { it.status != CountSessionStatus.OPEN }
            .sortedByDescending { it.id }
    }.distinctUntilChanged()

    override fun observeLines(sessionId: Long): Flow<List<CountSessionLine>> =
        database.tables.map { tables -> linesOf(tables.countLines, sessionId) }
            .distinctUntilChanged()

    override suspend fun findById(id: Long): CountSession? = database.read().countSessions[id]

    override suspend fun findOpenAt(locationId: Long): CountSession? =
        database.read().countSessions.values.firstOrNull {
            it.locationId == locationId && it.status == CountSessionStatus.OPEN
        }

    override suspend fun findLines(sessionId: Long): List<CountSessionLine> =
        linesOf(database.read().countLines, sessionId)

    override suspend fun insert(session: NewCountSession): Long {
        val id = database.nextId(SESSIONS)
        val row = CountSession(
            id = id,
            status = CountSessionStatus.OPEN,
            isBlind = session.isBlind,
            locationId = session.locationId,
            categoryId = session.categoryId,
            note = session.note,
            startedAt = session.startedAt,
            finishedAt = null,
            businessDate = session.businessDate,
        )
        database.write { it.copy(countSessions = it.countSessions + (id to row)) }
        return id
    }

    override suspend fun insertLine(
        sessionId: Long,
        productId: Long,
        lotId: Long?,
        expected: QuantityMilli,
    ): Long {
        requireOpen(sessionId)
        val id = database.nextId(LINES)
        val row = CountSessionLine(id, sessionId, productId, lotId, expected, counted = null)
        database.write { it.copy(countLines = it.countLines + (id to row)) }
        return id
    }

    override suspend fun setCounted(lineId: Long, counted: QuantityMilli?) {
        val line = checkNotNull(database.read().countLines[lineId]) { "La línea no existe" }
        requireOpen(line.sessionId)
        database.write {
            it.copy(countLines = it.countLines + (lineId to line.copy(counted = counted)))
        }
    }

    override suspend fun finish(id: Long, status: CountSessionStatus, finishedAt: Instant) {
        val session = requireOpen(id)
        database.write {
            it.copy(
                countSessions = it.countSessions +
                    (id to session.copy(status = status, finishedAt = finishedAt)),
            )
        }
    }

    override suspend fun productsMovedSinceOpen(sessionId: Long): Set<Long> {
        val tables = database.read()
        val session = checkNotNull(tables.countSessions[sessionId]) { "La sesión no existe" }
        val counted = linesOf(tables.countLines, sessionId).map { it.productId }.toSet()
        return tables.movements.values.filter {
            it.movement.key.locationId == session.locationId &&
                it.createdAt > session.startedAt &&
                it.source.countSessionId != sessionId &&
                it.movement.key.productId in counted
        }.map { it.movement.key.productId }.toSet()
    }

    private fun requireOpen(sessionId: Long): CountSession {
        val session = checkNotNull(database.read().countSessions[sessionId]) { "No existe" }
        check(session.status == CountSessionStatus.OPEN) { "La sesión ya no está abierta" }
        return session
    }

    private fun linesOf(lines: Map<Long, CountSessionLine>, sessionId: Long) =
        lines.values.filter { it.sessionId == sessionId }.sortedBy { it.id }

    private companion object {
        const val SESSIONS = "count_sessions"
        const val LINES = "count_session_lines"
    }
}
