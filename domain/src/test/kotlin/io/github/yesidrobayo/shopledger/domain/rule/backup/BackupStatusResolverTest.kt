package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNetwork
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.model.backup.DeviceStorage
import io.github.yesidrobayo.shopledger.domain.model.backup.ServiceSpace
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El estado del respaldo, una prueba por fila de la tabla de prioridad del glosario, con los casos
 * de borde de cada plazo y del reloj atrasado. Todo se calcula con la hora que recibe: ningún
 * trabajo en segundo plano tiene que haber corrido.
 */
class BackupStatusResolverTest {
    private val now = Instant.parse("2026-11-07T15:00:00Z")

    private val unmetered =
        BackupNetwork(validated = true, metered = false, dataSaverRestricted = false)
    private val metered =
        BackupNetwork(validated = true, metered = true, dataSaverRestricted = false)

    /** WebDAV conectado, al día, con red sin datos móviles. */
    private val upToDate =
        BackupStatusFacts(
            restoreInProgress = false,
            linkState = BackupLinkState.CONNECTED,
            destinationKind = BackupDestinationKind.WEBDAV,
            policyAccepted = true,
            databaseEmpty = false,
            uploadWork = BackupUploadWork.NONE,
            changeSeq = 40,
            verifiedSeq = 40,
            firstUnsyncedAt = null,
            lastSuccessAt = now - 3.days,
            lastUploadFinishedAt = now - 3.days,
            failureCode = null,
            firstHandoffAt = null,
            lastHumanCheckAt = null,
            meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
            network = unmetered,
        )

    /** Lo mismo con tres cambios sin copia desde hace un minuto. */
    private val pending = upToDate.copy(changeSeq = 43, firstUnsyncedAt = now - 1.minutes)

    /** La nube del teléfono, con la última copia entregada y revisada ayer. */
    private val slots =
        upToDate.copy(
            destinationKind = BackupDestinationKind.DOCUMENT_SLOTS,
            firstHandoffAt = now - 30.days,
            lastHumanCheckAt = now - 1.days,
        )

    private fun status(facts: BackupStatusFacts, at: Instant = now): BackupStatus =
        BackupStatusResolver.resolve(facts, at).status

    @Test
    fun `a restore in progress wins over everything`() {
        val everythingWrong =
            pending.copy(
                restoreInProgress = true,
                linkState = BackupLinkState.NOT_CONNECTED,
                policyAccepted = false,
                failureCode = BackupFailureCode.UNEXPECTED,
                firstUnsyncedAt = now - 5.days,
            )

        assertEquals(BackupStatus.RESTORING, status(everythingWrong))
    }

    @Test
    fun `without a destination it is not connected even with a day of changes`() {
        val old = pending.copy(firstUnsyncedAt = now - 5.days, policyAccepted = false)

        assertEquals(
            BackupStatus.NOT_CONNECTED,
            status(old.copy(linkState = BackupLinkState.NOT_CONNECTED)),
        )
        // Un enlace con un destino que la app no reconoce no sube a ninguna parte.
        assertEquals(BackupStatus.NOT_CONNECTED, status(old.copy(destinationKind = null)))
    }

    @Test
    fun `without the current policy accepted copies are paused`() {
        val facts =
            pending.copy(policyAccepted = false, linkState = BackupLinkState.PERMISSION_LOST)

        assertEquals(BackupStatus.POLICY_PENDING, status(facts))
    }

    @Test
    fun `a lost permission and a conflict wait for the person before any failure`() {
        val failed = pending.copy(failureCode = BackupFailureCode.UNEXPECTED, databaseEmpty = true)

        assertEquals(
            BackupStatus.PERMISSION_LOST,
            status(failed.copy(linkState = BackupLinkState.PERMISSION_LOST)),
        )
        assertEquals(
            BackupStatus.CONFLICT,
            status(failed.copy(linkState = BackupLinkState.CONFLICT)),
        )
    }

    @Test
    fun `an empty database has nothing to back up`() {
        val facts = pending.copy(databaseEmpty = true, uploadWork = BackupUploadWork.RUNNING)

        assertEquals(BackupStatus.NOTHING_TO_BACK_UP, status(facts))
    }

