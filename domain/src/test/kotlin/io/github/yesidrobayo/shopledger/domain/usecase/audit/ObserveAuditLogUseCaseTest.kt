package io.github.yesidrobayo.shopledger.domain.usecase.audit

import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEvent
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.port.AuditLogRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** El registro se pide por tramos, con un tope entre un evento y lo que la tabla conserva. */
class ObserveAuditLogUseCaseTest {
    private val asked = mutableListOf<Pair<Int, Boolean>>()
    private val event =
        AuditEvent(
            id = 3,
            occurredAt = Instant.fromEpochMilliseconds(1_767_225_600_000L),
            type = AuditEventType.EXPORT_CSV,
            result = AuditResult.SUCCESS,
        )
    private val repository =
        object : AuditLogRepository {
            override fun observeLatest(limit: Int, failuresOnly: Boolean): Flow<List<AuditEvent>> {
                asked += limit to failuresOnly
                return flowOf(listOf(event))
            }
        }
    private val observe = ObserveAuditLogUseCase(repository)

    @Test
    fun `it passes the page and the filter to the log`() = runTest {
        assertEquals(listOf(event), observe(ObserveAuditLogUseCase.PAGE_SIZE, false).first())
        observe(200, true).first()

        assertEquals(listOf(100 to false, 200 to true), asked)
    }

    @Test
    fun `the limit stays between one event and the cap`() = runTest {
        observe(0, false).first()
        observe(9_999, true).first()

        assertEquals(listOf(1 to false, ObserveAuditLogUseCase.MAX_EVENTS to true), asked)
    }
}
