package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDrillResult
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthSignal
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthState
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las filas de `Salud de mis datos`: cada señal con su estado según el destino, los avisos del
 * respaldo y lo que se lee aparte. Solo lee: los plazos son los de los avisos que ya existen.
 */
class DataHealthRuleTest {
    private val now = Instant.parse("2026-11-12T15:00:00Z")

    private fun overview(
        status: BackupStatus = BackupStatus.UP_TO_DATE,
        kind: BackupDestinationKind? = BackupDestinationKind.WEBDAV,
        notices: Set<BackupNotice> = emptySet(),
    ) = BackupOverview(
        report = BackupStatusReport(status, 0, savingSoon = false, notices = notices),
        destinationKind = kind,
        lastSuccessAt = now - 1.hours,
        failureCode = null,
        staleReason = null,
        meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
        nextMeteredUploadAt = null,
        lastFullReadAt = null,
        computedAt = now,
    )

    private val facts =
        HealthFacts(
            lastHumanCheckAt = now - 2.days,
            lastFullVerifyAt = now - 3.days,
            lastDrillAt = now - 4.days,
            lastDrillResult = BackupDrillResult.PASSED,
            deviceLocked = true,
            serviceSpaceKnown = true,
        )

    private fun states(
        overview: BackupOverview,
        facts: HealthFacts = this.facts,
        pinned: Int? = 1,
    ): Map<HealthSignal, HealthState> =
        DataHealthRule.rows(overview, facts, pinned).associate { it.signal to it.state }

    @Test
    fun `with webdav and everything fine the panel can be folded`() {
        val rows = DataHealthRule.rows(overview(), facts, pinned = 2)

        assertEquals(HealthSignal.entries, rows.map { it.signal })
        val states = rows.associate { it.signal to it.state }
        assertEquals(HealthState.OK, states[HealthSignal.LAST_COPY])
        assertEquals(HealthState.NOT_APPLICABLE, states[HealthSignal.CLOUD_CHECK])
        assertEquals(HealthState.OK, states[HealthSignal.FULL_VERIFY])
        assertEquals(HealthState.OK, states[HealthSignal.LAST_DRILL])
        assertEquals(HealthState.OK, states[HealthSignal.PINNED])
        assertEquals(HealthState.NOT_APPLICABLE, states[HealthSignal.OFFSITE])
        assertEquals(HealthState.NOT_APPLICABLE, states[HealthSignal.CODE])
        assertEquals(HealthState.OK, states[HealthSignal.DEVICE_LOCK])
        assertEquals(HealthState.OK, states[HealthSignal.PHONE_SPACE])
        assertEquals(HealthState.OK, states[HealthSignal.DESTINATION_SPACE])
        assertEquals(2, rows.single { it.signal == HealthSignal.PINNED }.count)
        assertEquals(now - 1.hours, rows.first().at)
        assertEquals(now - 3.days, rows.single { it.signal == HealthSignal.FULL_VERIFY }.at)
        assertTrue(DataHealthRule.allGood(rows))
    }

    @Test
    fun `with slots the check is what the person said and nothing of webdav applies`() {
        val states = states(overview(kind = BackupDestinationKind.DOCUMENT_SLOTS))

        assertEquals(HealthState.OK, states[HealthSignal.CLOUD_CHECK])
        assertEquals(HealthState.NOT_APPLICABLE, states[HealthSignal.FULL_VERIFY])
        assertEquals(HealthState.NOT_APPLICABLE, states[HealthSignal.PINNED])
        assertEquals(HealthState.NOT_APPLICABLE, states[HealthSignal.DESTINATION_SPACE])

        val due =
            states(
                overview(
                    kind = BackupDestinationKind.DOCUMENT_SLOTS,
                    notices = setOf(BackupNotice.HUMAN_CHECK_DUE),
                ),
            )
        assertEquals(HealthState.PENDING, due[HealthSignal.CLOUD_CHECK])
    }

    @Test
    fun `the notices of the backup make their rows pending or ask for attention`() {
        val states =
            states(
                overview(
                    notices =
                        setOf(
                            BackupNotice.FULL_READ_OVERDUE,
                            BackupNotice.LOW_DEVICE_STORAGE,
                            BackupNotice.SERVICE_NEARLY_FULL,
                        ),
                ),
            )

        assertEquals(HealthState.PENDING, states[HealthSignal.FULL_VERIFY])
        assertEquals(HealthState.ATTENTION, states[HealthSignal.PHONE_SPACE])
        assertEquals(HealthState.ATTENTION, states[HealthSignal.DESTINATION_SPACE])
    }