    @Test
    fun `a running upload shows as uploading even with a day of changes`() {
        val facts =
            pending.copy(uploadWork = BackupUploadWork.RUNNING, firstUnsyncedAt = now - 2.days)

        assertEquals(BackupStatus.UPLOADING, status(facts))
    }

    @Test
    fun `the oldest change without a copy is stale from a full day`() {
        assertEquals(BackupStatus.STALE, status(pending.copy(firstUnsyncedAt = now - 24.hours)))
        assertEquals(
            BackupStatus.PENDING,
            status(pending.copy(firstUnsyncedAt = now - 24.hours + 1.milliseconds)),
        )
    }

    @Test
    fun `stale is measured from the oldest change and not from the last copy`() {
        // Un negocio que cerró tres días: la primera venta de hoy no es un día sin copia.
        val facts = pending.copy(lastSuccessAt = now - 3.days, firstUnsyncedAt = now - 2.minutes)

        assertEquals(BackupStatus.PENDING, status(facts))
    }

    @Test
    fun `a clock set back never says everything is fine`() {
        // La hora quedó antes del cambio más viejo sin copia.
        assertEquals(BackupStatus.STALE, status(pending.copy(firstUnsyncedAt = now + 1.hours)))
        // Y antes de la última copia, aunque el cambio parezca reciente.
        assertEquals(
            BackupStatus.STALE,
            status(pending.copy(lastSuccessAt = now + 1.hours, firstUnsyncedAt = null)),
        )
    }

    @Test
    fun `a clock set back without pending changes is still up to date`() {
        assertEquals(BackupStatus.UP_TO_DATE, status(upToDate.copy(lastSuccessAt = now + 1.days)))
    }

    @Test
    fun `pending changes without any instant cannot be measured and are only pending`() {
        val facts = pending.copy(firstUnsyncedAt = null, lastSuccessAt = null)

        assertEquals(
            BackupStatusReport(BackupStatus.PENDING, pendingChanges = 3, savingSoon = false),
            BackupStatusResolver.resolve(facts, now),
        )
    }

    @Test
    fun `stale goes before a failure so its banner shows the reason`() {
        val facts =
            pending.copy(
                failureCode = BackupFailureCode.TRANSPORT_PERSISTENT,
                firstUnsyncedAt = now - 25.hours,
            )

        assertEquals(BackupStatus.STALE, status(facts))
    }

    @Test
    fun `a failure shows as failed with or without pending changes`() {
        for (code in BackupFailureCode.entries) {
            assertEquals(code.name, BackupStatus.FAILED, status(pending.copy(failureCode = code)))
            assertEquals(code.name, BackupStatus.FAILED, status(upToDate.copy(failureCode = code)))
        }
    }

    @Test
    fun `a fatal copy keeps the changes pending and turns stale after a day`() {
        val fatal =
            pending.copy(
                failureCode = BackupFailureCode.LOCAL_INVARIANT_FATAL,
                firstUnsyncedAt = now - 2.hours,
            )

        assertEquals(BackupStatus.FAILED, status(fatal))
        assertEquals(BackupStatus.STALE, status(fatal, now + 22.hours))
    }

    @Test
    fun `with slots the check is due seven days after the last check`() {
        assertEquals(BackupStatus.HANDED_OFF, status(slots.copy(lastHumanCheckAt = now - 6.days)))
        assertEquals(BackupStatus.CHECK_DUE, status(slots.copy(lastHumanCheckAt = now - 7.days)))
    }

    @Test
    fun `without any check it counts from the first handoff`() {
        val neverChecked = slots.copy(lastHumanCheckAt = null)

        assertEquals(
            BackupStatus.HANDED_OFF,
            status(neverChecked.copy(firstHandoffAt = now - 6.days)),
        )
        assertEquals(
            BackupStatus.CHECK_DUE,
            status(neverChecked.copy(firstHandoffAt = now - 7.days)),
        )
    }

    @Test
    fun `a check in the future means the clock went back and the check is due`() {
        assertEquals(BackupStatus.CHECK_DUE, status(slots.copy(lastHumanCheckAt = now + 1.hours)))
    }

