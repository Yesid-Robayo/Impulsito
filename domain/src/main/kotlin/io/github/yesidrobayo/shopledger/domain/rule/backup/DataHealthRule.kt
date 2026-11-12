package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDrillResult
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthRow
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthSignal
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthState

/**
 * Las filas de `Salud de mis datos`, a partir del estado del respaldo y de lo que se lee aparte.
 * Solo lee: no decide nada que el respaldo no haya decidido ya, y los plazos son los de los avisos.
 *
 * - La revisión en la nube es de las ranuras y la lectura completa de WebDAV: con el otro destino
 *   no aplican. La revisión se presenta como lo que dijo la persona.
 * - El simulacro que pasó está bien; el que falló pide atención, y el saltado o el que nunca corrió
 *   quedan pendientes.
 * - Las copias fijadas y el espacio del servicio son de WebDAV: las ranuras no se fijan, y la app no
 *   sabe cuánto espacio tiene otra nube.
 */
object DataHealthRule {
    /**
     * @param pinned con WebDAV, cuántas copias de la lista están fijadas, o nulo si la lista no se
     *   pudo ver.
     */
    fun rows(overview: BackupOverview, facts: HealthFacts, pinned: Int?): List<HealthRow> {
        val kind = overview.destinationKind
        val notices = overview.report.notices
        val webDav = kind == BackupDestinationKind.WEBDAV
        val slots = kind == BackupDestinationKind.DOCUMENT_SLOTS
        return listOf(
            HealthRow(HealthSignal.LAST_COPY, lastCopyState(overview), overview.lastSuccessAt),
            HealthRow(
                HealthSignal.CLOUD_CHECK,
                dueState(slots, BackupNotice.HUMAN_CHECK_DUE in notices),
                facts.lastHumanCheckAt,
            ),
            HealthRow(
                HealthSignal.FULL_VERIFY,
                dueState(webDav, BackupNotice.FULL_READ_OVERDUE in notices),
                facts.lastFullVerifyAt,
            ),
            HealthRow(
                HealthSignal.LAST_DRILL,
                drillState(kind, facts.lastDrillResult),
                facts.lastDrillAt,
            ),
            HealthRow(
                HealthSignal.PINNED,
                if (webDav && pinned != null) HealthState.OK else HealthState.NOT_APPLICABLE,
                count = pinned?.takeIf { webDav },
            ),
            HealthRow(HealthSignal.OFFSITE, availableState(facts.offsiteAvailable)),
            HealthRow(
                HealthSignal.CODE,
                dueState(facts.codeAvailable, facts.codeCheckDue),
                facts.lastCodeCheckAt?.takeIf { facts.codeAvailable },
            ),
            HealthRow(
                HealthSignal.DEVICE_LOCK,
                if (facts.deviceLocked) HealthState.OK else HealthState.PENDING,
            ),
            HealthRow(
                HealthSignal.PHONE_SPACE,
                if (BackupNotice.LOW_DEVICE_STORAGE in notices) {
                    HealthState.ATTENTION
                } else {
                    HealthState.OK
                },
            ),
            HealthRow(HealthSignal.DESTINATION_SPACE, serviceSpaceState(webDav, notices, facts)),
        )
    }

    /** Si todo está bien o no aplica: el panel puede ir plegado. */
    fun allGood(rows: List<HealthRow>): Boolean =
        rows.all { it.state == HealthState.OK || it.state == HealthState.NOT_APPLICABLE }

    private fun lastCopyState(overview: BackupOverview): HealthState =
        when (overview.report.status) {
            BackupStatus.UP_TO_DATE,
            BackupStatus.HANDED_OFF,
            BackupStatus.CHECK_DUE,
            BackupStatus.NOTHING_TO_BACK_UP,
            -> HealthState.OK

            BackupStatus.PENDING,
            BackupStatus.UPLOADING,
            BackupStatus.RESTORING,
            BackupStatus.WAITING_FOR_NETWORK,
            BackupStatus.WAITING_FOR_WIFI,
            BackupStatus.BLOCKED_BY_DATA_SAVER,
            -> HealthState.PENDING

            BackupStatus.NOT_CONNECTED,
            BackupStatus.POLICY_PENDING,
            BackupStatus.STALE,
            BackupStatus.FAILED,
            BackupStatus.PERMISSION_LOST,
            BackupStatus.CONFLICT,
            -> HealthState.ATTENTION
        }

    private fun dueState(applies: Boolean, due: Boolean): HealthState = when {
        !applies -> HealthState.NOT_APPLICABLE
        due -> HealthState.PENDING
        else -> HealthState.OK
    }

    private fun drillState(kind: BackupDestinationKind?, result: BackupDrillResult?): HealthState {
        if (kind == null) return HealthState.NOT_APPLICABLE
        // Un simulacro que nunca corrió queda pendiente, como uno saltado.
        val known = result ?: return HealthState.PENDING
        return when (known) {
            BackupDrillResult.PASSED -> HealthState.OK
            BackupDrillResult.FAILED -> HealthState.ATTENTION
            BackupDrillResult.SKIPPED -> HealthState.PENDING
        }
    }

    private fun availableState(available: Boolean): HealthState =
        if (available) HealthState.PENDING else HealthState.NOT_APPLICABLE

    private fun serviceSpaceState(
        webDav: Boolean,
        notices: Set<BackupNotice>,
        facts: HealthFacts,
    ): HealthState = when {
        !webDav -> HealthState.NOT_APPLICABLE
        BackupNotice.SERVICE_NEARLY_FULL in notices -> HealthState.ATTENTION
        facts.serviceSpaceKnown -> HealthState.OK
        else -> HealthState.NOT_APPLICABLE
    }
}