    @Test
    fun `each status gives the last copy its state`() {
        val expected =
            mapOf(
                HealthState.OK to
                    listOf(
                        BackupStatus.UP_TO_DATE,
                        BackupStatus.HANDED_OFF,
                        BackupStatus.CHECK_DUE,
                        BackupStatus.NOTHING_TO_BACK_UP,
                    ),
                HealthState.PENDING to
                    listOf(
                        BackupStatus.PENDING,
                        BackupStatus.UPLOADING,
                        BackupStatus.RESTORING,
                        BackupStatus.WAITING_FOR_NETWORK,
                        BackupStatus.WAITING_FOR_WIFI,
                        BackupStatus.BLOCKED_BY_DATA_SAVER,
                    ),
                HealthState.ATTENTION to
                    listOf(
                        BackupStatus.NOT_CONNECTED,
                        BackupStatus.POLICY_PENDING,
                        BackupStatus.STALE,
                        BackupStatus.FAILED,
                        BackupStatus.PERMISSION_LOST,
                        BackupStatus.CONFLICT,
                    ),
            )
        assertEquals(BackupStatus.entries.toSet(), expected.values.flatten().toSet())
        for ((state, statuses) in expected) {
            for (status in statuses) {
                assertEquals("$status", state, states(overview(status))[HealthSignal.LAST_COPY])
            }
        }
    }

    @Test
    fun `the drill passed is fine, failed asks for attention and the rest are pending`() {
        fun drill(result: BackupDrillResult?) =
            states(overview(), facts.copy(lastDrillResult = result))[HealthSignal.LAST_DRILL]

        assertEquals(HealthState.OK, drill(BackupDrillResult.PASSED))
        assertEquals(HealthState.ATTENTION, drill(BackupDrillResult.FAILED))
        assertEquals(HealthState.PENDING, drill(BackupDrillResult.SKIPPED))
        assertEquals(HealthState.PENDING, drill(null))
    }

    @Test
    fun `without a destination most rows do not apply and the panel stays open`() {
        val rows =
            DataHealthRule.rows(
                overview(BackupStatus.NOT_CONNECTED, kind = null),
                facts.copy(deviceLocked = false, serviceSpaceKnown = false),
                pinned = null,
            )
        val states = rows.associate { it.signal to it.state }

        assertEquals(HealthState.ATTENTION, states[HealthSignal.LAST_COPY])
        assertEquals(HealthState.NOT_APPLICABLE, states[HealthSignal.LAST_DRILL])
        assertEquals(HealthState.NOT_APPLICABLE, states[HealthSignal.PINNED])
        assertNull(rows.single { it.signal == HealthSignal.PINNED }.count)
        assertEquals(HealthState.PENDING, states[HealthSignal.DEVICE_LOCK])
        assertFalse(DataHealthRule.allGood(rows))
    }

    @Test
    fun `an unknown pinned count or service space does not claim anything`() {
        val states =
            states(overview(), facts.copy(serviceSpaceKnown = false), pinned = null)

        assertEquals(HealthState.NOT_APPLICABLE, states[HealthSignal.PINNED])
        assertEquals(HealthState.NOT_APPLICABLE, states[HealthSignal.DESTINATION_SPACE])
    }

    @Test
    fun `the local copy is pending once it exists, and the code once its check is due`() {
        val available =
            HealthFacts(
                lastHumanCheckAt = null,
                lastFullVerifyAt = null,
                lastDrillAt = null,
                lastDrillResult = null,
                deviceLocked = true,
                serviceSpaceKnown = false,
                offsiteAvailable = true,
                codeAvailable = true,
            )
        val states = states(overview(), available)

        assertEquals(HealthState.PENDING, states[HealthSignal.OFFSITE])
        assertEquals(HealthState.OK, states[HealthSignal.CODE])

        val checkedAt = Instant.parse("2026-11-01T10:00:00Z")
        val due = available.copy(codeCheckDue = true, lastCodeCheckAt = checkedAt)
        val codeRow = DataHealthRule.rows(overview(), due, pinned = null).single {
            it.signal == HealthSignal.CODE
        }
        assertEquals(HealthState.PENDING, codeRow.state)
        assertEquals(checkedAt, codeRow.at)

        val off = due.copy(codeAvailable = false)
        val offRow = DataHealthRule.rows(overview(), off, pinned = null).single {
            it.signal == HealthSignal.CODE
        }
        assertEquals(HealthState.NOT_APPLICABLE, offRow.state)
        assertEquals(null, offRow.at)
    }
}