    @Test
    fun `before any handoff there is nothing to check`() {
        val first = slots.copy(firstHandoffAt = null, lastHumanCheckAt = null)

        assertEquals(BackupStatus.HANDED_OFF, status(first))
    }

    @Test
    fun `the check goes before pending so a busy business still sees it`() {
        val busy = slots.copy(changeSeq = 90, firstUnsyncedAt = now - 1.minutes)

        assertEquals(BackupStatus.CHECK_DUE, status(busy.copy(lastHumanCheckAt = now - 8.days)))
        assertEquals(BackupStatus.PENDING, status(busy))
    }

    @Test
    fun `a failure hides the due check from the status`() {
        val facts =
            slots.copy(
                lastHumanCheckAt = now - 8.days,
                failureCode = BackupFailureCode.LOCAL_INVARIANT,
            )

        assertEquals(BackupStatus.FAILED, status(facts))
    }

    @Test
    fun `with WebDAV the check is never due`() {
        val facts = upToDate.copy(firstHandoffAt = now - 30.days, lastHumanCheckAt = null)

        assertEquals(BackupStatus.UP_TO_DATE, status(facts))
    }

    @Test
    fun `slots without pending changes are handed off and never up to date`() {
        assertEquals(BackupStatus.HANDED_OFF, status(slots))
    }

    @Test
    fun `WebDAV without pending changes is up to date`() {
        assertEquals(
            BackupStatusReport(BackupStatus.UP_TO_DATE, pendingChanges = 0, savingSoon = false),
            BackupStatusResolver.resolve(upToDate, now),
        )
    }

    @Test
    fun `data saver blocks a copy that only has mobile data`() {
        val blocked = metered.copy(dataSaverRestricted = true)

        assertEquals(BackupStatus.BLOCKED_BY_DATA_SAVER, status(pending.copy(network = blocked)))
        // Con Wi-Fi el ahorro de datos no detiene nada.
        assertEquals(
            BackupStatus.PENDING,
            status(pending.copy(network = unmetered.copy(dataSaverRestricted = true))),
        )
    }

    @Test
    fun `with Wi-Fi only the copy waits for Wi-Fi whatever data saver says`() {
        val wifiOnly = pending.copy(meteredPolicy = MeteredBackupPolicy.WIFI_ONLY)

        assertEquals(
            BackupStatus.WAITING_FOR_WIFI,
            status(wifiOnly.copy(network = metered.copy(dataSaverRestricted = true))),
        )
        assertEquals(
            BackupStatus.WAITING_FOR_WIFI,
            status(
                wifiOnly.copy(network = BackupNetwork.NONE, uploadWork = BackupUploadWork.ENQUEUED),
            ),
        )
        assertEquals(BackupStatus.PENDING, status(wifiOnly.copy(network = unmetered)))
    }

    @Test
    fun `with mobile data the copy waits for the interval since the last upload`() {
        val onData = pending.copy(network = metered)

        assertEquals(
            BackupStatus.WAITING_FOR_WIFI,
            status(onData.copy(lastUploadFinishedAt = now - 59.minutes)),
        )
        assertEquals(
            BackupStatus.PENDING,
            status(onData.copy(lastUploadFinishedAt = now - 60.minutes)),
        )
        assertEquals(BackupStatus.PENDING, status(onData.copy(lastUploadFinishedAt = null)))
    }

    @Test
    fun `the shorter interval is thirty minutes`() {
        val onData =
            pending.copy(network = metered, meteredPolicy = MeteredBackupPolicy.EVERY_30_MIN)

        assertEquals(
            BackupStatus.WAITING_FOR_WIFI,
            status(onData.copy(lastUploadFinishedAt = now - 29.minutes)),
        )
        assertEquals(
            BackupStatus.PENDING,
            status(onData.copy(lastUploadFinishedAt = now - 30.minutes)),
        )
    }

    @Test
    fun `an upload in the future means the clock went back and the interval is over`() {
        val facts = pending.copy(network = metered, lastUploadFinishedAt = now + 10.minutes)

        assertEquals(BackupStatus.PENDING, status(facts))
    }

    @Test
    fun `with Wi-Fi the interval does not hold the copy`() {
        val facts = pending.copy(lastUploadFinishedAt = now - 1.minutes)

        assertEquals(BackupStatus.PENDING, status(facts))
    }

    @Test
    fun `a queued upload without a network waits for the network`() {
        val offline = pending.copy(network = BackupNetwork.NONE)

        assertEquals(
            BackupStatus.WAITING_FOR_NETWORK,
            status(offline.copy(uploadWork = BackupUploadWork.ENQUEUED)),
        )
        // Sin nada encolado todavía, es solo la espera del agrupamiento.
        assertEquals(BackupStatus.PENDING, status(offline))
        // Encolada y con red, la subida está por salir: no espera nada.
        assertEquals(
            BackupStatus.PENDING,
            status(pending.copy(uploadWork = BackupUploadWork.ENQUEUED)),
        )
        // Una red que el sistema no comprobó cuenta como ninguna, también con el ahorro de datos.
        assertEquals(
            BackupStatus.WAITING_FOR_NETWORK,
            status(
                offline.copy(
                    uploadWork = BackupUploadWork.ENQUEUED,
                    network =
                        BackupNetwork(
                            validated = false,
                            metered = true,
                            dataSaverRestricted = true,
                        ),
                ),
            ),
        )
    }

    @Test
    fun `with slots there is no network wait`() {
        val facts =
            slots.copy(
                changeSeq = 41,
                firstUnsyncedAt = now - 1.minutes,
                uploadWork = BackupUploadWork.ENQUEUED,
                network = BackupNetwork.NONE,
                meteredPolicy = MeteredBackupPolicy.WIFI_ONLY,
            )

        assertEquals(BackupStatus.PENDING, status(facts))
    }

    @Test
    fun `pending says it saves soon only for the first fifteen minutes`() {
        val fresh =
            BackupStatusResolver.resolve(pending.copy(firstUnsyncedAt = now - 14.minutes), now)
        val late =
            BackupStatusResolver.resolve(pending.copy(firstUnsyncedAt = now - 15.minutes), now)

        assertEquals(BackupStatusReport(BackupStatus.PENDING, 3, savingSoon = true), fresh)
        assertEquals(BackupStatusReport(BackupStatus.PENDING, 3, savingSoon = false), late)
    }

    @Test
    fun `with slots the fifteen minutes count from the end of the slot interval`() {
        val waiting =
            slots.copy(
                changeSeq = 41,
                firstUnsyncedAt = now - 40.minutes,
                lastUploadFinishedAt = now - 50.minutes,
            )

        // El intervalo vence en diez minutos: esperar es lo normal.
        assertTrue(BackupStatusResolver.resolve(waiting, now).savingSoon)
        // Catorce minutos después de vencer, todavía es la espera corta.
        assertTrue(BackupStatusResolver.resolve(waiting, now + 24.minutes).savingSoon)
        assertFalse(BackupStatusResolver.resolve(waiting, now + 25.minutes).savingSoon)
        // Sin subida anterior, cuenta desde el cambio más viejo.
        assertFalse(
            BackupStatusResolver.resolve(waiting.copy(lastUploadFinishedAt = null), now).savingSoon,
        )
        // Una subida en el futuro es un reloj atrasado: el intervalo ya venció.
        assertFalse(
            BackupStatusResolver.resolve(waiting.copy(lastUploadFinishedAt = now + 5.minutes), now)
                .savingSoon,
        )
    }

    @Test
    fun `with WebDAV the slot interval does not move the fifteen minutes`() {
        val facts =
            pending.copy(
                firstUnsyncedAt = now - 20.minutes,
                lastUploadFinishedAt = now - 50.minutes,
            )

        assertFalse(BackupStatusResolver.resolve(facts, now).savingSoon)
    }

    @Test
    fun `only pending says it saves soon`() {
        val waiting =
            pending.copy(network = BackupNetwork.NONE, uploadWork = BackupUploadWork.ENQUEUED)

        assertEquals(
            BackupStatusReport(BackupStatus.WAITING_FOR_NETWORK, 3, savingSoon = false),
            BackupStatusResolver.resolve(waiting, now),
        )
    }

    @Test
    fun `pending changes are the changes without a verified copy whatever the destination`() {
        assertEquals(3, BackupStatusResolver.resolve(pending, now).pendingChanges)
        assertEquals(
            3,
            BackupStatusResolver.resolve(slots.copy(changeSeq = 43), now).pendingChanges,
        )
        // Nunca negativo, aunque el estado guardado diga algo imposible.
        assertEquals(
            0,
            BackupStatusResolver.resolve(upToDate.copy(verifiedSeq = 41), now).pendingChanges,
        )
    }

    @Test
    fun `space notices go with any status and never change it`() {
        val mib = 1024L * 1024
        val low = DeviceStorage(usableBytes = 100 * mib, databaseBytes = 10 * mib)
        val disconnected = upToDate.copy(linkState = BackupLinkState.NOT_CONNECTED, storage = low)
        val failed = upToDate.copy(failureCode = BackupFailureCode.QUOTA_EXCEEDED, storage = low)

        val withoutDestination = BackupStatusResolver.resolve(disconnected, now)
        val withAFailure = BackupStatusResolver.resolve(failed, now)

        assertEquals(BackupStatus.NOT_CONNECTED, withoutDestination.status)
        assertEquals(setOf(BackupNotice.LOW_DEVICE_STORAGE), withoutDestination.notices)
        assertEquals(BackupStatus.FAILED, withAFailure.status)
        assertEquals(setOf(BackupNotice.LOW_DEVICE_STORAGE), withAFailure.notices)
    }

    @Test
    fun `a base near the restore limit warns while the copy keeps going`() {
        val gib = 1024L * 1024 * 1024
        val near = DeviceStorage(usableBytes = 10 * gib, databaseBytes = gib * 9 / 10)

        val report = BackupStatusResolver.resolve(upToDate.copy(storage = near), now)

        assertEquals(BackupStatus.UP_TO_DATE, report.status)
        assertEquals(setOf(BackupNotice.COPY_NEAR_LIMIT), report.notices)
    }

    @Test
    fun `without reading the space there is no space notice`() {
        val ample = DeviceStorage(usableBytes = 10L * 1024 * 1024 * 1024, databaseBytes = 1)

        assertEquals(emptySet<BackupNotice>(), BackupStatusResolver.resolve(upToDate, now).notices)
        assertEquals(
            emptySet<BackupNotice>(),
            BackupStatusResolver.resolve(upToDate.copy(storage = ample), now).notices,
        )
    }

    private fun notices(facts: BackupStatusFacts, at: Instant = now): Set<BackupNotice> =
        BackupStatusResolver.resolve(facts, at).notices

    @Test
    fun `a pending check stays in sight behind a failure or a day without a copy`() {
        val due = slots.copy(lastHumanCheckAt = now - 7.days)
        val repairable = due.copy(failureCode = BackupFailureCode.LOCAL_INVARIANT)
        val stale = due.copy(changeSeq = 41, firstUnsyncedAt = now - 25.hours)

        assertEquals(BackupStatus.FAILED, status(repairable))
        assertEquals(setOf(BackupNotice.HUMAN_CHECK_DUE), notices(repairable))
        assertEquals(BackupStatus.STALE, status(stale))
        assertEquals(setOf(BackupNotice.HUMAN_CHECK_DUE), notices(stale))
        assertEquals(BackupStatus.CHECK_DUE, status(due))
        assertEquals(setOf(BackupNotice.HUMAN_CHECK_DUE), notices(due))
    }

    @Test
    fun `the pending check counts from the first hand-off without any check`() {
        val never = slots.copy(lastHumanCheckAt = null, firstHandoffAt = now - 7.days)

        assertEquals(setOf(BackupNotice.HUMAN_CHECK_DUE), notices(never))
        assertEquals(
            emptySet<BackupNotice>(),
            notices(never.copy(firstHandoffAt = now - 7.days + 1.milliseconds)),
        )
        // Sin ninguna entrega no hay copia que revisar.
        assertEquals(emptySet<BackupNotice>(), notices(never.copy(firstHandoffAt = null)))
        // Un reloj atrasado nunca la esconde.
        assertEquals(
            setOf(BackupNotice.HUMAN_CHECK_DUE),
            notices(slots.copy(lastHumanCheckAt = now + 1.hours)),
        )
    }

    @Test
    fun `with constant sales the check wins over the waits and its notice goes with it`() {
        val selling =
            slots.copy(
                lastHumanCheckAt = now - 8.days,
                changeSeq = 45,
                firstUnsyncedAt = now - 1.minutes,
                network = BackupNetwork.NONE,
                uploadWork = BackupUploadWork.ENQUEUED,
            )

        assertEquals(BackupStatus.CHECK_DUE, status(selling))
        assertEquals(setOf(BackupNotice.HUMAN_CHECK_DUE), notices(selling))
    }

    @Test
    fun `without a destination or with WebDAV there is no pending check`() {
        val due = slots.copy(lastHumanCheckAt = now - 9.days)

        assertEquals(
            emptySet<BackupNotice>(),
            notices(due.copy(linkState = BackupLinkState.NOT_CONNECTED)),
        )
        assertEquals(
            emptySet<BackupNotice>(),
            notices(due.copy(destinationKind = BackupDestinationKind.WEBDAV)),
        )
        assertEquals(emptySet<BackupNotice>(), notices(due.copy(destinationKind = null)))
    }

    @Test
    fun `with WebDAV seven days without a whole read give a notice and the status stays`() {
        val read = upToDate.copy(connectedAt = now - 60.days, lastFullVerifyAt = now - 7.days)

        assertEquals(BackupStatus.UP_TO_DATE, status(read))
        assertEquals(setOf(BackupNotice.FULL_READ_OVERDUE), notices(read))
        assertEquals(
            emptySet<BackupNotice>(),
            notices(read.copy(lastFullVerifyAt = now - 7.days + 1.milliseconds)),
        )
        // Un reloj atrasado ante la última lectura nunca la esconde.
        assertEquals(
            setOf(BackupNotice.FULL_READ_OVERDUE),
            notices(read.copy(lastFullVerifyAt = now + 1.hours)),
        )
    }

    @Test
    fun `without any whole read the seven days count from the connection`() {
        val never = upToDate.copy(lastFullVerifyAt = null, connectedAt = now - 7.days)

        assertEquals(setOf(BackupNotice.FULL_READ_OVERDUE), notices(never))
        assertEquals(
            emptySet<BackupNotice>(),
            notices(never.copy(connectedAt = now - 6.days)),
        )
        // Sin la hora de la conexión no hay con qué medir.
        assertEquals(emptySet<BackupNotice>(), notices(never.copy(connectedAt = null)))
        // Sin destino, o con ranuras, no aplica.
        assertEquals(
            emptySet<BackupNotice>(),
            notices(never.copy(linkState = BackupLinkState.NOT_CONNECTED)),
        )
        assertEquals(
            emptySet<BackupNotice>(),
            notices(
                never.copy(
                    destinationKind = BackupDestinationKind.DOCUMENT_SLOTS,
                    firstHandoffAt = now - 1.days,
                ),
            ),
        )
    }

    @Test
    fun `with WebDAV a nearly full service is a notice and the status stays`() {
        val mib = 1024L * 1024
        val full = upToDate.copy(
            serviceSpace = ServiceSpace(
                availableBytes = 100 * mib,
                copyBytes =
                    50 * mib,
            ),
        )

        assertEquals(BackupStatus.UP_TO_DATE, status(full))
        assertEquals(setOf(BackupNotice.SERVICE_NEARLY_FULL), notices(full))
        assertEquals(
            emptySet<BackupNotice>(),
            notices(full.copy(serviceSpace = ServiceSpace(150 * mib, 50 * mib))),
        )
        // Con la nube del teléfono la app no conoce ese espacio.
        assertEquals(
            emptySet<BackupNotice>(),
            notices(
                full.copy(
                    destinationKind = BackupDestinationKind.DOCUMENT_SLOTS,
                    firstHandoffAt = now,
                ),
            ),
        )
    }
}
